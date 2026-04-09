# Contributing to RecordPlatform

本文档定义了 RecordPlatform 的开发规范，适用于所有贡献者（人类与 AI agent）。

规范分为三层递进结构：
- **L1 核心公约** — 6 条铁律，所有贡献者必读
- **L2 领域规范** — 15 个维度的详细规则，按需查阅
- **L3 决策记录** — 每条规则的 Why + Anti-pattern，理解背景时查阅

> **导航提示**：每条规则有唯一 ID（如 `[C-01]`），可通过 ID 在 CLAUDE.md 等 AI 指令文件中引用。

---

# L1 核心公约

以下 6 条规则是本项目最高优先级的工程约束。违反任何一条都应在 Code Review 中阻断合并。

## [C-01] 边界验证，内部信任

仅在系统边界做输入校验，内部服务间调用信任参数。

**边界位置**：
- Controller 层：`@Valid` / `@NotNull` / 自定义 Validator
- Dubbo Provider 入口：基础非空校验
- 外部 API 响应：状态码 + 数据完整性检查
- 用户上传内容：文件类型、大小、内容扫描

**内部信任区域**：
- Service → Service 调用
- Service → DAO 调用
- Dubbo Consumer → Provider（已通过 Consumer 端校验）

## [C-02] 契约先行

REST 端点变更必须同步更新 OpenAPI spec 和前端类型定义。

**流程**：
1. 修改后端 Controller / DTO / VO
2. 本地启动后端，导出 `openapi.json`
3. 执行 `cd platform-frontend && pnpm types:gen`
4. 提交更新后的 `generated.ts` 到同一 PR
5. CI `contract-consistency` job 自动验证漂移

**硬性要求**：任何修改了 REST 接口签名的 PR，如果 `generated.ts` 未同步更新，CI 将阻断合并。

## [C-03] 写操作必须可补偿

涉及多服务的写操作必须设计补偿路径。

**分类处理**：
- **单服务写操作**：必须在数据库事务内（`@Transactional`）
- **跨服务写操作**：Saga 模式 + 补偿事务（参考 `FileSagaOrchestrator`）
- **跨服务异步通知**：Outbox 模式（本地事务写 Outbox 表 → 定时发 MQ）

**补偿设计要求**：每个 Saga 步骤必须定义 `execute()` 和 `compensate()`，失败时逆序触发补偿。

## [C-04] 测试隔离

测试不依赖外部共享环境，不依赖执行顺序。

**隔离层级**：
- **单元测试**（`*Test.java`）：零外部依赖，Mock 仅限外部边界（Dubbo Provider、第三方 API），禁止 Mock 内部 Service
- **集成测试**（`*IT.java`）：Testcontainers 自管理 MySQL + RabbitMQ，测试间数据隔离
- **前端测试**：jsdom + MSW mock 网络请求，fake-indexeddb 模拟存储
- **测试数据**：Builder 模式构建（`FileTestBuilder.aFile()`），`@ExtendWith(BuilderResetExtension.class)` 重置 ID 计数器

## [C-05] 安全默认

安全机制必须是默认行为，而非可选配置。

**强制执行**：
- 对外暴露的实体 ID 必须经 `SecureIdCodec` 混淆（AES-256-CTR + HMAC）
- SQL 一律使用参数化查询（MyBatis `#{}`），**绝对禁止** `${}` 接收用户输入
- JWT 过滤器必须验证 `tenantId` 一致性（Token 内 vs 请求头 `X-Tenant-ID`）
- 资源访问必须校验所有权（`@RequireOwnership`）或角色（`@PreAuthorize`）
- 敏感参数（密码、Token、密钥）不得出现在日志中（`OperationLogAspect` 自动脱敏）

## [C-06] 可观测优先

新增业务操作必须可追踪、可度量、可告警。

**必须项**：
- Controller 方法标注 `@OperationLog(module, operationType, description)`
- 关键业务指标通过 Micrometer 暴露（命名：`app.{module}.{metric_name}`）
- 异常使用结构化 `ResultEnum` 编码，不得抛出裸 `RuntimeException`

**自动项**（无需手动干预）：
- OTel Java Agent 自动注入 distributed trace
- MDC 自动填充 `traceId` + `userId`（由 JWT 过滤器设置）

---

# L2 领域规范

## 2.1 架构与分层

### [A-01] Controller 职责边界

Controller 只负责三件事：参数校验、调用 Service、返回 `Result<T>`。

**允许**：`@Valid` 注解、Service 方法调用、`Result.success(data)` 包装
**禁止**：业务逻辑、数据库操作、直接调用 DAO/Mapper、try-catch 业务异常（由全局 Handler 处理）

### [A-02] Command/Query 分离

Service 层区分写操作（Command）和读操作（Query）。

- **Command Service**（如 `FileService`）：处理创建、更新、删除，走事务 + Saga
- **Query Service**（如 `FileQueryService`）：处理查询，走缓存 + Virtual Thread 并发
- 读写分离后，Query Service 可独立做缓存优化和水平扩展

### [A-03] 服务间通信边界

跨服务调用走 Dubbo Triple 协议，**禁止**：
- 跨服务直连数据库
- 共享 DAO 层代码
- 直接 HTTP 调用（绕过注册中心）

所有 Dubbo 调用必须通过 `RemoteClient` 包装，统一配置 Circuit Breaker + Retry。

### [A-04] 前端状态管理

Store（`.svelte.ts`）是唯一状态源。

- 全局状态通过 `$lib/stores/` 下的 `.svelte.ts` 文件 export
- 组件通过 import 消费 Store，禁止 prop drilling 超过 2 层
- 临时 UI 状态（表单输入、展开/折叠）保留在组件内部

## 2.2 错误处理

### [E-01] 错误分类

| 类型 | HTTP 状态码 | 载体 | 示例 |
|------|-------------|------|------|
| 业务错误 | 200 | `Result.code` = ResultEnum 业务码 | 文件不存在、余额不足 |
| 协议错误 | 400/401/403 | 标准 HTTP 语义 | 参数格式错误、未认证 |
| 系统错误 | 500 | `Result.code` = 40xxx | 内部异常、限流触发 |

### [E-02] 异常类约束

仅允许两种自定义异常：
- `GeneralException(ResultEnum)` — 标准业务异常，全局 Handler 捕获返回 `Result`
- `RetryableException(ResultEnum, suggestedRetryAfterSeconds)` — 瞬时故障，附带重试建议

**禁止**新增其他自定义异常类。所有业务错误场景通过扩展 `ResultEnum` 枚举值表达。

### [E-03] ResultEnum 编码段

| 范围 | 领域 |
|------|------|
| 200 | 成功 |
| 10000-19999 | 参数校验 |
| 20000-29999 | 用户/认证 |
| 30000-39999 | 外部服务（区块链、存储、熔断） |
| 40000-49999 | 系统错误（限流、上传会话） |
| 50000-59999 | 业务数据（文件、分享） |
| 60000-69999 | 消息/好友/工单 |
| 70000-79999 | 权限控制 |

新增业务码时，必须在对应段内顺序分配，附带中英文描述。

### [E-04] 前端错误处理

API Client 按错误类型分流处理：
- `isUnauthorized` → 跳转登录页
- `isRateLimited` → 显示限流提示 + 倒计时
- `retryable` → 指数退避自动重试（最多 3 次）
- 其他 → Toast 提示用户

**禁止**裸 `catch(e) {}`，所有 catch 块必须有明确处理逻辑。

## 2.3 安全

### [S-01] ID 混淆

所有对外暴露的实体 ID 必须经 `SecureIdCodec` 编码。

- 编码格式：`[SIV:16][AES-CTR:16][HMAC-SHA256:10]` + Base62 ≈ 40 字符
- Controller 层接收外部 ID 后立即解码为内部 Long ID
- Service/DAO 层仅使用内部 Long ID
- 响应返回前由序列化器自动编码

### [S-02] 资源所有权校验

任何访问用户私有资源的接口必须校验所有权：

```java
@RequireOwnership(
    resourceIdParam = "fileId",
    ownerIdField = "userId",
    resourceClass = FilePO.class
)
```

管理员角色可绕过所有权检查（切面内置判断）。

### [S-03] 跨租户防护

- JWT 过滤器验证 Token 中 `tenantId` 与请求头 `X-Tenant-ID` 一致
- MyBatis Plus 拦截器自动注入 `tenant_id` WHERE 条件
- 跨租户操作必须用 `@TenantScope(ignoreIsolation=true)` 显式声明
- Dubbo 过滤器自动透传 `TenantContext`，下游服务无需手动设置

### [S-04] 敏感数据处理

- 密码：BCrypt 单向哈希，禁止明文存储或传输
- Token/密钥：仅在内存中使用，不落库、不落日志
- 文件内容：存储层 AES-GCM / ChaCha20-Poly1305 加密（可配置）
- 日志脱敏：`OperationLogAspect` 自动屏蔽 `password`、`token`、`secret` 等字段

## 2.4 数据库

### [D-01] Migration 规范

- 格式：`V{major}.{minor}.{patch}__{description}.sql`
- 新功能：递增 minor（V1.1.0 → V1.2.0）
- 小修复：递增 patch（V1.2.0 → V1.2.1）
- 创建前必须检查现有 migration 最大版本号，避免冲突
- Migration 内容必须包含清晰注释，说明变更目的

### [D-02] 表设计约定

- 主键：Snowflake ID（`BIGINT`），禁止自增（遗留表除外）
- 软删除：主实体必须有 `deleted` 字段（`TINYINT DEFAULT 0`）
- 多租户：必须有 `tenant_id` 字段
- 时间戳：`created_at` + `updated_at`（MyBatis Plus 自动填充）
- 索引：高频查询字段必须建索引，联合索引遵循最左前缀原则

### [D-03] 查询约定

- 默认过滤已软删除记录（MyBatis Plus `@TableLogic` 自动处理）
- 分页查询必须限制 `pageSize` 上限（默认 100）
- 禁止 `SELECT *`，仅查询需要的字段
- 批量操作使用 `IN` 查询时，限制列表长度（默认 1000）

## 2.5 容错与韧性

### [R-01] RemoteClient 模式

所有 Dubbo 远程调用必须通过 `RemoteClient` 包装类。

```
Controller → Service → RemoteClient → [CircuitBreaker + Retry] → Dubbo Provider
```

禁止在 Service 中直接 `@DubboReference` 调用远程服务。

### [R-02] Circuit Breaker 配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| slidingWindowSize | 50 | 滑动窗口大小 |
| failureRateThreshold | 50% | 失败率阈值 |
| waitDurationInOpenState | 30s | 开启状态等待时间 |
| slowCallDurationThreshold | 按服务配置 | blockchain 5s, storage 8s |

### [R-03] 重试策略

- 使用指数退避：初始 200ms，乘数 2
- 仅对 `RetryableException` 和 Dubbo 网络异常重试
- 最大重试次数：3（网络） / 5（Saga 补偿）
- 幂等性：重试的操作必须保证幂等（通过唯一键或状态检查）

## 2.6 测试

### [T-01] 测试命名与分类

| 后缀 | 类型 | 运行阶段 | 外部依赖 |
|------|------|----------|----------|
| `*Test.java` | 单元测试 | `mvn test`（Surefire） | 无 |
| `*IT.java` | 集成测试 | `mvn verify -Pit`（Failsafe） | Testcontainers |

### [T-02] 测试数据构建

使用 Builder 模式，所有 Builder 位于 `backend-service/src/test/.../builders/`：

```java
@ExtendWith(BuilderResetExtension.class)
class FileServiceTest {
    @Test
    void shouldUploadFile() {
        var file = FileTestBuilder.aFile();
        var account = AccountTestBuilder.anAccount(a -> a.setUsername("test"));
        // ...
    }
}
```

### [T-03] Mock 边界

| 层级 | 可 Mock | 禁止 Mock |
|------|---------|-----------|
| 单元测试 | Dubbo Provider、外部 HTTP API、Redis/MQ | 内部 Service、DAO |
| 集成测试 | 外部不可控服务（区块链节点） | 数据库、MQ（用 Testcontainers） |

### [T-04] 覆盖率要求

**后端**（JaCoCo，`mvn verify` 强制）：

| 模块 | 最低行覆盖率 |
|------|-------------|
| backend-web | 40% |
| backend-service | 45% |
| backend-common | 40% |

**前端**（Vitest，分路径阈值）：

| 路径 | Lines | Functions | Branches |
|------|-------|-----------|----------|
| `src/lib/utils/**` | 70% | 70% | 60% |
| `src/lib/api/endpoints/**` | 90% | 90% | 85% |
| `src/lib/stores/**` | 90% | 90% | 80% |
| `src/lib/services/**` | 90% | 90% | 85% |

## 2.7 前端组件与状态

### [F-01] Svelte 5 Runes

**强制使用**：`$state`、`$derived`、`$effect`
**禁止使用**：`$:`（reactive declaration）、`export let`（用 `$props()`）、`<slot>`（用 `{@render}`）

```svelte
<!-- 正确 -->
<script lang="ts">
  let { data, onSubmit }: Props = $props();
  let count = $state(0);
  const doubled = $derived(count * 2);
</script>

<!-- 错误 -->
<script lang="ts">
  export let data;  // 禁止
  $: doubled = count * 2;  // 禁止
</script>
```

### [F-02] $effect 使用约束

`$effect` 是副作用逃生舱，不是计算属性的替代品。

**允许的 $effect 场景**：
- 定时器管理（设置/清理 `setInterval`）
- DOM 操作（scrollTo、focus）
- 发起网络请求（需配合 cleanup）
- 日志/埋点

**禁止**：在 `$effect` 内更新 `$state`（会触发无限循环风险），优先用 `$derived`。

### [F-03] API 层三层结构

```
src/lib/api/
  client.ts          ← L1: HTTP 基础设施（重试、Token、错误分类）
  endpoints/         ← L2: 类型化业务 API（login、uploadFile）
    auth.ts
    files.ts
    ...
  types/
    generated.ts     ← L3: OpenAPI 自动生成的类型定义
    messages.ts      ← L3: 手工补充类型
```

新增 API 时：在 `endpoints/` 对应文件中封装，引用 `generated.ts` 的类型，通过 `client.ts` 发请求。

### [F-04] 组件分层

| 层级 | 目录 | 内容 | 示例 |
|------|------|------|------|
| UI 原语 | `components/ui/` | bits-ui 包装，无业务逻辑 | Button, Input, Card, Dialog |
| 业务组件 | `components/` | 组合 UI 原语 + 业务逻辑 | DownloadManager, FilePreview |
| 页面组件 | `routes/` | 路由级组件，组合业务组件 | files/+page.svelte |

### [F-05] 路由分组

| 分组 | 用途 | 认证要求 |
|------|------|----------|
| `(auth)/` | 登录、注册、密码重置 | 无 |
| `(app)/` | 用户功能页面 | 需登录 |
| `(admin)/` | 管理后台 | 需 admin/monitor 角色 |
| `share/` | 公开分享链接 | 无 |

## 2.8 Git 工作流

### [G-01] 分支策略

- **禁止**直接向 `main` 提交
- 分支命名：`feat/xxx`、`fix/xxx`、`refactor/xxx`、`docs/xxx`、`chore/xxx`
- 一个 PR 只解决一个关注点（一个功能、一个 Bug、一次重构）

### [G-02] Commit 规范

- 语言：英文
- 格式：`{prefix}: {description}`，单行 ~80 字符
- 前缀：`fix:` / `feat:` / `refactor:` / `opt:` / `docs:` / `test:` / `chore:` / `ci:`
- **禁止**：co-author 尾注、AI generated 标注

### [G-03] PR 合入条件

- 所有 CI gates 通过（backend-test, frontend-test, contract-consistency, build-check）
- Contract consistency 检查通过（`generated.ts` 无漂移）
- 代码变更附带相关文档更新

### [G-04] 推送前本地检查

推送前必须在本地验证，不得依赖 CI 反馈循环：

```bash
# 后端
mvn -f platform-backend/pom.xml test -pl backend-common,backend-service,backend-web -am

# 前端
cd platform-frontend
pnpm format:check && pnpm lint && pnpm check && pnpm test:coverage
pnpm types:gen:check  # 如果修改了 REST 接口
```

## 2.9 可观测性

### [O-01] 审计日志

所有 Controller 方法必须标注：

```java
@OperationLog(
    module = "file",
    operationType = OperationType.UPLOAD,
    description = "上传文件"
)
```

切面自动记录：操作人、时间、参数（脱敏）、结果、耗时、异常信息。

### [O-02] Tracing

- OTel Java Agent 自动注入 trace span，无需手动埋点
- 需要细粒度追踪时（如 Saga 各步骤），可手动添加 span
- MDC 自动包含 `traceId`、`userId`，日志自动关联

### [O-03] Metrics

- 业务指标通过 Micrometer 暴露，命名：`app.{module}.{metric_name}`
- 使用 `@Timed`、`Counter`、`Gauge` 等标准 API
- Prometheus 端点：`/actuator/prometheus`

## 2.10 多租户

### [M-01] 上下文生命周期

```
HTTP Request → JwtFilter 设置 TenantContext
    → Controller → Service → DAO（拦截器自动注入 tenant_id）
    → Dubbo 调用 → DubboFilter 透传 TenantContext → Provider
```

**禁止**手动构造 `TenantContext`，必须由 JWT 过滤器或 Dubbo 过滤器设置。

### [M-02] 跨租户操作

必须使用 `@TenantScope(ignoreIsolation=true)` 显式声明：

```java
@TenantScope(ignoreIsolation = true)
@Scheduled(cron = "0 0 2 * * ?")
public void dailyIntegrityCheck() {
    // 跨所有租户执行数据完整性检查
}
```

### [M-03] 存储隔离

S3 路径必须包含租户和用户前缀：`/{tenantId}/{userId}/{fileHash}`

## 2.11 缓存策略

### [CA-01] 多级缓存

```
查询 → Caffeine（本地，TTL 5min）→ Redis（分布式，TTL 30min）→ DB
```

- 写操作完成后，同时清除本地缓存和 Redis 缓存
- 缓存 Key 必须包含 `tenantId`，防止跨租户数据泄露

### [CA-02] 缓存一致性

- 写操作：先写 DB，再删缓存（Cache-Aside 模式）
- 读操作：缓存未命中时回源 DB，回填缓存
- 禁止在缓存中存储可变引用（必须深拷贝或使用不可变对象）

## 2.12 异步消息

### [AM-01] Outbox 模式

跨服务异步通信必须通过 Outbox 保证原子性：

```
本地事务：写业务表 + 写 Outbox 表（同一事务）
定时任务：轮询 Outbox → 发送 RabbitMQ → 标记已发送
```

### [AM-02] 消费者幂等性

MQ Consumer 必须保证幂等：
- 通过 `messageId` 做去重检查
- 业务操作使用数据库唯一键约束作为最终防线
- Dead Letter Queue 接收多次重试失败的消息

## 2.13 配置管理

### [CF-01] Profile 策略

| Profile | 用途 | 配置来源 |
|---------|------|----------|
| `local` | 本地开发 | `application-local.yml` + `.env` |
| `dev` | 开发环境 | Nacos 配置中心 |
| `prod` | 生产环境 | Nacos 配置中心 + 环境变量 |

### [CF-02] 敏感配置

- 敏感值（JWT_KEY, S3_SECRET, DB_PASSWORD）通过环境变量注入
- `.env.example` 记录所有需要的环境变量及说明
- **禁止**在代码或配置文件中硬编码敏感值

### [CF-03] 动态配置

Nacos 动态刷新的配置项使用 `@RefreshScope` 或 `@NacosValue(autoRefreshed = true)`。
变更动态配置时注意线程安全（配置值可能在请求处理中途变化）。

## 2.14 性能

### [P-01] Virtual Threads

项目全局启用 Virtual Threads（`spring.threads.virtual.enabled=true`）。

- I/O 密集操作（网络请求、数据库查询）自动受益
- **禁止**在 Virtual Thread 中使用 `synchronized`（改用 `ReentrantLock`）
- **禁止**长时间持有 pinned carrier thread 的操作

### [P-02] 文件传输优化

- 上传：动态 chunk 大小（2MB-50MB），根据文件大小自动调整
- 下载：StreamSaver.js 流式下载，避免内存中缓存完整文件
- 批量操作：流式处理，禁止将所有数据加载到内存

### [P-03] 查询优化

- 列表查询必须分页（`pageSize` 上限 100）
- 大表查询必须走索引（`EXPLAIN` 验证）
- N+1 查询：使用 `IN` 批量查询替代循环单查

## 2.15 文档

### [DOC-01] 代码与文档同步

代码变更后必须同步更新相关文档：
- REST 接口变更 → 更新 OpenAPI 注解 + 重新生成 `generated.ts`
- 架构变更 → 更新 `docs/` 下相关文档
- 配置变更 → 更新 `.env.example` 说明

### [DOC-02] API 文档

- 使用 Swagger/OpenAPI 注解自动生成
- Controller 方法必须有 `@Operation(summary)` 描述
- DTO 字段必须有 `@Schema(description)` 注解

---

# L3 决策记录

每条 L2 规则的设计背景和常见错误。

## 核心公约决策

### [C-01] 为什么是"边界验证，内部信任"？

**Why**：
过度防御式编程（每层都做校验）会导致：(1) 校验逻辑分散，修改一个规则要改多处；(2) 性能损耗在高频内部调用路径上累积；(3) 校验逻辑与业务逻辑耦合。Google SWE Book 和 Spring Boot 社区共识是在系统边界做一次彻底校验，内部调用链信任已校验数据。

**Anti-pattern**：
```java
// ❌ Service 层重复校验 Controller 已验证的参数
public void uploadFile(FileUploadDTO dto) {
    if (dto.getFileName() == null) throw new GeneralException(PARAM_ERROR);  // 多余
    if (dto.getFileSize() <= 0) throw new GeneralException(PARAM_ERROR);     // 多余
    // Controller 的 @Valid 已经做了这些检查
}

// ✅ Service 信任 Controller 的校验结果，直接处理业务
public void uploadFile(FileUploadDTO dto) {
    var session = createUploadSession(dto);
    sagaOrchestrator.execute(session);
}
```

**参考**：Google "Software Engineering at Google" Ch.8, Spring Validation Best Practices

### [C-02] 为什么"契约先行"？

**Why**：
前后端类型漂移是微服务前后端分离架构中最常见的集成 Bug 来源。手动同步类型定义容易遗漏字段、类型错配、枚举值不一致。项目通过 OpenAPI spec 自动生成 TypeScript 类型，配合 CI contract-consistency 检查，将类型漂移从运行时 Bug 降级为编译期错误。

**Anti-pattern**：
```typescript
// ❌ 手写类型定义，与后端 DTO 不同步
interface FileVO {
  fileName: string;
  fileSize: number;  // 后端已改为 fileSizeBytes，但前端未更新
}

// ✅ 使用 generated.ts 中自动生成的类型
import type { components } from '$api/types/generated';
type FileVO = components['schemas']['FileVO'];
```

### [C-03] 为什么"写操作必须可补偿"？

**Why**：
分布式系统中，跨服务写操作无法使用传统 ACID 事务。项目的文件上传存证涉及 S3 存储 + 区块链写入 + 数据库记录三个服务，任何一步失败都需要回滚前序步骤。Saga 模式将大事务拆分为本地事务序列 + 补偿事务，`FileSagaOrchestrator` 实现了自动补偿调度（失败时逆序执行 `compensate()`）。Outbox 模式确保本地事务和消息发送的原子性。

**Anti-pattern**：
```java
// ❌ 跨服务调用没有补偿路径
public void attestFile(Long fileId) {
    storageClient.upload(file);       // 成功
    blockchainClient.attest(hash);    // 失败！但 S3 上已有文件，无人清理
    fileMapper.updateStatus(fileId, ATTESTED);
}

// ✅ Saga 模式，每步都有补偿
sagaOrchestrator.newSaga()
    .step(uploadStep)        // execute: upload to S3,   compensate: delete from S3
    .step(attestStep)        // execute: write to chain,  compensate: mark invalid
    .step(recordStep)        // execute: update DB,       compensate: revert status
    .execute();
```

**参考**：Chris Richardson "Microservices Patterns" Ch.4, microservices.io/patterns/data/saga

### [C-04] 为什么"测试隔离"？

**Why**：
依赖共享环境的测试会产生"在我机器上能跑"的问题，且无法并行执行。Mock 内部 Service 会掩盖集成问题（如参数类型变更、行为变更），让测试通过但生产环境失败。Testcontainers 提供一次性容器环境，每次测试运行都从干净状态开始。Builder 模式 + `BuilderResetExtension` 确保测试数据不会跨用例泄露。

**Anti-pattern**：
```java
// ❌ Mock 内部 Service，掩盖集成问题
@MockBean
private FileQueryService fileQueryService;  // 这是内部 Service，不应 Mock

when(fileQueryService.getFile(anyLong())).thenReturn(fakeFile);
// 如果 FileQueryService.getFile() 的签名改了，这个测试仍然通过

// ✅ 只 Mock 外部边界
@MockBean
private FileRemoteClient fileRemoteClient;  // Dubbo 远程服务的包装，可以 Mock

when(fileRemoteClient.uploadToS3(any())).thenReturn(uploadResult);
```

### [C-05] 为什么"安全默认"？

**Why**：
安全机制如果是可选的（"记得加上 ID 混淆"、"别忘了校验所有权"），就一定会有人忘记。项目通过以下机制将安全约束变为默认行为：(1) `SecureIdCodec` 在序列化层自动编解码 ID；(2) `@RequireOwnership` 声明式所有权校验；(3) MyBatis Plus 拦截器自动注入租户条件。开发者不需要"记住"安全规则，而是需要"显式声明"想要跳过（如 `@TenantScope(ignoreIsolation=true)`）。

**Anti-pattern**：
```java
// ❌ 手动校验所有权，容易遗漏
@GetMapping("/{fileId}")
public Result<FileVO> getFile(@PathVariable Long fileId) {
    var file = fileService.getById(fileId);
    if (!file.getUserId().equals(currentUserId)) {  // 每个接口都要写，早晚漏一个
        throw new GeneralException(PERMISSION_DENIED);
    }
    return Result.success(file);
}

// ✅ 声明式所有权校验
@RequireOwnership(resourceIdParam = "fileId", ownerIdField = "userId", resourceClass = FilePO.class)
@GetMapping("/{fileId}")
public Result<FileVO> getFile(@PathVariable Long fileId) {
    return Result.success(fileService.getById(fileId));  // AOP 已完成校验
}
```

### [C-06] 为什么"可观测优先"？

**Why**：
没有审计日志的系统在出问题时只能"猜"发生了什么。`@OperationLog` 自动记录每个操作的完整上下文（谁、什么时间、做了什么、参数是什么、结果如何、耗时多久），使问题排查从"猜测"变为"查询"。ResultEnum 结构化编码让错误分类和告警规则可以基于编码段自动配置，而非依赖模糊的错误消息匹配。

**Anti-pattern**：
```java
// ❌ 没有审计追踪
@PostMapping("/delete/{fileId}")
public Result<Void> deleteFile(@PathVariable Long fileId) {
    fileService.delete(fileId);  // 谁删的？什么时候？为什么？无从查证
    return Result.success();
}

// ✅ 完整审计
@OperationLog(module = "file", operationType = OperationType.DELETE, description = "删除文件")
@RequireOwnership(resourceIdParam = "fileId", ownerIdField = "userId", resourceClass = FilePO.class)
@PostMapping("/delete/{fileId}")
public Result<Void> deleteFile(@PathVariable Long fileId) {
    fileService.delete(fileId);
    return Result.success();
}
```

## 架构决策

### [A-02] 为什么 Command/Query 分离？

**Why**：
文件模块读写比约 10:1。读操作需要多级缓存（Caffeine + Redis）和 Virtual Thread 并发查询，写操作需要 Saga 事务保证。如果混合在一个 Service 中，缓存失效逻辑会污染写路径，事务注解会影响读性能，两种关注点互相干扰。分离后，`FileQueryService` 可以独立优化缓存策略和并发度，`FileService` 可以专注事务正确性。

**Anti-pattern**：
```java
// ❌ 读写混合在一个 Service
public class FileService {
    @Cacheable("files")
    public FileVO getFileDetail(Long id) { ... }  // 读，需要缓存

    @Transactional
    @CacheEvict("files")
    public void deleteFile(Long id) { ... }  // 写，需要事务+缓存清除
    // 缓存注解和事务注解交织，维护困难
}

// ✅ 读写分离
public class FileQueryService {  // 只读，可水平扩展
    @Cacheable("files")
    public FileVO getFileDetail(Long id) { ... }
}

public class FileService {  // 只写，事务保证
    @Transactional
    public void deleteFile(Long id) {
        // 业务操作...
        cacheManager.evict("files", id);  // 显式清除缓存
    }
}
```

**参考**：Microsoft CQRS Pattern, Martin Fowler "CQRS"

### [A-03] 为什么强制 RemoteClient 包装？

**Why**：
直接在 Service 中 `@DubboReference` 调用远程服务，会导致 Circuit Breaker 和 Retry 配置分散在每个调用点。`RemoteClient` 作为统一的远程调用入口，集中管理：(1) Resilience4j 断路器配置；(2) 超时和重试策略；(3) 错误转换（Dubbo 异常 → `GeneralException`）；(4) 调用指标采集。修改容错策略时只需改一处。

**Anti-pattern**：
```java
// ❌ 直接注入 Dubbo Reference，容错逻辑分散
@DubboReference
private DistributedStorageService storageService;

public void upload(byte[] data) {
    try {
        storageService.upload(data);  // 没有断路器、没有重试、没有超时
    } catch (Exception e) {
        // 每个调用点都要写错误处理
    }
}

// ✅ 通过 RemoteClient 统一管理
@Resource
private FileRemoteClient fileRemoteClient;

public void upload(byte[] data) {
    fileRemoteClient.upload(data);  // 断路器+重试+超时+错误转换 已内置
}
```

### [F-02] 为什么限制 $effect？

**Why**：
`$effect` 在 Svelte 5 中是副作用的执行器，每当其依赖的响应式值变化时自动重新执行。如果在 `$effect` 内更新 `$state`，更新会触发依赖该 state 的其他 effect 或 derived，形成连锁反应甚至无限循环。Svelte 官方文档明确将 `$effect` 定位为"逃生舱"（escape hatch），推荐优先使用 `$derived` 做派生计算。

**Anti-pattern**：
```svelte
<script lang="ts">
  let items = $state<Item[]>([]);
  let total = $state(0);

  // ❌ 在 effect 中更新 state（应该用 $derived）
  $effect(() => {
    total = items.reduce((sum, i) => sum + i.price, 0);
  });

  // ✅ 用 $derived 替代
  const total = $derived(items.reduce((sum, i) => sum + i.price, 0));
</script>
```

**参考**：svelte.dev/docs/svelte/$effect, Svelte 5 Best Practices

### [E-02] 为什么限制只用两种异常类？

**Why**：
自定义异常类泛滥是 Java 项目常见的技术债。每个模块各自定义 `FileNotFoundException`、`UserAlreadyExistsException`、`QuotaExceededException`……最终导致全局异常处理器中出现数十个 catch 分支。`ResultEnum` 枚举集中管理所有错误码和消息，`GeneralException` + `RetryableException` 两个类覆盖"业务失败"和"瞬时故障"两大类场景。新增错误场景只需添加枚举值，不需要新建类。

**Anti-pattern**：
```java
// ❌ 每种错误一个异常类
public class FileNotFoundException extends RuntimeException { ... }
public class QuotaExceededException extends RuntimeException { ... }
public class ShareExpiredException extends RuntimeException { ... }
// 全局 Handler 需要为每个异常写一个 @ExceptionHandler

// ✅ 统一用 ResultEnum + GeneralException
throw new GeneralException(ResultEnum.FILE_NOT_FOUND);
throw new GeneralException(ResultEnum.QUOTA_EXCEEDED);
throw new GeneralException(ResultEnum.SHARE_EXPIRED);
// 全局 Handler 只需一个 @ExceptionHandler(GeneralException.class)
```

### [CA-01] 为什么采用多级缓存？

**Why**：
单级 Redis 缓存在高并发读场景下，每次缓存查询都走网络 I/O（~1ms RTT），QPS 上万时累积延迟显著。Caffeine 本地缓存作为 L1，命中时零网络开销（~100ns）。L1 TTL 短（5min）容忍一定程度的数据不一致，L2 Redis TTL 长（30min）作为兜底。写操作同时清除两级缓存保证最终一致。缓存 Key 必须包含 `tenantId`，否则租户 A 的缓存可能被租户 B 读到。

**Anti-pattern**：
```java
// ❌ 缓存 Key 不含 tenantId
@Cacheable(key = "'file:' + #fileId")  // 不同租户的同 fileId 会命中同一缓存！

// ✅ 缓存 Key 包含 tenantId
@Cacheable(key = "'file:' + T(cn.flying.common.tenant.TenantContext).getCurrentTenantId() + ':' + #fileId")
```

---

# 附录

## A. 规则速查表

| 层级 | ID | 一句话描述 |
|------|-----|-----------|
| L1 | C-01 | 边界验证，内部信任 |
| L1 | C-02 | 契约先行，CI 检查漂移 |
| L1 | C-03 | 写操作必须可补偿 |
| L1 | C-04 | 测试隔离，零外部依赖 |
| L1 | C-05 | 安全默认，显式豁免 |
| L1 | C-06 | 可观测优先，结构化错误 |
| L2 | A-01~04 | 架构分层与状态管理 |
| L2 | E-01~04 | 错误处理与异常约束 |
| L2 | S-01~04 | 安全：ID 混淆、所有权、租户、脱敏 |
| L2 | D-01~03 | 数据库：Migration、表设计、查询 |
| L2 | R-01~03 | 容错：RemoteClient、断路器、重试 |
| L2 | T-01~04 | 测试：分类、Builder、Mock 边界、覆盖率 |
| L2 | F-01~05 | 前端：Runes、Store、API 层、组件、路由 |
| L2 | G-01~04 | Git：分支、Commit、PR、本地检查 |
| L2 | O-01~03 | 可观测性：审计、Tracing、Metrics |
| L2 | M-01~03 | 多租户：上下文、跨租户、存储隔离 |
| L2 | CA-01~02 | 缓存：多级缓存、一致性 |
| L2 | AM-01~02 | 异步消息：Outbox、幂等 |
| L2 | CF-01~03 | 配置：Profile、敏感值、动态刷新 |
| L2 | P-01~03 | 性能：Virtual Threads、传输优化、查询优化 |
| L2 | DOC-01~02 | 文档：同步更新、API 注解 |

## B. 参考来源

- Google "Software Engineering at Google" (O'Reilly, 2020) — 代码规范自动化、测试策略
- Chris Richardson "Microservices Patterns" — Saga, Outbox, CQRS, Circuit Breaker
- microservices.io — 微服务模式目录
- Spring Boot CONTRIBUTING.adoc — 构造器注入、Profile 隔离
- svelte.dev/docs/svelte/best-practices — Runes 规范、$effect 约束
- Uber Go Style Guide — 接口设计、错误处理一致性
- Addy Osmani "My LLM coding workflow going into 2026" — AI 辅助开发规范
- arXiv:2512.18925 "Beyond the Prompt" — 项目规则文件实证研究
- OWASP Top 10 — SQL 注入、身份认证、访问控制
