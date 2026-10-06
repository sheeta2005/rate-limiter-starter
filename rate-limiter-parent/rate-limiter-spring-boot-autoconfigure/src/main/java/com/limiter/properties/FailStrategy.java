package com.limiter.properties;

/**
 * Redis 不可用时的限流降级策略
 * <p>
 * PASS 为放行（fail-open，默认），优先保证可用性；
 * DENY 为拒绝（fail-close），优先保护下游，Redis 故障时直接拒绝请求。
 * </p>
 *
 * @author limiter
 * @since 1.0.0
 */
public enum FailStrategy {

    PASS,

    DENY
}
