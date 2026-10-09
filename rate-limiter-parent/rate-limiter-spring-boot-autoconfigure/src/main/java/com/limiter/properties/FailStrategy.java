package com.limiter.properties;

/**
 * Redis 不可用时的限流降级策略：PASS 放行，DENY 拒绝
 */
public enum FailStrategy {

    PASS,

    DENY
}
