package com.limiter.aspect;

import com.limiter.annotation.RateLimitAlgorithm;
import com.limiter.annotation.RateLimiter;
import com.limiter.exception.RateLimitException;
import com.limiter.properties.FailStrategy;
import com.limiter.properties.RateLimiterProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/**
 * 限流 AOP 切面
 * <p>
 * 拦截标记了 {@link RateLimiter} 的方法，按算法选择对应 Lua 脚本执行原子限流。
 * 两种算法都在 Lua 内以 Redis 服务端时间为准，避免依赖应用实例时钟。
 * </p>
 *
 * @author limiter
 * @since 1.0.0
 */
@Aspect
public class RateLimiterAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    private final StringRedisTemplate redisTemplate;

    private final RedisScript<Long> fixedWindowScript;

    private final RedisScript<Long> slidingWindowScript;

    private final RateLimiterProperties properties;

    public RateLimiterAspect(StringRedisTemplate redisTemplate,
                             RedisScript<Long> fixedWindowScript,
                             RedisScript<Long> slidingWindowScript,
                             RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.fixedWindowScript = fixedWindowScript;
        this.slidingWindowScript = slidingWindowScript;
        this.properties = properties;
    }

    @Around("@annotation(limiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter limiter) throws Throwable {
        int limit = limiter.limit() > 0 ? limiter.limit() : properties.getDefaultLimit();
        int window = limiter.window() > 0 ? limiter.window() : properties.getDefaultWindow();
        RateLimitAlgorithm algorithm = limiter.algorithm() != RateLimitAlgorithm.DEFAULT
                ? limiter.algorithm()
                : properties.getDefaultAlgorithm();

        String businessKey = parseSpEL(joinPoint, limiter.key());
        String redisKey = buildRedisKey(businessKey, window, algorithm);

        Long result = executeScript(redisKey, limit, window, algorithm);

        if (result != null && result == 1L) {
            return joinPoint.proceed();
        }

        log.warn("限流触发 | Key: {} | Limit: {}/{}s | Algorithm: {}",
                redisKey, limit, window, algorithm);
        throw new RateLimitException(limiter.message());
    }

    private String parseSpEL(ProceedingJoinPoint joinPoint, String spEL) {
        if (spEL == null || spEL.trim().isEmpty()) {
            return getDefaultKey(joinPoint);
        }

        try {
            Expression expression = PARSER.parseExpression(spEL);
            Object value = expression.getValue(buildEvaluationContext(joinPoint));
            if (value == null) {
                log.warn("SpEL 表达式解析结果为 null: {}，回退为类名.方法名", spEL);
                return getDefaultKey(joinPoint);
            }
            return value.toString();
        } catch (Exception e) {
            log.error("SpEL 表达式解析失败: {}，错误: {}，回退为类名.方法名", spEL, e.getMessage());
            return getDefaultKey(joinPoint);
        }
    }

    private EvaluationContext buildEvaluationContext(ProceedingJoinPoint joinPoint) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 接口代理暴露的方法可能没有参数名，优先读取实现方法。
        Method method = AopUtils.getMostSpecificMethod(signature.getMethod(), joinPoint.getTarget().getClass());
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        if (parameterNames == null) {
            parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(signature.getMethod());
        }
        Object[] args = joinPoint.getArgs();

        // 参数位置不依赖业务项目是否开启 -parameters。
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        return context;
    }

    private String getDefaultKey(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    private String buildRedisKey(String businessKey, int window, RateLimitAlgorithm algorithm) {
        // 固定窗口使用 HASH，滑动窗口使用 ZSET；不同算法与窗口分别存储。
        return properties.getKeyPrefix() + algorithm.name() + ":" + window + ":" + businessKey;
    }

    private Long executeScript(String redisKey, int limit, int window,
                               RateLimitAlgorithm algorithm) {
        try {
            Long result;
            if (algorithm == RateLimitAlgorithm.SLIDING_WINDOW) {
                String member = UUID.randomUUID().toString();
                result = redisTemplate.execute(slidingWindowScript,
                        Collections.singletonList(redisKey),
                        String.valueOf(limit), String.valueOf(window), member);
            } else {
                result = redisTemplate.execute(fixedWindowScript,
                        Collections.singletonList(redisKey),
                        String.valueOf(limit), String.valueOf(window));
            }
            if (result == null || (result != 0L && result != 1L)) {
                throw new IllegalStateException("限流脚本返回了无效结果: " + result);
            }
            return result;
        } catch (Exception e) {
            log.error("限流脚本执行失败 | Key: {} | Error: {}", redisKey, e.getMessage());
            if (properties.getFailStrategy() == FailStrategy.DENY) {
                log.warn("fail-close：Redis 故障，拒绝请求 | Key: {}", redisKey);
                return 0L;
            }
            log.warn("fail-open：Redis 故障，放行请求 | Key: {}", redisKey);
            return 1L;
        }
    }
}
