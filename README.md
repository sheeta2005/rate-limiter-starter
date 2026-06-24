
# Rate Limiter Spring Boot Starter

基于 Redis + Lua 实现的分布式限流 Starter，支持 Spring Boot 3.2.8+、JDK 21，适配 JMeter 高并发压测场景。

## 📋 特性

- ✅ **固定窗口计数算法**：原子操作保证高并发下计数准确
- ✅ **SpEL 动态限流维度**：支持按用户ID、IP、接口参数等灵活配置
- ✅ **启动时预加载 Lua**：消除运行时 IO 开销
- ✅ **EVALSHA 优化**：SHA1 指纹匹配，减少网络传输
- ✅ **三级配置优先级**：注解属性 > 全局配置 > 默认值
- ✅ **降级策略**：Redis 异常时自动放行请求

---

## 🚀 快速开始

### 1. 引入依赖

在项目的 `pom.xml` 中添加：

```
xml
<dependency>
<groupId>com.limiter</groupId>
<artifactId>rate-limiter-spring-boot-starter</artifactId>
<version>1.0-SNAPSHOT</version>
</dependency>
```
### 2. 配置 Redis

在 `application.yml` 中配置 Redis 连接信息：

```
yaml
spring:
data:
redis:
host: localhost
port: 6379
password: your_password  # 可选
database: 0
```
### 3. 使用限流注解

```
java
@RestController
@RequestMapping("/api/user")
public class UserController {

    /**
     * 按用户ID限流：每分钟最多50次请求
     */
    @RateLimiter(key = "#userId", limit = 50, window = 60)
    @GetMapping("/{userId}")
    public User getUser(@PathVariable String userId) {
        return userService.findById(userId);
    }

    /**
     * 按接口维度限流：每秒最多10次请求
     */
    @RateLimiter(limit = 10, window = 1, message = "系统繁忙，请稍后重试")
    @PostMapping("/order")
    public Order createOrder(@RequestBody OrderRequest request) {
        return orderService.create(request);
    }

    /**
     * 使用默认配置（60秒窗口，100次阈值）
     */
    @RateLimiter
    @GetMapping("/list")
    public List<User> listUsers() {
        return userService.list();
    }
}
```
---

## ⚙️ 配置说明

### 全局配置（可选）

在 `application.yml` 中配置全局限流默认参数：

```
yaml
rate-limiter:
enabled: true          # 是否启用限流功能，默认true
default-limit: 100     # 默认限流阈值（次/窗口），默认100
default-window: 60     # 默认时间窗口（秒），默认60
```
### 注解属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `key` | String | `""` | SpEL表达式，未指定时回退为"类名.方法名" |
| `limit` | int | `100` | 时间窗口内允许的最大请求次数 |
| `window` | int | `60` | 时间窗口大小（单位：秒） |
| `message` | String | `"请求过于频繁，请稍后重试"` | 限流触发时的错误消息 |

### 配置优先级

```

注解属性 > 全局配置 > 硬编码默认值
```
示例：
- 注解设置 `limit=50` → 使用 50
- 注解未设置 `limit`，全局配置 `default-limit=200` → 使用 200
- 注解和全局都未设置 → 使用 100

---

## 💡 使用示例

### 示例1：按用户ID限流

```
java
@RateLimiter(key = "#userId", limit = 30, window = 60)
public UserProfile getUserProfile(String userId) {
// 每个用户每分钟最多访问30次
}
```
### 示例2：按IP地址限流

```
java
@RateLimiter(key = "#request.remoteAddress", limit = 100, window = 60)
public void login(HttpServletRequest request, LoginRequest loginRequest) {
// 每个IP每分钟最多登录100次
}
```
### 示例3：按接口参数组合限流

```
java
@RateLimiter(key = "#productId + '_' + #userId", limit = 10, window = 60)
public void purchaseProduct(String productId, String userId) {
// 每个用户对每个商品每分钟最多购买10次
}
```
### 示例4：自定义错误消息

```
java
@RateLimiter(
key = "#phone",
limit = 5,
window = 3600,
message = "验证码发送过于频繁，请1小时后再试"
)
public void sendSmsCode(String phone) {
// 每个手机号每小时最多发送5次验证码
}
```
---

## 🔧 高级用法

### SpEL 表达式支持

`key` 属性支持完整的 SpEL 表达式语法：

```
java
// 使用方法参数
@RateLimiter(key = "#userId")

// 使用对象属性
@RateLimiter(key = "#request.userId")

// 调用静态方法
@RateLimiter(key = "T(java.time.LocalDate).now()")

// 字符串拼接
@RateLimiter(key = "'prefix_' + #userId")

// 条件表达式
@RateLimiter(key = "#userId != null ? #userId : 'anonymous'")
```
### 全局开关控制

可以通过配置动态开启/关闭限流功能：

```
yaml
# 开发环境关闭限流
rate-limiter:
enabled: false

# 生产环境开启限流
rate-limiter:
enabled: true
default-limit: 200
default-window: 60
```
### 自定义全局异常处理

```
java
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 捕获限流异常，返回429状态码
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 429);
        result.put("message", e.getMessage());
        result.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.status(429).body(result);
    }
}
```
---

## 📊 JMeter 压测最佳实践

### 1. Redis 性能调优

确保 Redis 服务器配置适合高并发：

```
bash
# redis.conf
maxclients 10000
tcp-backlog 511
timeout 0
tcp-keepalive 300
```
### 2. Lettuce 连接池配置（可选）

虽然 Starter 未暴露连接池配置，但可以在应用中自定义：

```
java
@Configuration
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory lettuceConnectionFactory() {
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(100);
        poolConfig.setMaxIdle(50);
        poolConfig.setMinIdle(10);
        
        LettucePoolingClientConfiguration poolingConfig = LettucePoolingClientConfiguration.builder()
            .poolConfig(poolConfig)
            .build();
        
        return new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("localhost", 6379),
            poolingConfig
        );
    }
}
```
### 3. 压测脚本示例

```
jmeter
线程组配置：
- 线程数：1000
- Ramp-Up时间：10秒
- 循环次数：永久
- 调度器持续时间：300秒

HTTP请求：
- 路径：/api/user/${__Random(1,1000)}
- 断言：响应码 = 200 或 429
```
### 4. 监控指标

建议监控以下指标：
- Redis CPU 使用率
- Redis 内存使用量
- 限流触发次数（通过日志统计）
- 接口响应时间 P95/P99

---

## 🛠️ 故障排查

### 问题1：限流不生效

**检查清单：**
1. Redis 连接是否正常
2. `rate-limiter.enabled` 是否为 `true`
3. 方法是否添加了 `@RateLimiter` 注解
4. 注解是否在 Spring Bean 中（@Component、@Service、@Controller 等）

### 问题2：SpEL 表达式解析失败

**解决方案：**
- 检查参数名是否正确（需要编译时保留调试信息）
- 查看日志中的错误提示
- 简化表达式或使用字符串常量

### 问题3：高并发下性能下降

**优化建议：**
1. 检查 Redis 服务器负载
2. 增加 Redis 连接池大小
3. 调整限流阈值和窗口大小
4. 使用 Redis 集群分散压力

### 问题4：Lua 脚本执行失败

**排查步骤：**
1. 检查 Redis 版本是否支持 Lua（Redis 2.6+）
2. 查看应用日志中的异常堆栈
3. 验证 `lua/limit.lua` 文件是否存在于 classpath

---

## 📝 架构说明

### 核心组件

```

┌─────────────────────────────────────────────┐
│           @RateLimiter 注解                  │
└────────────────┬────────────────────────────┘
│
▼
┌─────────────────────────────────────────────┐
│         RateLimiterAspect (AOP切面)          │
│  1. 解析注解配置                              │
│  2. SpEL 表达式解析                          │
│  3. 构造 Redis Key                           │
│  4. 执行 Lua 脚本                            │
│  5. 判断限流结果                              │
└────────────────┬────────────────────────────┘
│
▼
┌─────────────────────────────────────────────┐
│         Redis + Lua 脚本                     │
│  - 原子操作：GET + INCR + EXPIRE             │
│  - 返回值：1=允许，0=拦截                     │
└─────────────────────────────────────────────┘
```
### Redis Key 格式

```

rate_limit:{业务Key}:{窗口起始时间戳}
```
示例：
```

rate_limit:com.example.UserService.getUser:1703462400
rate_limit:user123:1703462460
```
### 限流算法

**固定窗口计数器：**
1. 计算当前窗口的起始时间戳
2. 构造 Redis Key（包含时间戳）
3. GET 获取当前计数
4. 如果不存在或已过期，初始化为 1 并设置过期时间
5. 如果计数 < 阈值，INCR 并返回 1（允许）
6. 如果计数 >= 阈值，返回 0（拦截）

---

## 📄 License

本项目仅供学习参考使用。

---

## 🤝 技术支持

如有问题，请提交 Issue 或联系开发团队。
```


