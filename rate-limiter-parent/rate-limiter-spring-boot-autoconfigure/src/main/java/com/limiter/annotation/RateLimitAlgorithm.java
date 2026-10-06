package com.limiter.annotation;

/**
 * 限流算法类型
 * <p>
 * FIXED_WINDOW 为固定窗口计数器，SLIDING_WINDOW 为滑动窗口（基于 Redis ZSET）。
 * DEFAULT 表示注解未指定算法，回退到全局配置 rate-limiter.default-algorithm。
 * </p>
 *
 * @author limiter
 * @since 1.0.0
 */
public enum RateLimitAlgorithm {

    DEFAULT,

    FIXED_WINDOW,

    SLIDING_WINDOW
}
