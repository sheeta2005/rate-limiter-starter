package com.limiter.aspect;

import com.limiter.annotation.RateLimiter;
import com.limiter.exception.RateLimitException;
import com.limiter.properties.RateLimiterProperties;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 限流AOP切面
 * <p>
 * 拦截标记了{@link RateLimiter}注解的方法，基于Redis + Lua脚本实现分布式限流。
 * 核心流程：
 * <ol>
 *   <li>解析注解参数，提取限流配置（key、limit、window）</li>
 *   <li>通过SpEL表达式动态计算限流Key（支持方法参数、Spring上下文）</li>
 *   <li>构造Redis Key格式：rate_limit:{业务Key}:{窗口起始时间戳}</li>
 *   <li>执行预缓存的Lua脚本进行原子计数判断</li>
 *   <li>根据返回值决定放行或抛出限流异常</li>
 * </ol>
 * </p>
 *
 * <h3>高并发优化：</h3>
 * <ul>
 *   <li>Lua脚本启动时预加载，避免运行时IO开销</li>
 *   <li>使用EVALSHA命令减少网络传输（SHA1指纹匹配）</li>
 *   <li>无同步锁设计，完全依赖Redis原子操作</li>
 *   <li>适配JMeter万级并发压测场景</li>
 * </ul>
 *
 * @author limiter
 * @since 1.0-SNAPSHOT
 */
@Aspect
public class RateLimiterAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    /**
     * Redis Key前缀标识
     */
    private static final String REDIS_KEY_PREFIX = "rate_limit:";

    /**
     * SpEL表达式解析器
     */
    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /**
     * 方法参数名发现器（用于SpEL表达式绑定参数）
     */
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * Redis操作模板
     */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 预缓存的Lua限流脚本（启动时已加载）
     */
    private final RedisScript<Long> redisScript;

    /**
     * 限流全局配置属性
     */
    private final RateLimiterProperties properties;

    /**
     * 构造切面对象
     *
     * @param redisTemplate Redis操作模板
     * @param redisScript   预加载的Lua脚本
     * @param properties    限流全局配置
     */
    public RateLimiterAspect(RedisTemplate<String, Object> redisTemplate,
                             RedisScript<Long> redisScript,
                             RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.redisScript = redisScript;
        this.properties = properties;
    }

    /**
     * 环绕通知：拦截@RateLimiter注解的方法
     * <p>
     * 执行流程：
     * <ol>
     *   <li>检查全局开关，未启用则直接放行</li>
     *   <li>解析注解配置，合并全局默认值</li>
     *   <li>通过SpEL解析动态限流Key</li>
     *   <li>构造Redis Key并执行Lua脚本</li>
     *   <li>根据脚本返回值决定放行或抛异常</li>
     * </ol>
     * </p>
     *
     * @param joinPoint 连接点对象
     * @param limiter   限流注解实例
     * @return 目标方法返回值
     * @throws Throwable 目标方法异常或限流异常
     */
    @Around("@annotation(limiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter limiter) throws Throwable {
        // 检查全局开关，未启用则直接放行
        if (!properties.isEnabled()) {
            log.debug("限流功能未启用，直接放行");
            return joinPoint.proceed();
        }

        // 解析限流配置：优先使用注解值，否则回退全局默认值
        int limit = limiter.limit() > 0 ? limiter.limit() : properties.getDefaultLimit();
        int window = limiter.window() > 0 ? limiter.window() : properties.getDefaultWindow();

        // 解析SpEL表达式获取业务限流Key
        String businessKey = parseSpEL(joinPoint, limiter.key());

        // 构造Redis Key：包含窗口时间戳实现自动过期隔离
        String redisKey = buildRedisKey(businessKey, window);

        log.debug("执行限流检查 | Key: {} | Limit: {} | Window: {}s", redisKey, limit, window);

        // 执行Lua脚本进行原子限流判断
        Long result = executeLuaScript(redisKey, limit, window);

        // 根据返回值判断是否限流：1=允许，0=拦截
        if (result != null && result == 1) {
            // 允许通过，执行目标方法
            return joinPoint.proceed();
        } else {
            // 触发限流，抛出异常
            log.warn("限流触发 | Key: {} | Limit: {}/{}", redisKey, limit, window);
            throw new RateLimitException(limiter.message());
        }
    }

    /**
     * 解析SpEL表达式获取动态限流Key
     * <p>
     * 支持的表达式示例：
     * <ul>
     *   <li>{@code #userId} - 使用方法参数userId的值</li>
     *   <li>{@code #request.remoteAddress} - 使用HttpServletRequest属性</li>
     *   <li>{@code T(java.time.LocalDate).now()} - 调用静态方法</li>
     *   <li>空字符串或未指定 - 回退为"类名.方法名"</li>
     * </ul>
     * </p>
     *
     * @param joinPoint 连接点对象
     * @param spEL      SpEL表达式字符串
     * @return 解析后的限流Key
     */
    private String parseSpEL(ProceedingJoinPoint joinPoint, String spEL) {
        // 未指定SpEL表达式，回退为类名.方法名
        if (spEL == null || spEL.trim().isEmpty()) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            return method.getDeclaringClass().getName() + "." + method.getName();
        }

        try {
            // 解析SpEL表达式
            Expression expression = PARSER.parseExpression(spEL);
            EvaluationContext context = buildEvaluationContext(joinPoint);

            // 执行表达式获取结果
            Object value = expression.getValue(context);
            if (value == null) {
                log.warn("SpEL表达式解析结果为null: {}, 回退为类名.方法名", spEL);
                return getDefaultKey(joinPoint);
            }

            return value.toString();
        } catch (Exception e) {
            log.error("SpEL表达式解析失败: {}, 错误: {}, 回退为类名.方法名", spEL, e.getMessage());
            return getDefaultKey(joinPoint);
        }
    }

    /**
     * 构建SpEL评估上下文（绑定方法参数到表达式变量）
     *
     * @param joinPoint 连接点对象
     * @return SpEL评估上下文
     */
    private EvaluationContext buildEvaluationContext(ProceedingJoinPoint joinPoint) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        // 获取方法签名和参数名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        Object[] args = joinPoint.getArgs();

        // 将方法参数绑定到SpEL上下文
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        return context;
    }

    /**
     * 获取默认限流Key（类名.方法名）
     *
     * @param joinPoint 连接点对象
     * @return 默认Key
     */
    private String getDefaultKey(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    /**
     * 构造Redis Key
     * <p>
     * 格式：rate_limit:{业务Key}:{窗口起始时间戳}
     * 例如：rate_limit:com.example.UserService.getUser:1703462400
     * </p>
     * <p>
     * 时间戳作用：每个窗口生成独立Key，配合Lua脚本中的EXPIRE实现自动清理
     * </p>
     *
     * @param businessKey 业务限流Key（如用户ID、IP、接口名）
     * @param window      时间窗口大小（秒）
     * @return Redis Key
     */
    private String buildRedisKey(String businessKey, int window) {
        // 计算当前窗口的起始时间戳（向下取整到窗口边界）
        long currentTimestamp = System.currentTimeMillis() / 1000;
        long windowStart = currentTimestamp - (currentTimestamp % window);

        return REDIS_KEY_PREFIX + businessKey + ":" + windowStart;
    }

    /**
     * 执行Lua脚本进行限流判断
     * <p>
     * 使用EVALSHA命令（Spring自动优化），首次执行后通过SHA1指纹调用，
     * 大幅减少网络传输开销，适配高并发场景。
     * </p>
     *
     * @param redisKey Redis Key
     * @param limit    限流阈值
     * @param window   时间窗口（秒）
     * @return Lua脚本返回值：1=允许，0=拦截，null=执行失败
     */
    private Long executeLuaScript(String redisKey, int limit, int window) {
        try {
            // 执行Lua脚本（Spring自动使用EVALSHA优化）
            Long result = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(limit),
                    String.valueOf(window)
            );

            log.debug("Lua脚本执行结果 | Key: {} | Result: {}", redisKey, result);
            return result;
        } catch (Exception e) {
            log.error("Lua脚本执行失败 | Key: {} | Error: {}", redisKey, e.getMessage(), e);
            // 执行失败时降级策略：放行请求（避免误杀）
            log.warn("限流脚本执行失败，降级放行请求");
            return 1L;
        }
    }
}
