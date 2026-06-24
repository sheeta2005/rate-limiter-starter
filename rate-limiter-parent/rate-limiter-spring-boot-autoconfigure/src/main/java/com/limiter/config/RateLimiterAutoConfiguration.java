package com.limiter.config;

import com.limiter.aspect.RateLimiterAspect;
import com.limiter.properties.RateLimiterProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import jakarta.annotation.PostConstruct;
import java.util.Objects;

/**
 * 限流器自动配置类
 * <p>
 * Spring Boot 3.x 自动装配核心入口，负责注册限流相关Bean组件。
 * 启动时预加载Lua脚本到内存，避免运行时频繁IO读取，提升高并发性能。
 * </p>
 *
 * <h3>自动装配流程：</h3>
 * <ol>
 *   <li>加载{@link RateLimiterProperties}全局配置属性</li>
 *   <li>从classpath读取lua/limit.lua脚本内容</li>
 *   <li>创建{@link RedisScript} Bean并缓存脚本SHA1指纹</li>
 *   <li>注入{@link RedisTemplate}和脚本，创建AOP切面Bean</li>
 * </ol>
 *
 * <h3>使用方式：</h3>
 * 用户项目引入starter后，Spring Boot自动扫描META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * 完成本配置类的加载，无需手动{@code @Import}。
 *
 * @author limiter
 * @since 1.0-SNAPSHOT
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterAutoConfiguration {

    /**
     * Lua脚本文件路径（classpath相对路径）
     */
    private static final String LUA_SCRIPT_PATH = "lua/limit.lua";

    /**
     * 预缓存的Redis Lua脚本对象
     * <p>
     * 使用DefaultRedisScript封装，Spring会自动计算SHA1指纹并通过EVALSHA执行，
     * 大幅减少网络传输开销，适配JMeter万级并发压测场景。
     * </p>
     */
    private RedisScript<Long> redisScript;

    /**
     * 启动时预加载Lua脚本到内存
     * <p>
     * 在Bean初始化阶段完成脚本读取和解析，避免首次请求耗时过长。
     * 如果脚本加载失败，应用启动会直接报错（Fail-fast原则）。
     * </p>
     *
     * @throws IllegalStateException 脚本文件不存在或读取异常时抛出
     */
    @PostConstruct
    public void init() {
        ClassPathResource resource = new ClassPathResource(LUA_SCRIPT_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("限流Lua脚本不存在: " + LUA_SCRIPT_PATH);
        }

        // 创建RedisScript实例并指定返回类型为Long
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(resource));
        script.setResultType(Long.class);

        this.redisScript = script;
    }

    /**
     * 注册限流AOP切面Bean
     * <p>
     * 切面依赖RedisTemplate和预加载的Lua脚本，用于拦截@RateLimiter注解并执行限流逻辑。
     * {@code @ConditionalOnMissingBean}确保用户自定义切面时不会冲突。
     * </p>
     *
     * @param redisTemplate Redis操作模板（由spring-boot-starter-data-redis自动提供）
     * @param properties 限流全局配置属性
     * @return 限流切面对象
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterAspect rateLimiterAspect(RedisTemplate<String, Object> redisTemplate,
                                               RateLimiterProperties properties) {
        return new RateLimiterAspect(redisTemplate, redisScript, properties);
    }
}
