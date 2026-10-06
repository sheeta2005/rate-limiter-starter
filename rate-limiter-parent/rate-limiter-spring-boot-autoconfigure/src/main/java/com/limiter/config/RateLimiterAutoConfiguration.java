package com.limiter.config;

import com.limiter.aspect.RateLimiterAspect;
import com.limiter.properties.RateLimiterProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 限流器自动配置类
 * <p>
 * 通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 注册。
 * 仅在 classpath 存在 Redis、容器中存在 StringRedisTemplate 且 rate-limiter.enabled 不为 false 时装配。
 * </p>
 *
 * @author limiter
 * @since 1.0.0
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "rate-limiter", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterAspect rateLimiterAspect(StringRedisTemplate stringRedisTemplate,
                                               RateLimiterProperties properties) {
        return new RateLimiterAspect(stringRedisTemplate,
                loadScript("lua/fixed_window.lua"),
                loadScript("lua/sliding_window.lua"),
                properties);
    }

    private static RedisScript<Long> loadScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // 初始化时读取脚本并缓存内容，资源缺失直接阻止限流组件启动。
        try {
            script.setScriptText(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法加载限流脚本: " + path, e);
        }
        script.setResultType(Long.class);
        script.getSha1();
        return script;
    }
}
