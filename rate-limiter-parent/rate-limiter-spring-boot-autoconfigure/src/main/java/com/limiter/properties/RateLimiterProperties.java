package com.limiter.properties;

import com.limiter.annotation.RateLimitAlgorithm;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流器全局配置属性
 * <p>
 * 绑定 application.yml 中 rate-limiter 前缀的配置项。
 * 配置优先级：注解属性 &gt; 全局配置 &gt; 硬编码默认值。
 * </p>
 *
 * <h3>配置示例：</h3>
 * <pre>{@code
 * rate-limiter:
 *   enabled: true
 *   key-prefix: "rate_limit:"
 *   default-limit: 100
 *   default-window: 60
 *   default-algorithm: fixed_window
 *   fail-strategy: pass
 * }</pre>
 *
 * @author limiter
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties implements InitializingBean {

    private boolean enabled = true;

    private String keyPrefix = "rate_limit:";

    private int defaultLimit = 100;

    private int defaultWindow = 60;

    private RateLimitAlgorithm defaultAlgorithm = RateLimitAlgorithm.FIXED_WINDOW;

    private FailStrategy failStrategy = FailStrategy.PASS;

    /** 配置绑定完成后校验，防止非法窗口或算法进入限流脚本。 */
    @Override
    public void afterPropertiesSet() {
        if (defaultLimit <= 0 || defaultWindow <= 0) {
            throw new IllegalArgumentException("default-limit 和 default-window 必须大于 0");
        }
        if (defaultAlgorithm == null || defaultAlgorithm == RateLimitAlgorithm.DEFAULT) {
            throw new IllegalArgumentException("default-algorithm 必须是 fixed_window 或 sliding_window");
        }
        if (failStrategy == null || keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("fail-strategy 和 key-prefix 不能为空");
        }
    }

    //获取限流开关
    public boolean isEnabled() {
        return enabled;
    }

    //设置限流开关
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    //获取限流键前缀
    public String getKeyPrefix() {
        return keyPrefix;
    }

    //设置限流键前缀
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    //获取默认限流阈值
    public int getDefaultLimit() {
        return defaultLimit;
    }

    //设置默认限流阈值
    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    //获取默认窗口秒数
    public int getDefaultWindow() {
        return defaultWindow;
    }

    //设置默认窗口秒数
    public void setDefaultWindow(int defaultWindow) {
        this.defaultWindow = defaultWindow;
    }

    //获取默认限流算法
    public RateLimitAlgorithm getDefaultAlgorithm() {
        return defaultAlgorithm;
    }

    //设置默认限流算法
    public void setDefaultAlgorithm(RateLimitAlgorithm defaultAlgorithm) {
        this.defaultAlgorithm = defaultAlgorithm;
    }

    //获取 Redis 故障策略
    public FailStrategy getFailStrategy() {
        return failStrategy;
    }

    //设置 Redis 故障策略
    public void setFailStrategy(FailStrategy failStrategy) {
        this.failStrategy = failStrategy;
    }
}
