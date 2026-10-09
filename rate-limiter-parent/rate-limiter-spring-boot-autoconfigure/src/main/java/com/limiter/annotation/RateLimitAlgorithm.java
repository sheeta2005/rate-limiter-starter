package com.limiter.annotation;

/**
 * 限流算法类型：FIXED_WINDOW 固定窗口，SLIDING_WINDOW 滑动窗口，DEFAULT 回退全局配置
 */
public enum RateLimitAlgorithm {

    DEFAULT,

    FIXED_WINDOW,

    SLIDING_WINDOW
}
