package com.limiter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式限流注解
 * <p>
 * 标记需要限流保护的方法，基于 Redis + Lua 实现限流，支持固定窗口与滑动窗口两种算法。
 * 支持 SpEL 表达式动态解析限流维度（用户ID、IP地址、接口参数等）。
 * </p>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 按用户ID固定窗口限流：每秒最多10次
 * @RateLimiter(key = "#userId", limit = 10, window = 1)
 * public void getUserInfo(String userId) { ... }
 *
 * // 滑动窗口限流：每分钟最多100次
 * @RateLimiter(limit = 100, window = 60, algorithm = RateLimitAlgorithm.SLIDING_WINDOW)
 * public void queryOrders() { ... }
 * }</pre>
 *
 * @author limiter
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 限流 Key 的 SpEL 表达式
     * <p>
     * 支持 #userId、#request.remoteAddress、T(java.time.LocalDate).now() 等写法。
     * 未指定时回退为“类名.方法名”。
     * </p>
     *
     * @return SpEL 表达式，默认为空
     */
    String key() default "";

    /**
     * 限流阈值：时间窗口内允许的最大请求次数
     * <p>
     * 未指定（-1）时回退全局配置 rate-limiter.default-limit。
     * </p>
     *
     * @return 最大请求次数，默认 -1
     */
    int limit() default -1;

    /**
     * 时间窗口大小（秒）
     * <p>
     * 未指定（-1）时回退全局配置 rate-limiter.default-window。
     * </p>
     *
     * @return 窗口时长（秒），默认 -1
     */
    int window() default -1;

    /**
     * 限流算法
     * <p>
     * DEFAULT 时回退全局配置 rate-limiter.default-algorithm。
     * </p>
     *
     * @return 限流算法，默认 DEFAULT
     */
    RateLimitAlgorithm algorithm() default RateLimitAlgorithm.DEFAULT;

    /**
     * 限流触发时的错误消息
     *
     * @return 错误提示消息
     */
    String message() default "请求过于频繁，请稍后重试";
}
