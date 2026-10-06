package com.limiter.exception;

/**
 * 限流异常
 * <p>
 * 请求超过限流阈值时抛出，用于快速中断请求流程，不阻塞线程。
 * 业务方可通过 @RestControllerAdvice 捕获并返回 429 状态码。
 * </p>
 *
 * @author limiter
 * @since 1.0.0
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
