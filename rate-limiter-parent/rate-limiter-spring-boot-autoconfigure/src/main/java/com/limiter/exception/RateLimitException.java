package com.limiter.exception;

/**
 * 限流异常：请求超过限流阈值时抛出
 */
public class RateLimitException extends RuntimeException {

    //创建限流异常
    public RateLimitException(String message) {
        super(message);
    }
}
