package com.limiter.exception;

/**
 * 限流异常
 * <p>
 * 当请求超过配置的限流阈值时抛出此异常，用于快速中断请求流程，不阻塞线程。
 * 在JMeter高并发压测场景下，该异常会被全局异常处理器捕获并返回429状态码。
 * </p>
 *
 * <h3>触发时机：</h3>
 * <ul>
 *   <li>Redis Lua脚本返回0（表示当前窗口计数已超过阈值）</li>
 *   <li>AOP切面拦截到{@code @RateLimiter}注解且限流条件满足</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * @RestControllerAdvice
 * public class GlobalExceptionHandler {
 *     @ExceptionHandler(RateLimitException.class)
 *     public ResponseEntity<String> handleRateLimit(RateLimitException e) {
 *         return ResponseEntity.status(429).body(e.getMessage());
 *     }
 * }
 * }</pre>
 *
 * @author limiter
 * @since 1.0-SNAPSHOT
 */
public class RateLimitException extends RuntimeException {

    /**
     * 构造限流异常
     *
     * @param message 限流错误提示消息
     */
    public RateLimitException(String message) {
        super(message);
    }

    /**
     * 构造限流异常（无参构造，使用默认消息）
     */
    public RateLimitException() {
        super("请求过于频繁，请稍后重试");
    }
}
