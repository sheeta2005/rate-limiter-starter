package com.limiter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式限流注解
 * <p>
 * 用于标记需要限流保护的方法，基于Redis + Lua实现固定窗口计数限流。
 * 支持SpEL表达式动态解析限流维度（如用户ID、IP地址、接口参数等）。
 * </p>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 按用户ID限流：每秒最多10次请求
 * @RateLimiter(key = "#userId", limit = 10, window = 1)
 * public void getUserInfo(String userId) { ... }
 *
 * // 按接口维度限流：每分钟最多100次请求（使用默认key）
 * @RateLimiter(limit = 100, window = 60)
 * public void queryOrders() { ... }
 *
 * // 自定义错误消息
 * @RateLimiter(key = "#ip", limit = 50, message = "IP访问频率超限")
 * public void login(String ip) { ... }
 * }</pre>
 *
 * @author limiter
 * @since 1.0-SNAPSHOT
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 限流Key的SpEL表达式
     * <p>
     * 支持动态解析方法参数、Spring上下文变量等，例如：
     * <ul>
     *   <li>{@code #userId} - 使用方法参数userId的值</li>
     *   <li>{@code #request.remoteAddress} - 使用HttpServletRequest的IP</li>
     *   <li>{@code T(java.time.LocalDate).now()} - 使用静态方法</li>
     * </ul>
     * </p>
     * <p>
     * 如果未指定或为空字符串，自动回退为"类名.方法名"作为限流Key
     * </p>
     *
     * @return SpEL表达式字符串，默认为空
     */
    String key() default "";

    /**
     * 限流阈值：时间窗口内允许的最大请求次数
     * <p>
     * 默认值100次，配合默认窗口60秒，即每分钟最多100次请求
     * </p>
     *
     * @return 最大请求次数，默认100
     */
    int limit() default 100;

    /**
     * 时间窗口大小（单位：秒）
     * <p>
     * 默认值60秒，表示固定窗口的持续时间
     * </p>
     *
     * @return 窗口时长（秒），默认60
     */
    int window() default 60;

    /**
     * 限流触发时的错误消息
     * <p>
     * 可自定义异常提示信息，默认返回"请求过于频繁，请稍后重试"
     * </p>
     *
     * @return 错误提示消息
     */
    String message() default "请求过于频繁，请稍后重试";
}
