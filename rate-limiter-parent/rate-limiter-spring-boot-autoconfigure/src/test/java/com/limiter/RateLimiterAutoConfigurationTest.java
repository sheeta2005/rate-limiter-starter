package com.limiter;

import com.limiter.annotation.RateLimiter;
import com.limiter.aspect.RateLimiterAspect;
import com.limiter.config.RateLimiterAutoConfiguration;
import com.limiter.exception.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.io.ClassPathResource;

import com.limiter.properties.RateLimiterProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class RateLimiterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                    RateLimiterAutoConfiguration.class))
            .withUserConfiguration(TestServiceConfiguration.class);

    @Service
    static class TestService {

        @RateLimiter(limit = 3, window = 60)
        public String call() {
            return "ok";
        }

        @RateLimiter
        public String businessFailure() {
            throw new IllegalArgumentException("业务异常");
        }
    }

    @Configuration
    static class TestServiceConfiguration {

        @Bean
        TestService testService() {
            return new TestService();
        }
    }

    private StringRedisTemplate failingRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("mock connection failure"));
        return template;
    }

    @Test
    void aspectBeanIsCreatedWhenRedisTemplatePresent() {
        contextRunner
                .withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .run(context -> assertThat(context).hasSingleBean(RateLimiterAspect.class));
    }

    @Test
    void aspectBeanIsAbsentWhenRedisTemplateMissing() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(RateLimiterAspect.class));
    }

    @Test
    void aspectBeanIsAbsentWhenDisabled() {
        contextRunner
                .withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .withPropertyValues("rate-limiter.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RateLimiterAspect.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(TestService.class))).isFalse();
                });
    }

    @Test
    void failsOpenByDefaultWhenRedisDown() {
        contextRunner
                .withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .run(context -> assertDoesNotThrow(() ->
                        context.getBean(TestService.class).call()));
    }

    @Test
    void failsCloseWhenConfiguredDeny() {
        contextRunner
                .withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .withPropertyValues("rate-limiter.fail-strategy=deny")
                .run(context -> assertThrows(RateLimitException.class, () ->
                        context.getBean(TestService.class).call()));
    }

    @Test
    void defaultRedisAutoConfigurationCreatesAspectWithoutCustomTemplate() {
        // 真实自动配置创建 StringRedisTemplate，不手工提供模板，也不需要连接 Redis。
        contextRunner.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(StringRedisTemplate.class);
                    assertThat(context).hasSingleBean(RateLimiterAspect.class);
                });
    }

    @Test
    void absentRedisClassesDoNotBreakStartup() {
        contextRunner.withClassLoader(new FilteredClassLoader("org.springframework.data.redis"))
                .run(context -> assertThat(context).doesNotHaveBean(RateLimiterAspect.class));
    }

    @Test
    void missingLuaResourceFailsDuringStartup() {
        // 不创建业务代理，避免类加载异常掩盖真正的脚本资源错误。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RateLimiterAutoConfiguration.class))
                .withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .withClassLoader(new FilteredClassLoader(new ClassPathResource("lua/fixed_window.lua")))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(java.io.FileNotFoundException.class)
                            .rootCause().hasMessageContaining("lua/fixed_window.lua");
                });
    }

    @Test
    void customAspectMakesAutoConfigurationBackOff() {
        RateLimiterAspect customAspect = mock(RateLimiterAspect.class);
        contextRunner.withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .withBean(RateLimiterAspect.class, () -> customAspect)
                .run(context -> assertThat(context.getBean(RateLimiterAspect.class)).isSameAs(customAspect));
    }

    @Test
    void invalidGlobalDefaultsFailDuringStartup() {
        for (String property : List.of("rate-limiter.default-limit=0",
                "rate-limiter.default-window=-1", "rate-limiter.default-algorithm=default",
                "rate-limiter.key-prefix=")) {
            contextRunner.withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                    .withPropertyValues(property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void builtInDefaultsRemainAvailable() {
        contextRunner.withBean(StringRedisTemplate.class, this::failingRedisTemplate)
                .run(context -> {
                    RateLimiterProperties properties = context.getBean(RateLimiterProperties.class);
                    assertThat(properties.getDefaultLimit()).isEqualTo(100);
                    assertThat(properties.getDefaultWindow()).isEqualTo(60);
                });
    }

    @Test
    void businessExceptionIsNotSwallowedByFailOpen() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(1L);
        contextRunner.withBean(StringRedisTemplate.class, () -> template)
                .run(context -> {
                    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                            () -> context.getBean(TestService.class).businessFailure());
                    assertThat(exception).hasMessage("业务异常");
                    verify(template).execute(any(RedisScript.class), any(List.class), any(Object[].class));
                });
    }

    @Test
    void disabledLimiterNeverAccessesRedis() {
        StringRedisTemplate template = failingRedisTemplate();
        contextRunner.withBean(StringRedisTemplate.class, () -> template)
                .withPropertyValues("rate-limiter.enabled=false")
                .run(context -> {
                    assertThat(context.getBean(TestService.class).call()).isEqualTo("ok");
                    verify(template, never()).execute(any(RedisScript.class), any(List.class), any(Object[].class));
                });
    }

    @Test
    void invalidScriptResultUsesConfiguredFailureStrategy() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenReturn(null, 2L);
        contextRunner.withBean(StringRedisTemplate.class, () -> template)
                .withPropertyValues("rate-limiter.fail-strategy=deny")
                .run(context -> {
                    assertThrows(RateLimitException.class, () -> context.getBean(TestService.class).call());
                    assertThrows(RateLimitException.class, () -> context.getBean(TestService.class).call());
                });
    }
}
