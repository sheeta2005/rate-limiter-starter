# Rate Limiter Spring Boot Starter

基于 Redis、Lua 和 Spring AOP 的分布式限流组件。业务方法使用 `@RateLimiter` 声明规则，多个应用实例通过 Redis 共享计数。

当前构建基于 Spring Boot 3.2.5、JDK 21 和 Maven，功能测试使用 Testcontainers 1.21.4 启动 Redis 7.4.9。兼容性以当前构建和测试环境为准。

## 功能

- 固定窗口计数和基于 ZSET 的滑动窗口，可通过注解或全局配置选择。
- 两种算法均使用 Redis 服务端时间，Lua 原子执行计数和拦截判断。
- SpEL 支持按用户、IP、方法参数及对象属性构造限流维度。
- 阈值、窗口和算法按照“注解显式配置 > 全局配置 > 内置默认值”生效。
- Redis 操作异常时，可配置放行（`pass`）或拒绝（`deny`）。
- 关闭组件或缺少 Redis 模板时不创建限流切面。
- 初始化时将 Lua 资源读入内存并计算 SHA1；执行时由 Spring Data Redis 优先尝试 EVALSHA，缓存未命中时回退 EVAL。

## 本地安装与接入

项目包含父工程、核心自动配置模块及空 Starter 模块，尚未声明已发布到 Maven Central。先在仓库根目录安装到本地 Maven 仓库：

```bash
mvn -B -ntp -f rate-limiter-parent/pom.xml clean install
```

完整构建会执行真实 Redis 功能测试，需要 Docker 正常运行。首次运行需要下载测试镜像。

在业务项目中引入 Starter：

```xml
<dependency>
    <groupId>com.limiter</groupId>
    <artifactId>rate-limiter-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

配置 Redis 连接：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
```

默认 Redis Starter 使用 Lettuce，其底层网络通信依赖 Netty。无需业务方手工声明 Netty 版本，依赖版本由 Spring Boot BOM 管理。

在 Spring 管理的业务 Bean 的公开方法上使用注解：

```java
import com.limiter.annotation.RateLimiter;
import org.springframework.stereotype.Service;

@Service
public class SmsService {

    // 每个手机号在一个固定小时窗口内最多发送 5 次。
    @RateLimiter(key = "'sms:' + #p0", limit = 5, window = 3600,
            message = "验证码发送过于频繁，请稍后重试")
    public void sendCode(String phone) {
        // 执行实际发送业务。
    }
}
```

注解只对经过 Spring AOP 代理的调用生效。同一个对象内部通过 `this` 调用、手工创建的对象、私有方法以及无法被代理的方法不适用。

## 配置

```yaml
rate-limiter:
  enabled: true
  key-prefix: "rate_limit:"
  default-limit: 100
  default-window: 60
  default-algorithm: fixed_window
  fail-strategy: pass
```

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `enabled` | `true` | 启动时决定是否创建限流组件 |
| `key-prefix` | `rate_limit:` | Redis Key 前缀，不能为空 |
| `default-limit` | `100` | 默认请求阈值，必须大于零 |
| `default-window` | `60` | 默认窗口秒数，必须大于零 |
| `default-algorithm` | `fixed_window` | `fixed_window` 或 `sliding_window` |
| `fail-strategy` | `pass` | Redis 操作异常时 `pass` 放行或 `deny` 拒绝 |

`enabled` 是启动配置，修改后需要重启；本组件不提供运行时配置刷新。全局配置非法时，已启用的组件会启动失败，不将配置错误视为 Redis 故障。

| 注解属性 | 默认值 | 含义 |
| --- | --- | --- |
| `key` | 空字符串 | SpEL 表达式；为空或解析失败时回退为类名和方法名 |
| `limit` | `-1` | 大于零时覆盖全局阈值，否则继承全局配置 |
| `window` | `-1` | 大于零时覆盖全局窗口秒数，否则继承全局配置 |
| `algorithm` | `DEFAULT` | 继承全局算法，或指定 `FIXED_WINDOW` / `SLIDING_WINDOW` |
| `message` | `请求过于频繁，请稍后重试` | 超限或配置为拒绝且 Redis 操作失败时的异常消息 |

例如，全局 `default-limit=200` 时，单独使用 `@RateLimiter` 会采用 200；显式指定 `limit=50` 则采用 50；两者都未配置时采用内置默认值 100。

## SpEL 与限流维度

```java
// 按用户限流，不依赖编译时保留参数名。
@RateLimiter(key = "'user:' + #p0")

// 按业务参数名访问，业务项目必须启用 javac -parameters。
@RateLimiter(key = "'user:' + #userId")

// 读取第一个方法参数对象的属性。
@RateLimiter(key = "'user:' + #a0.userId")

// 滑动窗口：任意连续 60 秒最多允许 30 次。
@RateLimiter(key = "'login:' + #p0", limit = 30, window = 60,
        algorithm = RateLimitAlgorithm.SLIDING_WINDOW)
```

`#p0` 和 `#a0` 都代表第一个参数，后续依次使用 `#p1`、`#a1`。按名称访问参数时优先读取实现方法，因此接口代理也可以使用实现方法中保留的参数名。

Servlet 请求的 IP 属性为 `remoteAddr`，例如 `#p0.remoteAddr`；代理转发后的真实 IP 应由业务系统结合可信代理配置处理。

SpEL 为空、返回 null 或解析错误时，记录日志并回退为“类全限定名.方法名”，此时用户维度会合并成方法维度。默认 Key 不区分同名重载方法，需要区分时显式设置业务 Key。

Redis Key 格式：`{前缀}{算法名称}:{窗口秒数}:{业务Key}`。例如 `rate_limit:SLIDING_WINDOW:60:login:user123`。相同算法、窗口和业务 Key 会共享状态，阈值不同也不会自动分离；跨接口共享额度时应保持规则一致，不共享时应加业务前缀。

## 算法与边界

| 算法 | 存储方式 | 特点与适用场景 |
| --- | --- | --- |
| 固定窗口 | HASH 保存窗口编号及计数 | 状态量小，适合按对齐时间段限制次数 |
| 滑动窗口 | ZSET 保存通过请求的时间及唯一标识 | 限制任意连续窗口的通过次数，适合登录、验证码等需要更平滑约束的场景 |

固定窗口以 Redis 时间划分对齐窗口。Lua 在窗口编号变化时重置计数，通过 HINCRBY 获取次数并设置过期时间。拒绝请求也计入当前窗口计数。它存在临界突刺：旧窗口末尾与新窗口开头分别通过阈值数量的请求，短时间内可能通过接近两倍阈值的请求。

滑动窗口通过 Redis TIME 获取微秒时间，清除窗口外记录，再判断当前数量；未超限时写入 UUID 唯一成员，拒绝请求不写入。窗口定义为 `(当前时间 - 窗口长度, 当前时间]`。存储量随窗口内通过请求数增长，清理记录也有成本。

两种脚本都只访问传入的一个 Key，不在脚本中拼接动态 Key。应用实例无需以本地时钟划分窗口，但 Redis 服务端仍应保持正常的时间同步。当前功能测试针对单节点 Redis，未验证 Redis Cluster 拓扑或故障转移。

## 故障策略与异常

- `pass`：Redis 连接或脚本执行异常时记录日志并放行，优先保证业务可用性；此时限流保护无法保证。
- `deny`：Redis 连接或脚本执行异常时拒绝请求，优先保护下游，抛出 `RateLimitException`。

正常超限会抛出 `RateLimitException`。业务方可以通过 `@RestControllerAdvice` 和 `@ExceptionHandler(RateLimitException.class)` 返回 HTTP 429；组件自身不绑定 Web 框架，也不会自动写入 HTTP 响应。

业务方法本身的异常正常传播，不受 Redis 故障策略影响。Lua 文件缺失或无法读取会在组件初始化时导致启动失败。

## 功能验证与构建

```bash
mvn -B -ntp -f rate-limiter-parent/pom.xml clean verify
```

测试覆盖默认自动装配、条件退出、配置校验、故障放行与拒绝、业务异常传播、接口代理参数解析，以及真实 Redis 下的双算法、阈值拦截、窗口恢复、用户隔离和 SpEL。集成测试不会因缺少 Docker 自动跳过，未满足测试环境时构建失败。

故障测试使用独立 Redis 容器，在建立连接后主动停止容器，验证实际连接中断时的两种策略；Lua 资源缺失也有启动失败用例。

若 Windows 环境出现 `WEPollSelectorImpl` / `Unable to establish loopback connection`，应检查 JDK 的本地回环管道及临时目录。可通过 Maven 的 `-DargLine` 给测试 JVM 设置 `-Djdk.net.unixdomain.tmpdir=实际存在且可写的目录` 后重新验证；该参数只用于处理环境问题，组件不会修改系统临时目录。

`.github/workflows/build.yml` 定义 JDK 21 下的 Maven 构建及测试报告归档。只有文件上传到 GitHub 并触发工作流后才会运行远程 CI，本地构建结果不代表远程 CI 已通过。

## 项目结构

- `rate-limiter-parent`：聚合工程，统一依赖及构建插件。
- `rate-limiter-spring-boot-autoconfigure`：注解、配置绑定、自动装配、切面、Lua 脚本及测试。
- `rate-limiter-spring-boot-starter`：空 Starter，依赖核心模块。

调用流程：业务代理方法 → 注解与配置合并 → SpEL 解析 → Redis Key → Lua 原子判断 → 业务执行或限流异常。

## 许可

许可证尚未确定，当前未授予开源许可。以仓库后续确认并添加的 LICENSE 为准。
