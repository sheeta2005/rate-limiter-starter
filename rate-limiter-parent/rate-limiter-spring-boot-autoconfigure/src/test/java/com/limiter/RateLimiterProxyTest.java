package com.limiter;

import com.limiter.annotation.RateLimiter;
import com.limiter.config.RateLimiterAutoConfiguration;
import com.limiter.exception.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证接口代理能从实现方法读取参数名与限流注解。 */
class RateLimiterProxyTest {

    interface UserService {
        //按用户调用测试服务
        String call(String argument);
    }

    static class UserServiceImpl implements UserService {
        //按用户执行限流测试
        @Override
        @RateLimiter(key = "#userId", limit = 1, window = 60)
        public String call(String userId) {
            return userId;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ServiceConfiguration {
        //注册用户测试服务
        @Bean
        UserService userService() {
            return new UserServiceImpl();
        }
    }

    //验证接口代理读取实现方法参数名
    @Test
    void implementationParameterNamesAreUsedWithJdkProxy() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(0L);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        RateLimiterAutoConfiguration.class))
                .withUserConfiguration(ServiceConfiguration.class)
                .withBean(StringRedisTemplate.class, () -> template)
                .withPropertyValues("spring.aop.proxy-target-class=false")
                .run(context -> {
                    UserService service = context.getBean(UserService.class);
                    assertThat(AopUtils.isJdkDynamicProxy(service)).isTrue();
                    assertThrows(RateLimitException.class, () -> service.call("interface-user"));
                    verify(template).execute(any(RedisScript.class),
                            eq(List.of("rate_limit:FIXED_WINDOW:60:interface-user")),
                            eq(new Object[]{"1", "60"}));
                });
    }
}
