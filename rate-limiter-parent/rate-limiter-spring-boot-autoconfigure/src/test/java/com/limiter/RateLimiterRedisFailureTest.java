package com.limiter;

import com.limiter.annotation.RateLimiter;
import com.limiter.aspect.RateLimiterAspect;
import com.limiter.config.RateLimiterAutoConfiguration;
import com.limiter.exception.RateLimitException;
import com.limiter.properties.FailStrategy;
import com.limiter.properties.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 使用独立的真实 Redis 容器验证连接中断后的放行与拒绝。 */
@Testcontainers
class RateLimiterRedisFailureTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4.9"))
            .withExposedPorts(6379);

    static class TestService {
        //执行故障测试请求
        @RateLimiter(limit = 100, window = 60)
        public String call() {
            return "正常执行";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ServiceConfiguration {
        //注册故障测试服务
        @Bean
        TestService testService() {
            return new TestService();
        }
    }

    //验证真实 Redis 断连时的故障策略
    @Test
    void realRedisOutageHonorsBothFailureStrategies() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class,
                        AopAutoConfiguration.class, RateLimiterAutoConfiguration.class))
                .withUserConfiguration(ServiceConfiguration.class)
                .withPropertyValues("spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(6379),
                        "spring.data.redis.timeout=300ms", "spring.data.redis.connect-timeout=300ms",
                        "spring.data.redis.lettuce.shutdown-timeout=100ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(RateLimiterAspect.class);
                    TestService service = context.getBean(TestService.class);
                    assertThat(service.call()).isEqualTo("正常执行");

                    // 主动停止测试专用容器，避免故障测试依赖 Mock 或影响其他用例。
                    redis.stop();
                    assertThat(service.call()).isEqualTo("正常执行");
                    context.getBean(RateLimiterProperties.class).setFailStrategy(FailStrategy.DENY);
                    assertThrows(RateLimitException.class, service::call);
                });
    }
}
