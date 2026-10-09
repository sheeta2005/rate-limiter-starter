package com.limiter.annotation;

import java.lang.annotation.*;

/**
 * 分布式限流注解：标记需要限流保护的方法，基于 Redis + Lua 实现，支持两种窗口算法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 限流 Key 的 SpEL 表达式，未指定时回退为“类名.方法名”
     * @return SpEL 表达式，默认为空
     */
    String key() default "";

    /**
     * 限流阈值：时间窗口内允许的最大请求次数，未指定时回退全局配置
     * @return 最大请求次数，默认 -1
     */
    int limit() default -1;

    /**
     * 时间窗口大小（秒），未指定时回退全局配置
     * @return 窗口时长（秒），默认 -1
     */
    int window() default -1;

    /**
     * 限流算法，DEFAULT 时回退全局配置
     * @return 限流算法，默认 DEFAULT
     */
    RateLimitAlgorithm algorithm() default RateLimitAlgorithm.DEFAULT;

    /**
     * 限流触发时的错误消息
     * @return 错误提示消息
     */
    String message() default "请求过于频繁，请稍后重试";
}
