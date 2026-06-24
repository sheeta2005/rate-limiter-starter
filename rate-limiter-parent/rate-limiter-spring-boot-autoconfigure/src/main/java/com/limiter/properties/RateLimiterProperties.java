package com.limiter.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流器全局配置属性
 * <p>
 * 通过{@code @ConfigurationProperties}绑定application.yml中的配置项，
 * 提供全局限流默认参数。优先级低于注解上的局部配置。
 * </p>
 *
 * <h3>配置示例：</h3>
 * <pre>{@code
 * # application.yml
 * rate-limiter:
 *   enabled: true          # 是否启用限流功能
 *   default-limit: 200     # 默认限流阈值（次/窗口）
 *   default-window: 30     # 默认时间窗口（秒）
 * }</pre>
 *
 * <h3>配置优先级：</h3>
 * <ol>
 *   <li>注解属性{@code @RateLimiter(limit=50)} > 全局配置{@code default-limit}</li>
 *   <li>全局配置{@code default-limit} > 硬编码默认值100</li>
 * </ol>
 *
 * @author limiter
 * @since 1.0-SNAPSHOT
 */
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    /**
     * 是否启用限流功能
     * <p>
     * 默认true，设置为false时切面不执行限流逻辑，直接放行请求
     * </p>
     */
    private boolean enabled = true;

    /**
     * 默认限流阈值：时间窗口内允许的最大请求次数
     * <p>
     * 默认值100次，当注解未指定limit时使用此值
     * </p>
     */
    private int defaultLimit = 100;

    /**
     * 默认时间窗口大小（单位：秒）
     * <p>
     * 默认值60秒，当注解未指定window时使用此值
     * </p>
     */
    private int defaultWindow = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getDefaultWindow() {
        return defaultWindow;
    }

    public void setDefaultWindow(int defaultWindow) {
        this.defaultWindow = defaultWindow;
    }
}
