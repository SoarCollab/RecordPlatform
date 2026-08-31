# 测试框架说明（最少代码 + 关键覆盖）

本项目采用"单元测试优先 + 少量高价值集成测试"的策略，目标是在 CI 中尽早发现回归，同时保持本地开发的执行成本足够低。

## 当前测试文件快照（328 files）

> 2026-08-31 按 canonical source tree 中的 `*Test.java` / `*IT.java` / `*.test.ts` / `*.spec.ts` / `test_*.py` / `*_test.py` 统计。该数字是文件快照，不等同 test case 数；以下长表只说明代表性覆盖。`tools/docs/check_consistency.py --check-evidence` 会从 exact tree 重新计算并核对本表。

| Component | Test files |
| --- | ---: |
| `platform-backend/backend-common` | 13 |
| `platform-backend/backend-api` | 1 |
| `platform-backend/backend-service` | 96 |
| `platform-backend/backend-web` | 106 |
| `platform-backend` | 216 |
| `platform-storage` | 28 |
| `platform-frontend` | 41 |
| `platform-verifier` | 15 |
| `platform-fisco` | 14 |
| `platform-api` | 3 |
| `tools/ci` | 6 |
| `tools/contracts` | 4 |
| `tools/docs` | 1 |
| `tools` | 11 |
| `total` | 328 |

### 后端单元测试（backend-common，代表性测试类）

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

### 后端单元测试（backend-service，代表性测试类）

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
| DownloadBatchMetricsServiceImplTest | 批量下载指标上报与聚合 |
| QuotaServiceImplTest | 配额查询与执行逻辑 |
| QuotaRolloutAuditServiceImplTest | 配额灰度审计服务 |
| IntegrityCheckServiceTest | 存储完整性校验（S3 存在性 + DB-链上哈希一致性） |
| ChunkManifestCanonicalizerTest | canonical manifest、连续索引、hash 与版本化加密约束 |
| ManifestBackfillRunServiceTest | SCAN/DRY_RUN/APPLY 快照、状态机、暂停/恢复/重试 |
| ManifestEvidenceResolverTest | ALREADY_MANIFEST/BACKFILLABLE/REUPLOAD_REQUIRED 等证据分类 |
| ManifestGovernanceStatusServiceTest | 缺失 manifest 的机器可读下载治理合同 |
| ManifestReferenceLifecycleTest | reference census、mark/grace/delete 的开关与 fencing 生命周期 |

### 后端测试（backend-web，代表性测试文件）

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
| QuotaAdminControllerTest | 配额管理员端点 |
| QuotaControllerTest | 配额查询端点 |
| IntegrityAlertControllerIT | 完整性告警管理端点（列表、触发、确认、解决） |

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
| CustomMethodSecurityExpressionHandlerTest | 自定义方法安全表达式处理器 |
| ValidationControllerTest | 参数校验控制器端点 |

#### 其他后端测试

| 测试类 | 覆盖范围 |
|--------|----------|
| DatabaseIT | Flyway 迁移、MyBatis-Plus、SecureId AOP |
| AccountMapperIT | Account Mapper 集成 |
| FileMapperIT | File Mapper 集成 |
| BaseMapperIT | Mapper 基础设施 |
| BaseDataTest | DTO/VO 数据结构验证 |
| RecordJacksonSerializationTest | Record 类 Jackson 序列化验证 |
| SystemMonitorServiceImplTest | 系统监控服务单元测试 |
| RabbitMQHealthIndicatorTest | RabbitMQ 健康检查 |
| FileStorageEventListenerTest | 文件存储事件监听 |
| LoadTestSeedRunnerTest | 压测数据 Seed 初始化 |
| BaseIntegrationTest | 集成测试基类 |
| BaseControllerIntegrationTest | 控制器集成测试基类 |
| OpenApiContractExportTest | OpenAPI 契约导出与稳定性校验 |

### 前端测试（platform-frontend，代表性测试文件）

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
| FileShareTestBuilder | 文件分享测试数据构建器 |
| FriendFileShareTestBuilder | 好友文件分享测试数据构建器 |
| TicketTestBuilder | 工单测试数据构建器 |

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

#### FISCO 服务测试覆盖

| 测试类 | 类型 | 覆盖范围 |
|--------|------|----------|
| FiscoTest | 单元 | FISCO SDK 连接与基础操作 |
| BlockChainServiceImplTest | 单元 | 区块链服务接口实现 |

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

#### 直传真实故障矩阵与负载 smoke

PR 的存储事实源不是外部部署环境，而是固定版本 Testcontainers：三套数据面独立的 MinIO、Redis/Redisson 与两条独立 Toxiproxy 路径。执行命令：

```bash
mvn -f platform-storage/pom.xml clean verify -Pit
```

CI 必须精确校验以下 Failsafe XML，`skipped/failures/errors` 均为 `0`：

| 测试类 | tests | 责任 |
|--------|------:|------|
| `DirectUploadPromotionMinioIT` | 8 | copy/stream/CORS、degraded repair、dead letter、complete/abort/cleanup、receipt restart |
| `DirectUploadRedisLifecycleIT` | 7 | 多客户端 receipt、fence、tombstone 生命周期 |
| `DirectUploadFaultMatrixMinioIT` | 9 | F01-F06、F12-F14 的真实 provider/network 故障 |
| `DirectUploadLoadSmokeIT` | 1 | 4 并发、8 次迭代、资源与 lifecycle 报告、零残留 |

F07、F09-F11 复用既有真实 MinIO/Redis 测试和确定性 service tests，不重复创建慢测试；F08 在矩阵中通过真实 repair callback + Toxiproxy timeout 断言 `RETRYABLE_DEFERRED`。`DirectUploadFaultMatrixMinioIT` 类注释维护完整 F01-F14 traceability。

`DirectUploadLoadSmokeIT` 产物：

- `platform-storage/target/direct-upload-load-smoke/report.json`
- `platform-storage/target/direct-upload-load-smoke/report.md`

P2-4 在 exact `main` 的 [Test Suite run 30209115456](https://github.com/SoarCollab/RecordPlatform/actions/runs/30209115456) 留下以下可复核快照：Linux amd64、Java 21.0.11、4 processors，环境 fingerprint `5b9cece769dd3a52cd34a0af45d9342573ad855edb98d048547b732d8cdeab6b`，4 并发完成 8/8 次迭代；256 KiB 负载 wall time 414 ms、p99 291 ms、吞吐 `5,061,808.93 bytes/s`（约 4.83 MiB/s），heap peak delta `37,748,736 bytes`（36 MiB）、direct-buffer peak delta `2,686,976 bytes`、thread delta 23；receipt/tombstone 从 8/8 回落到 0/0，最终对象与 Redis lifecycle 残留为零。[retained report](docs/public/evidence/direct-upload-load-smoke-30209115456.json) 的 source SHA-256 为 `4ecc8b77b7a39f4ccf6c2809dc52642d252bf8d123845823736a8f67476aab39`。该结果是对应 fingerprint 的 smoke 证据，不是生产 SLA，也不能与不同环境/负载直接比较。

PR 硬门禁是零失败、零对象/Redis 残留、有界 deadline 与资源预算；吞吐和紧时延只记录，不作为共享 runner 的生产 SLA。96 MiB 对象 / 80 MiB heap 的对象级边界仍由以下独立 fork 证明：

```bash
mvn -f platform-storage/pom.xml verify -Pdirect-upload-constrained-heap
```

本机无 Docker 时，真实容器命令会因环境不可用而阻塞或失败，不能表述为真实 IT 已通过；GitHub Actions 的精确 XML 断言会阻止缺报告或 skip 被误报为成功。

#### 手工真实部署 direct-path k6

`.github/workflows/perf-smoke.yml` 是手工外部环境验证入口，默认执行 `direct-path/smoke`。它消费现有 REST/预签名合同，执行 direct create、无平台鉴权头的 raw PUT、ETag 提交、direct complete、manifest download metadata、raw GET size/hash 复核和按 `RUN_ID` 清理。

该工作流使用 digest 固定的 k6 镜像并归档 `direct-path-baseline.json`、`direct-path-report.md`、原始 summary/metrics 与 `run-meta.json`。资源或 lifecycle 端点分别记录运行开始与清理后结束的 availability；未配置时必须显示 `unavailable`，不得填充伪造的零值。direct 套件关闭日志并禁用 `url`/`name` system tags，避免预签名查询参数进入 artifact。

baseline 仅在 flow/cleanup 样本、完成文件、p95/p99、吞吐和失败率完整时有效；比较器还要求环境指纹、k6 引擎/digest、profile/scenario、分片规模、executor、并发、时长和 VU pool 一致。缺指标返回 `INVALID_EVIDENCE`，负载或环境不同返回 `NOT_COMPARABLE`。外部 secrets 不参与 PR required gate。

## 5. CI 执行策略

- GitHub Actions 会执行：
  - 后端：`mvn -f platform-backend/pom.xml clean verify -pl backend-common,backend-service,backend-web -am -Pit`
  - FISCO：`mvn -f platform-fisco/pom.xml test`
  - Storage：`mvn -f platform-storage/pom.xml clean verify -Pit`，并精确校验 Failsafe XML、负载报告与零残留；另执行 `verify -Pdirect-upload-constrained-heap`
  - 前端质量门禁：
    - `pnpm lint`
    - `pnpm check`
    - `pnpm test:coverage`
    - `pnpm audit --audit-level high`
  - 安全阻断子集：Trivy 已修复 High/Critical 扫描使用 `exit-code: 1`；完整 `security-poc` 聚合仍为观察模式
  - 契约一致性门禁：
    - 后端导出 `platform-backend/backend-web/target/openapi/openapi.json`
    - 前端执行 `OPENAPI_SOURCE=../backend-openapi/openapi.json pnpm types:gen`
    - 校验 `platform-frontend/src/lib/api/types/generated.ts` 无未提交差异
- 后端覆盖率报告由 JaCoCo 生成，CI 中会上传 `backend-common`、`backend-service`、`backend-web` 三模块的 `jacoco.xml`（见 `.github/workflows/test.yml`）。
- 任一步骤失败都会阻断 PR 合并（包括 lint/check/contract-consistency）。

### 文档与在线证据校验

```bash
python3 tools/docs/check_consistency.py \
  --check-routes --check-env --check-roadmap --check-versions
pnpm -C docs install --frozen-lockfile
pnpm -C docs docs:build
```

`docs-consistency.yml` 负责 canonical `ROADMAP.md`、路由、环境变量、版本与文档构建；`docs.yml` 负责 Pages 构建部署。文档 leaf 合并后必须在 exact merge SHA 上通过两者，并以线上 Pages HTTP 200 作为发布验收。工作区或 PR 分支构建成功不能表述为已上线。

## 6. 存储完整性校验测试

### 单元测试（`IntegrityCheckServiceTest`）

- S3 文件存在性验证 + 数据库与链上哈希一致性比对
- 告警生命周期（创建、确认、解决）
- 分布式锁行为（Redisson 锁获取与冲突）
- 边界场景（空文件列表、无租户数据、锁冲突）

### 集成测试（`IntegrityAlertControllerIT`）

- 管理员 REST API 端点（列表查询、手动触发校验、确认告警、解决告警）
- 认证与授权校验（非管理员拒绝访问）

## 7. 新增测试的建议（保持"最少代码"）

- 优先给 **纯业务逻辑** 写单元测试：无 Spring 上下文、无外部依赖、直接 new / Mockito 即可
- 只为最关键链路写少量集成测试：数据库迁移 + ORM 映射 + 关键 AOP/拦截器
- 单测不要依赖执行顺序；集成测试也尽量使用随机/唯一数据，避免与其他用例耦合
