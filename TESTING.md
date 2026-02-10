# 测试框架说明（最少代码 + 关键覆盖）

本项目采用"单元测试优先 + 少量高价值集成测试"的策略，目标是在 CI 中尽早发现回归，同时保持本地开发的执行成本足够低。

## 当前测试覆盖（120 test files: 82 backend + 6 storage + 32 frontend）

### 后端单元测试（backend-common，10 个测试类）

| 测试类 | 覆盖范围 |
|--------|----------|
| SecureIdCodecTest | ID 加密/解密编解码 |
| Base62Test | Base62 编码算法 |
| JwtUtilsTest | JWT 生成、解析、过期处理 |
| UidEncoderTest | UID 编码器 |
| CommonUtilsTest | 通用工具方法 |
| TenantContextTest | 租户上下文 ThreadLocal 管理 |
| SensitiveDataMaskerTest | 敏感数据脱敏 |
| DistributedRateLimiterTest | 分布式限流算法 |
| DistributedRateLimiterPerformanceTest | 限流性能基准 |
| DistributedLockAspectTest | 分布式锁 AOP 切面 |

### 后端单元测试（backend-service，26 个测试类）

| 测试类 | 覆盖范围 |
|--------|----------|
| FileUploadServiceTest | 分块上传、暂停/恢复、状态管理、所有权验证 |
| FileUploadServiceConcurrencyTest | 上传并发安全 |
| FileServiceTest | 分享生成（公开/私密）、取消/更新分享、访问计数 |
| FileServiceConcurrencyTest | 文件操作并发安全 |
| FileQueryServiceTest | 文件访问控制、好友分享权限、管理员权限、JSON 验证 |
| FileQueryServiceEdgeCaseTest | 文件查询边界场景 |
| FileAdminServiceImplTest | 管理员文件管理、溯源链 |
| FriendServiceTest | 好友请求生命周期、接受/拒绝/取消、解除好友 |
| FriendFileShareServiceImplTest | 好友文件分享业务逻辑 |
| TicketServiceTest | 工单创建、回复、状态转换、分配 |
| AccountServiceImplTest | 账号管理业务逻辑 |
| AnnouncementServiceImplTest | 公告 CRUD、发布/撤回 |
| ConversationServiceImplTest | 会话管理 |
| MessageServiceImplTest | 私信发送、已读标记 |
| ImageServiceImplTest | 头像/图片上传处理 |
| PermissionServiceImplTest | 权限分配与校验 |
| ShareAuditServiceImplTest | 分享审计日志 |
| SysAuditServiceImplTest | 系统审计服务 |
| SysOperationLogServiceImplTest | 操作日志查询与导出 |
| LoginSecurityServiceTest | 登录安全（锁定、限流） |
| OutboxServiceTest | Outbox 消息发布与清理 |
| FileSagaOrchestratorTest | Saga 状态机、补偿逻辑 |
| SagaCompensationHelperTest | Saga 补偿辅助逻辑 |
| ChunkEncryptionStrategyTest | AES-GCM/ChaCha20 加密（参数化） |
| ChunkDecryptionServiceTest | 分片解密服务 |
| SseEmitterManagerTest | SSE 连接管理、并发 |

### 后端测试（backend-web，46 个测试文件）

#### 控制器集成测试

| 测试类 | 覆盖范围 |
|--------|----------|
| AuthorizeControllerIntegrationTest | 注册、验证码、密码重置 |
| AccountControllerIntegrationTest | 用户信息、邮箱/密码修改 |
| FileControllerIntegrationTest | 文件列表、删除、下载 |
| FileRestControllerTest | REST 风格文件端点 |
| FileUploadControllerIntegrationTest | 上传会话生命周期 |
| UploadSessionControllerTest | 上传会话 REST 端点 |
| ShareControllerIntegrationTest | 分享创建、获取、取消 |
| ShareRestControllerTest | REST 风格分享端点 |
| FileShareE2ETest | 文件分享端到端流程 |
| FileUploadE2ETest | 文件上传端到端流程 |
| FriendControllerIntegrationTest | 好友请求、列表、删除 |
| FriendFileShareControllerIntegrationTest | 好友文件分享端点 |
| TicketControllerIntegrationTest | 工单 CRUD、回复、关闭 |
| MessageControllerIntegrationTest | 私信发送、未读计数 |
| ConversationControllerIntegrationTest | 会话列表、详情、已读 |
| AnnouncementControllerIntegrationTest | 公告列表、管理员操作 |
| ImageControllerIntegrationTest | 图片上传/下载 |
| PermissionControllerIntegrationTest | 权限管理端点 |
| RolePermissionControllerTest | 角色权限分配 |
| SysAuditControllerIntegrationTest | 审计日志查询 |
| SysAuditControllerTest | 审计控制器单元测试 |
| SystemControllerIntegrationTest | 系统监控端点 |
| SseControllerIntegrationTest | SSE 连接/断开 |
| FileAdminControllerIntegrationTest | 管理员文件管理端点 |
| AdminAndTransactionControllerTest | 管理员与交易端点 |
| ControllerCoverageBoostTest | 控制器覆盖补充 |

#### 过滤器与安全测试

| 测试类 | 覆盖范围 |
|--------|----------|
| JwtAuthenticationFilterTest | JWT 验证、安全上下文 |
| TenantFilterTest | 租户解析、ThreadLocal 清理 |
| CorsFilterTest | CORS 跨域策略 |
| IdSecurityFilterTest | ID 混淆安全过滤 |
| RequestLogFilterTest | 请求日志记录 |
| FlowLimitingFilterTest | 接口限流 |
| GlobalExceptionHandlerTest | 全局异常处理 |
| CustomMethodSecurityExpressionRootTest | 自定义权限表达式 |

#### 其他后端测试

| 测试类 | 覆盖范围 |
|--------|----------|
| DatabaseIT | Flyway 迁移、MyBatis-Plus、SecureId AOP |
| AccountMapperIT | Account Mapper 集成 |
| FileMapperIT | File Mapper 集成 |
| BaseMapperIT | Mapper 基础设施 |
| BaseDataTest | DTO/VO 数据结构验证 |
| RecordJacksonCompatibilityTest | Record 类 Jackson 序列化兼容性 |
| SystemMonitorServiceImplTest | 系统监控服务单元测试 |
| RabbitMQHealthIndicatorTest | RabbitMQ 健康检查 |
| FileStorageEventListenerTest | 文件存储事件监听 |
| LoadTestSeedRunnerTest | 压测数据 Seed 初始化 |
| BaseIntegrationTest | 集成测试基类 |
| BaseControllerIntegrationTest | 控制器集成测试基类 |

### 前端测试（platform-frontend，32 个测试文件）

#### API 层测试

| 测试文件 | 覆盖范围 |
|----------|----------|
| client.test.ts | HTTP 客户端、Token 管理、ApiError |
| auth.test.ts | 登录、注册、密码重置 API |
| files.test.ts | 文件列表、删除、下载 API |
| messages.test.ts | 私信 API |
| friends.test.ts | 好友 API |
| tickets.test.ts | 工单 API |
| upload.test.ts | 分片上传 API |
| system.test.ts | 系统监控 API |
| admin.test.ts | 管理员 API |
| images.test.ts | 图片上传/下载 API |
| sse.test.ts | SSE 连接 API |

#### Store 层测试

| 测试文件 | 覆盖范围 |
|----------|----------|
| auth.svelte.test.ts | 认证状态管理 |
| sse.svelte.test.ts | SSE 连接状态 |
| upload.svelte.test.ts | 上传进度状态 |
| download.svelte.test.ts | 下载进度状态 |
| badges.svelte.test.ts | 未读徽章状态 |
| notifications.svelte.test.ts | 通知管理 |
| sse-leader.svelte.test.ts | SSE 多标签页 Leader Election |

#### 工具与服务测试

| 测试文件 | 覆盖范围 |
|----------|----------|
| crypto.test.ts | 文件解密、加密头解析 |
| validation.test.ts | 输入验证规则 |
| format.test.ts | 格式化工具 |
| storage.test.ts | 本地存储封装 |
| downloadStorage.test.ts | 下载存储管理 |
| chunkDownloader.test.ts | 分块下载器 |
| streamingDownloader.test.ts | 流式下载器 |
| avatar.test.ts | 头像 URL 处理 |
| fileSize.test.ts | 文件大小格式化 |
| utils.test.ts | 通用工具函数 |
| navigation.test.ts | 路由导航配置 |
| sseMessageHandler.test.ts | SSE 消息处理器 |

#### 路由与集成测试

| 测试文件 | 覆盖范围 |
|----------|----------|
| route-loaders.test.ts | 路由数据加载器 |
| app-layout-load.test.ts | 应用布局加载逻辑 |

### 测试工具类

| 工具类 | 用途 |
|--------|------|
| FileTestBuilder | File 实体测试数据构建器 |
| FileUploadStateTestBuilder | 上传状态测试数据构建器 |
| AccountTestBuilder | Account 实体测试数据构建器 |
| FriendRequestTestBuilder | 好友请求测试数据构建器 |

## 1. 测试分层约定

- **单元测试（Unit）**：命名 `*Test.java`，由 Maven Surefire 在 `test` 阶段执行
  - 特点：不依赖外部系统（DB/Redis/MQ/Nacos/Dubbo），执行快、可并行、定位问题清晰
  - 建议：单测里尽量避免打印异常堆栈（用 mock + 断言覆盖失败分支即可）
- **集成测试（Integration）**：命名 `*IT.java`，由 Maven Failsafe 在 `verify` 阶段执行（需要启用 `it` Profile）
  - 特点：使用 Testcontainers 启动依赖（如 MySQL/Redis/RabbitMQ），覆盖 Flyway、MyBatis、AOP、关键基础设施联动

## 2. 后端（platform-backend）运行方式

> 说明：`backend-service` 依赖 `platform-api`，本地跑后端测试前需要先安装 `platform-api` 到本地仓库。

### 2.1 安装 platform-api（一次性 / 依赖更新时）

```bash
mvn -f platform-api/pom.xml clean install -DskipTests
```

### 2.2 仅跑单元测试（不需要 Docker）

```bash
mvn -f platform-backend/pom.xml test -pl backend-common,backend-service,backend-web -am
```

### 2.3 跑集成测试（需要 Docker，使用 Testcontainers）

```bash
mvn -f platform-backend/pom.xml verify -pl backend-service,backend-web -am -Pit
```

> 说明：本地没有 Docker 时，集成测试会自动跳过（不会导致构建失败）。

## 3. 前端（platform-frontend）运行方式

```bash
cd platform-frontend
pnpm test:coverage
```

## 4. 其他服务测试（可选）

### 4.1 区块链服务（platform-fisco）

```bash
mvn -f platform-fisco/pom.xml test
```

### 4.2 存储服务（platform-storage）

```bash
mvn -f platform-storage/pom.xml test
```

#### 存储服务测试覆盖

| 测试类 | 类型 | 覆盖范围 |
|--------|------|----------|
| ConsistentHashRingTest | 单元 | 一致性哈希算法、虚拟节点分布 |
| FaultDomainManagerTest | 单元 | 故障域管理、节点状态转换 |
| StandbyPoolManagerTest | 单元 | 备用节点池、自动提升逻辑 |
| RebalanceServiceTest | 单元 | 数据再平衡、限流控制 |
| ConsistencyRepairServiceTest | 单元 | 一致性修复、统计与调度条件 |
| DistributedStorageServiceImplTest | 单元 | 存储/查询/删除/健康检查、再平衡触发 |

#### Mock 策略

存储服务测试使用以下 Mock 策略：
- **S3 客户端**: 使用 Mockito Mock `S3Client`
- **Redis**: 使用内嵌 Redis 或 Mock `RedisTemplate`
- **事件发布**: Mock `ApplicationEventPublisher`

## 5. CI 执行策略

- GitHub Actions 会执行：
  - 后端：`mvn -f platform-backend/pom.xml clean verify -pl backend-common,backend-service,backend-web -am -Pit`
  - FISCO：`mvn -f platform-fisco/pom.xml test`
  - Storage：`mvn -f platform-storage/pom.xml test`
  - 前端：`pnpm test:coverage`
- 后端覆盖率报告由 JaCoCo 生成，CI 中会上传 `jacoco.xml`（见 `.github/workflows/test.yml`）。

## 6. 新增测试的建议（保持"最少代码"）

- 优先给 **纯业务逻辑** 写单元测试：无 Spring 上下文、无外部依赖、直接 new / Mockito 即可
- 只为最关键链路写少量集成测试：数据库迁移 + ORM 映射 + 关键 AOP/拦截器
- 单测不要依赖执行顺序；集成测试也尽量使用随机/唯一数据，避免与其他用例耦合
