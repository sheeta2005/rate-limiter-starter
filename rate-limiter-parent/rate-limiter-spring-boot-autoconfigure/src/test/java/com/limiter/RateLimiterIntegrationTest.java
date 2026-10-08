package com.limiter;

import com.limiter.annotation.RateLimitAlgorithm;
import com.limiter.annotation.RateLimiter;
import com.limiter.exception.RateLimitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = RateLimiterIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "rate-limiter.default-limit=3",
        "rate-limiter.default-window=2",
        "rate-limiter.default-algorithm=sliding_window",
        "rate-limiter.fail-strategy=deny"
})
@Testcontainers
class RateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.9"))
            .withExposedPorts(6379);

    //绑定测试 Redis 地址
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // 不扫描同包中的其他测试配置，避免测试 Bean 覆盖或装配顺序掩盖真实问题。
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        //注册测试服务
        @Bean
        TestService testService() {
            return new TestService();
        }
    }

    static class TestService {

        //按用户执行固定窗口限流
        @RateLimiter(key = "#userId", limit = 5, window = 60,
                algorithm = RateLimitAlgorithm.FIXED_WINDOW)
        public String fixedByUser(String userId) {
            return "ok";
        }

        //使用默认键执行固定窗口限流
        @RateLimiter(limit = 3, window = 60, algorithm = RateLimitAlgorithm.FIXED_WINDOW)
        public String fixedDefaultKey() {
            return "ok";
        }

        //按用户执行滑动窗口限流
        @RateLimiter(key = "#userId", limit = 5, window = 60,
                algorithm = RateLimitAlgorithm.SLIDING_WINDOW)
        public String slidingByUser(String userId) {
            return "ok";
        }

        //使用全局默认规则限流
        @RateLimiter(key = "#userId")
        public String useGlobalDefaults(String userId) {
            return "ok";
        }

        //按请求对象中的用户限流
        @RateLimiter(key = "#request.userId", limit = 5, window = 60)
        public String spelByDto(TestRequest request) {
            return "ok";
        }

        //按参数位置解析限流键
        @RateLimiter(key = "#p0", limit = 1, window = 60)
        public String positionalParameter(String userId) {
            return "ok";
        }

        //使用独立窗口按用户限流
        @RateLimiter(key = "#userId", limit = 1, window = 120)
        public String differentWindow(String userId) {
            return "ok";
        }

        //使用一秒滑动窗口限流
        @RateLimiter(key = "#userId", limit = 1, window = 1,
                algorithm = RateLimitAlgorithm.SLIDING_WINDOW)
        public String shortSlidingWindow(String userId) {
            return "ok";
        }

        //模拟 SpEL 返回空值
        @RateLimiter(key = "#missing", limit = 1, window = 60)
        public String nullSpel() {
            return "ok";
        }

        //模拟 SpEL 语法错误
        @RateLimiter(key = "#(", limit = 1, window = 60)
        public String invalidSpel() {
            return "ok";
        }
    }

    static class TestRequest {

        private final String userId;

        //创建测试请求
        TestRequest(String userId) {
            this.userId = userId;
        }

        //获取测试用户标识
        public String getUserId() {
            return userId;
        }
    }

    @Autowired
    TestService service;

    @Autowired
    StringRedisTemplate redisTemplate;

    //清理测试限流数据
    @BeforeEach
    void clearLimiterKeys() {
        // Redis 容器只供本测试使用，清理限流 key 以隔离每个用例。
        Set<String> keys = redisTemplate.keys("rate_limit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    //验证固定窗口超限拦截
    @Test
    void fixedWindowAllowsFiveThenBlocks() {
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.fixedByUser("fixed-user"));
        }
        assertThrows(RateLimitException.class, () -> service.fixedByUser("fixed-user"));
        assertThat(redisTemplate.opsForHash().get("rate_limit:FIXED_WINDOW:60:fixed-user", "count"))
                .isEqualTo("6");
    }

    //验证默认键的固定窗口限流
    @Test
    void fixedWindowDefaultKeyAllowsThreeThenBlocks() {
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> service.fixedDefaultKey());
        }
        assertThrows(RateLimitException.class, () -> service.fixedDefaultKey());
    }

    //验证滑动窗口超限拦截
    @Test
    void slidingWindowAllowsFiveThenBlocks() {
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.slidingByUser("sliding-user"));
        }
        assertThrows(RateLimitException.class, () -> service.slidingByUser("sliding-user"));
    }

    //验证全局默认规则生效
    @Test
    void globalDefaultsAreApplied() {
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> service.useGlobalDefaults("global-user"));
        }
        assertThrows(RateLimitException.class, () -> service.useGlobalDefaults("global-user"));
        assertThat(redisTemplate.opsForZSet().zCard("rate_limit:SLIDING_WINDOW:2:global-user"))
                .isEqualTo(3L);
    }

    //验证 SpEL 解析对象属性
    @Test
    void spelResolvesDtoProperty() {
        TestRequest request = new TestRequest("spel-user");
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(() -> service.spelByDto(request));
        }
        assertThrows(RateLimitException.class, () -> service.spelByDto(request));
    }

    //验证固定窗口切换后重置计数
    @Test
    void fixedWindowResetsAfterRollover() {
        for (int i = 0; i < 5; i++) {
            service.fixedByUser("reset-fixed");
        }
        assertThrows(RateLimitException.class, () -> service.fixedByUser("reset-fixed"));

        // 保留计数，把 HASH 内窗口编号设为旧窗口，验证脚本按服务端时间重置。
        String key = "rate_limit:FIXED_WINDOW:60:reset-fixed";
        redisTemplate.opsForHash().put(key, "window", "0");
        assertDoesNotThrow(() -> service.fixedByUser("reset-fixed"));
        assertThat(redisTemplate.opsForHash().get(key, "count")).isEqualTo("1");
        assertThat(redisTemplate.getExpire(key)).isPositive();
    }

    //验证滑动窗口过期后恢复放行
    @Test
    void slidingWindowResetsAfterWindowPasses() throws InterruptedException {
        service.shortSlidingWindow("reset-sliding");
        assertThrows(RateLimitException.class, () -> service.shortSlidingWindow("reset-sliding"));

        Thread.sleep(1200);

        assertDoesNotThrow(() -> service.shortSlidingWindow("reset-sliding"));
    }

    //验证不同用户的限流额度独立
    @Test
    void differentUsersHaveIndependentLimits() {
        for (int i = 0; i < 5; i++) {
            service.fixedByUser("user-a");
        }
        assertThrows(RateLimitException.class, () -> service.fixedByUser("user-a"));
        assertDoesNotThrow(() -> service.fixedByUser("user-b"));
    }

    //验证不同算法和窗口的限流键独立
    @Test
    void algorithmsAndWindowsUseIndependentKeys() {
        for (int i = 0; i < 5; i++) {
            service.fixedByUser("shared-user");
            service.slidingByUser("shared-user");
        }
        assertThrows(RateLimitException.class, () -> service.fixedByUser("shared-user"));
        assertThrows(RateLimitException.class, () -> service.slidingByUser("shared-user"));
        assertDoesNotThrow(() -> service.differentWindow("shared-user"));
        assertThrows(RateLimitException.class, () -> service.differentWindow("shared-user"));
    }

    //验证位置参数可区分用户
    @Test
    void positionalSpelSeparatesUsers() {
        service.positionalParameter("pos-a");
        assertThrows(RateLimitException.class, () -> service.positionalParameter("pos-a"));
        assertDoesNotThrow(() -> service.positionalParameter("pos-b"));
    }

    //验证对象属性可区分用户
    @Test
    void dtoSpelSeparatesUsers() {
        for (int i = 0; i < 5; i++) {
            service.spelByDto(new TestRequest("dto-a"));
        }
        assertThrows(RateLimitException.class, () -> service.spelByDto(new TestRequest("dto-a")));
        assertDoesNotThrow(() -> service.spelByDto(new TestRequest("dto-b")));
    }

    //验证 SpEL 异常或空值时使用默认键
    @Test
    void invalidOrNullSpelFallsBackToMethodKey() {
        service.nullSpel();
        service.invalidSpel();
        assertThrows(RateLimitException.class, service::nullSpel);
        assertThrows(RateLimitException.class, service::invalidSpel);
    }

    //验证滑动窗口只清理过期请求
    @Test
    void slidingWindowOnlyRemovesExpiredRequests() {
        Long nowUs = redisTemplate.execute(new DefaultRedisScript<>(
                "local t=redis.call('TIME'); return t[1]*1000000+t[2]", Long.class), List.of());
        assertThat(nowUs).isNotNull();
        String key = "rate_limit:SLIDING_WINDOW:60:partial-expiry";
        redisTemplate.opsForZSet().add(key, "expired", nowUs - 61_000_000D);
        for (int i = 0; i < 4; i++) {
            redisTemplate.opsForZSet().add(key, "recent-" + i, nowUs - 30_000_000D);
        }
        service.slidingByUser("partial-expiry");
        assertThrows(RateLimitException.class, () -> service.slidingByUser("partial-expiry"));
        assertThat(redisTemplate.opsForZSet().zCard(key)).isEqualTo(5L);
        assertThat(redisTemplate.opsForZSet().score(key, "expired")).isNull();
        assertThat(redisTemplate.getExpire(key)).isPositive();
    }
}
