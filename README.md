# RecordPlatform

基于 FISCO BCOS 区块链和 S3兼容存储 分布式存储的企业级文件存证平台。

## 核心能力

**业务特性**
- 区块链存证：文件元数据上链，保证不可篡改和可追溯
- 分布式存储：S3兼容存储 双副本冗余，任一副本可读
- 分片上传：大文件断点续传，支持 AES-GCM / ChaCha20-Poly1305 可配置加密
- 文件分享：生成带访问次数限制的分享码
- 协作通知：站内消息、公告广播、工单系统、会话管理
- SSE 实时推送：文件状态变更、消息通知实时送达

**平台能力**
- 分布式事务：Saga + Outbox 模式保证跨服务数据一致性
- 弹性容错：Resilience4j 熔断、重试、限流
- 权限控制：RBAC 细粒度权限、资源所有权校验、分级限流
- 安全机制：JWT 认证（HMAC512）、ID 混淆、CORS 白名单
- 多租户隔离：数据库/Redis/S3兼容存储 路径租户隔离
- 审计追踪：完整的操作日志和审计记录

## 系统架构

```
                         Infrastructure
    ┌────────┐  ┌───────┐  ┌──────────┐  ┌───────┐  ┌────────────┐
    │ Nacos  │  │ MySQL │  │ RabbitMQ │  │ Redis │  │ S3存储集群  │
    │ :8848  │  │ :3306 │  │  :5672   │  │ :6379 │  │   :9000    │
    └────┬───┘  └───┬───┘  └────┬─────┘  └───┬───┘  └─────┬──────┘
         │          │           │            │            │
         └──────────┴───────────┴────────────┴────────────┘
                               │
    ┌──────────────────────────┴──────────────────────────┐
    │                    platform-api                     │
    │      FiscoExternalService, StorageExternalService   │
    └──────────────────────────┬──────────────────────────┘
                               │ implements
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     │                     ▼
┌─────────────────┐            │            ┌─────────────────┐
│ platform-fisco  │            │            │ platform-storage│
│ Dubbo Provider  │            │            │ Dubbo Provider  │
│ Port 8091       │            │            │ Port 8092       │
└────────┬────────┘            │            └────────┬────────┘
         │                     │                     │
         │      Dubbo RPC      ▼      Dubbo RPC      │
         │            ┌─────────────────┐            │
         └───────────►│ platform-backend│◄───────────┘
                      │ Dubbo Consumer  │
                      │ REST API :8000  │
                      └────────┬────────┘
                               │
                      ┌────────▼────────┐
                      │ FISCO BCOS Node │
                      │ Peer :20200     │
                      └─────────────────┘
```

## 核心业务流程

### 文件上传存证流程

```
Client                Backend               S3兼容存储              Blockchain
  │                      │                    │                    │
  │── 1. start upload ──►│                    │                    │
  │◄─── upload session ──│                    │                    │
  │                      │                    │                    │
  │── 2. upload chunk ──►│                    │                    │
  │                      │── AES-GCM encrypt ─│                    │
  │                      │── store chunk ────►│                    │
  │                      │◄── chunk stored ───│                    │
  │◄─── chunk ack ───────│                    │                    │
  │                      │                    │                    │
  │── 3. complete ──────►│                    │                    │
  │                      │─────── Saga Transaction ───────────────►│
  │                      │                    │── store metadata ─►│
  │                      │                    │◄── tx hash ────────│
  │                      │◄── Outbox publish ─│                    │
  │◄─── file record ─────│                    │                    │
  │                      │                    │                    │
  │◄══ 4. SSE: status ═══│                    │                    │
```

**关键步骤说明**
1. **start**: 创建上传会话，返回 uploadId 和分片参数
2. **chunk**: 分片上传，每片使用配置的算法（AES-GCM/ChaCha20）加密后双写 S3兼容存储
3. **complete**: 触发 Saga 事务，合并分片、计算哈希、上链存证
4. **SSE**: 实时推送上传进度和存证结果

### Saga 补偿机制

| 步骤 | 正向操作 | 补偿操作 |
|------|---------|---------|
| PENDING | 初始化 | - |
| MINIO_UPLOADING | S3兼容存储 存储分片 | 清理已存储分片 |
| MINIO_UPLOADED | 分片存储完成 | 删除 S3兼容存储 文件 |
| CHAIN_STORING | 区块链存证 | 标记链上记录删除 |
| COMPLETED | 提交成功 | - |

补偿策略：指数退避重试（初始 1s，最大 5 次），失败后进入人工处理队列。

### 补偿原子化策略

`SagaCompensationHelper` 使用 `REQUIRES_NEW` 事务传播级别，确保每个补偿步骤独立提交：

**问题背景**：
- 外部调用（如 MinIO 删除）成功后，若后续事务回滚，状态会不一致
- 重试时无法判断哪些步骤已完成

**解决方案**：
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void persistPayloadInNewTransaction(FileSaga saga, String payloadJson) {
    // 补偿步骤完成后立即在独立事务中持久化状态
    sagaMapper.updatePayloadById(saga.getId(), payloadJson);
}
```

**优势**：
- 每个补偿步骤完成后立即提交（无依赖于外层事务）
- 重试时根据持久化的步骤状态跳过已完成操作
- 即使系统崩溃也能恢复到一致状态

## 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言/框架 | Java, Spring Boot | 21, 3.2.11 |
| 微服务 | Apache Dubbo (Triple), Nacos | 3.3.3 |
| 区块链 | FISCO BCOS, Solidity | 3.8.0, ^0.8.11 |
| 区块链扩展 | Web3j (Besu 支持) | 4.9.8 |
| 存储 | S3兼容存储, MySQL, Redis | 8.5.9, 8.0+, 6.0+ |
| S3 客户端 | AWS SDK v2 | 2.29.30 |
| ORM | MyBatis Plus, Druid | 3.5.9, 1.2.23 |
| 消息队列 | RabbitMQ | 3.8+ |
| 弹性设计 | Resilience4j | 2.2.0 |
| 分布式锁 | Redisson | 3.35.0 |
| 数据库迁移 | Flyway | 9.22.3 |
| 监控 | Micrometer, Prometheus | - |

## 快速开始

### 前置依赖

| 服务 | 端口 | 用途 |
|------|------|------|
| Nacos | 8848 | 服务发现和配置中心 |
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存和分布式锁 |
| RabbitMQ | 5672 | 消息队列 |
| S3兼容存储 | 9000 | 对象存储（建议双节点） |
| FISCO BCOS | 20200 | 区块链节点 |

### 环境配置

```bash
cp .env.example .env
vim .env  # 填入实际配置
```

### 构建

```bash
# 1. 安装共享接口
mvn -f platform-api/pom.xml clean install

# 2. 构建各模块
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 启动

```bash
# 使用启动脚本（推荐）
./scripts/start.sh all

# 或手动启动（按顺序）
source .env
java -jar platform-storage/target/platform-storage-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
java -jar platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
java -jar platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Profile: `local`, `dev`, `prod`

### 验证

| 端点 | 地址 |
|------|------|
| Swagger UI | http://localhost:8000/record-platform/swagger-ui.html |
| 健康检查 | http://localhost:8000/record-platform/actuator/health |
| Prometheus | http://localhost:8000/record-platform/actuator/prometheus |

> 生产环境 Swagger 和 Druid 监控已禁用

## 项目结构

```
RecordPlatform/
├── platform-api/                 # Dubbo 接口定义
│   └── external/                 # BlockChainService, DistributedStorageService
│
├── platform-backend/             # 后端主服务 (Dubbo Consumer)
│   ├── backend-web/              # REST 控制器、过滤器、配置
│   │   ├── controller/           # API 端点
│   │   ├── aspect/               # AOP 切面 (权限、限流)
│   │   ├── security/             # 自定义 SpEL 表达式
│   │   └── resources/db/migration/  # Flyway 迁移脚本
│   ├── backend-service/          # 业务逻辑、Saga、Outbox、权限服务
│   ├── backend-dao/              # MyBatis Plus Mapper、实体
│   └── backend-common/           # 工具类、常量、注解、分布式锁
│
├── platform-fisco/               # 区块链服务 (Dubbo Provider)
│   ├── contract/                 # Solidity 智能合约
│   └── adapter/                  # 多链适配器 (FISCO/BSN/Besu)
│
├── platform-storage/             # 存储服务 (Dubbo Provider)
│
├── platform-frontend/            # Svelte 5 + SvelteKit 前端 (独立部署)
│
└── scripts/                      # 启动脚本
```

## 安全机制

### JWT 认证
- 算法：HMAC512（升级自 HMAC256）
- 签发：登录成功后签发 Token，包含 issuer/audience 声明
- 校验：启动时验证密钥强度（长度 ≥32、熵值 ≥128 bits、弱密钥检测）
- 黑名单：退出登录后 Token 加入 Redis 黑名单

### 登录安全
- IP 限流：单 IP 5 分钟内最多 10 次失败尝试
- 账号锁定：同账号连续失败 5 次锁定 15 分钟
- 验证码：登录失败 3 次后强制验证码

### RBAC 权限控制

**角色定义**
| 角色 | 说明 | 默认权限 |
|------|------|---------|
| `user` | 普通用户 | 文件读写删除分享、工单创建、消息收发 |
| `admin` | 管理员 | 所有权限 |
| `monitor` | 监控员 | 只读权限 + 审计日志查看 |

**权限码格式**
```
module:action
```
示例：`file:read`, `file:admin`, `ticket:write`, `system:audit`

**使用方式**
```java
// 权限码检查
@PreAuthorize("hasPerm('file:admin')")

// 多权限（任一满足）
@PreAuthorize("hasAnyPerm('file:read', 'file:admin')")

// 角色检查
@PreAuthorize("isAdmin()")
@PreAuthorize("isAdminOrMonitor()")

// 资源所有权检查
@PreAuthorize("isOwner(#file.uid)")
@PreAuthorize("isOwner(#userId) or hasPerm('file:admin')")
```

**资源所有权注解**
```java
@RequireOwnership(
    resourceIdParam = "id",        // 参数名
    ownerIdField = "uid",          // 实体中所有者字段
    resourceClass = File.class,    // 实体类
    adminBypass = true             // 管理员跳过校验
)
public Result<File> getFile(@PathVariable Long id) { ... }
```

### 分级限流

支持按角色设置不同的限流阈值：

```java
@RateLimit(
    limit = 10,           // 普通用户：10次/分钟
    period = 60,          // 时间窗口（秒）
    adminLimit = 100,     // 管理员：100次/分钟
    type = LimitType.USER // 限流维度：USER/IP/API
)
```

限流维度：
- `USER`：按用户 ID 限流（默认）
- `IP`：按 IP 地址限流
- `API`：全局限流（所有请求共享配额）

### 分布式限流器

基于 Redis Lua 脚本的滑动窗口限流，支持多实例部署：

**两种模式**：
| 模式 | 方法 | 说明 |
|------|------|------|
| 带封禁 | `tryAcquireWithBlock()` | 超限后触发封禁，封禁期间直接拒绝 |
| 简单限流 | `tryAcquire()` | 仅计数限流，无封禁机制 |

**返回值**：
- `ALLOWED`：允许访问
- `RATE_LIMITED`：窗口期内超限（在窗口期内超过限制）
- `BLOCKED`：已在封禁列表中

**实现原理**：
1. 使用 `INCR` 原子递增计数器
2. 首次访问时设置过期时间（窗口期）
3. 超过阈值后设置封禁 key
4. 所有操作在单个 Lua 脚本中原子执行

**容错策略**：Redis 故障时自动放行，避免服务不可用。

### ID 混淆
外部 API 使用加密 ID，内部使用雪花 ID。采用无状态 AES 加密，无需 Redis 缓存：
```
外部请求 → AES 解密 → 内部 Snowflake ID → 数据库查询
```

**技术实现**
- 加密算法：AES-256-CTR + HMAC-SHA256（SIV 风格）
- 输出格式：前缀 + Base62 编码（约 40 字符）
  - `E` 前缀：实体 ID（文件、记录等）
  - `U` 前缀：用户 ID
- 密钥派生：HKDF 从 `JWT_KEY` 派生独立的加密密钥和 MAC 密钥
- 安全特性：16 字节 SIV + 10 字节 HMAC 完整性校验，防篡改

**数据结构**
```
明文 (16 bytes): [version:1][type:1][id:8][padding:6]
密文 (42 bytes): [SIV:16][AES-CTR(plaintext):16][HMAC:10]
输出: prefix + Base62(密文) ≈ 40 chars
```

### HTTPS 强制

生产环境自动将 HTTP 请求 301 重定向到 HTTPS：

**配置项**：
```yaml
server:
  port: 443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-type: PKCS12

security:
  require-ssl: true           # 启用 HTTPS 强制
  http-redirect-port: 80      # HTTP 监听端口
```

**实现方式**：
- 额外创建 HTTP Connector 监听 80 端口
- 配置 Security Constraint 强制 CONFIDENTIAL 传输
- 所有 HTTP 请求自动 301 重定向到 HTTPS
- 仅在 `prod` Profile 且 `security.require-ssl=true` 时激活

### 文件加密策略
分片文件加密支持两种算法，可通过配置切换：

| 算法 | 特点 | 适用场景 |
|------|------|---------|
| AES-256-GCM | 有 AES-NI 硬件加速时极快（1000+ MB/s） | 物理服务器、有 AES-NI 的 x86 主机 |
| ChaCha20-Poly1305 | 软件实现稳定，抗侧信道攻击 | 容器环境、ARM 设备、混合云 |

**配置项**
```yaml
file:
  encryption:
    # aes-gcm, chacha20, auto
    algorithm: chacha20          # 默认 ChaCha20（安全默认值）
    benchmark-on-startup: false  # auto 模式时启动时运行基准测试
```

**分片文件格式** (v2.0+)
```
[Header: 4B] [IV/Nonce: 12B] [加密数据] [认证标签] [--HASH--\n] [hash] [--NEXT_KEY--\n] [key]
```
- Header: 魔数 `RP` (0x52 0x50) + 版本号 (0x01) + 算法标识 (0x01=AES-GCM, 0x02=ChaCha20)
- **重要**：v2.0 不再支持无头部的旧格式，所有加密文件必须包含版本头

### SSE 短期令牌

由于 EventSource 不支持自定义 Header，SSE 连接使用 URL token 参数认证：

**流程**：
1. `POST /api/v1/auth/sse-token` 获取短期令牌（需 JWT）
2. `GET /api/v1/sse/connect?token=<token>` 建立 SSE 连接

**特性**：
- 有效期 30 秒
- 一次性使用
- 与主 JWT 绑定用户身份

## 弹性容错

### Resilience4j 配置示例

```yaml
resilience4j:
  circuitbreaker:
    instances:
      blockChainService:
        sliding-window-size: 50
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      storageService:
        slow-call-duration-threshold: 8s
        slow-call-rate-threshold: 80
  retry:
    instances:
      storageService:
        max-attempts: 3
        wait-duration: 1s
        exponential-backoff-multiplier: 2
```

### 熔断联动
- 存储服务熔断时，Saga 暂停新事务，已有事务进入补偿队列
- 区块链服务熔断时，文件存储正常，存证延迟执行

## 多租户

### 隔离策略
| 层级 | 隔离方式 |
|------|---------|
| 数据库 | `tenant_id` 字段，MyBatis 自动注入 |
| Redis | Key 前缀 `tenant:{tenantId}:` |
| S3兼容存储 | 路径 `/{tenantId}/{userId}/` |
| Dubbo | Context 透传 `TenantContext` |

### 租户标识来源
JWT Token 中的 `tenantId` 字段，由 `JwtAuthenticationFilter` 解析后写入 `TenantContext`。

### 声明式租户上下文控制

`@TenantScope` 注解用于声明式控制方法执行时的租户隔离行为：

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `ignoreIsolation` | false | 忽略租户隔离，跨租户查询 |
| `tenantId` | -1 | 切换到指定租户执行 |

**使用示例**
```java
// 定时任务跨租户查询
@TenantScope(ignoreIsolation = true)
@Scheduled(cron = "0 0 3 * * ?")
public void cleanupDeletedFiles() { ... }

// 健康检查跨租户统计
@TenantScope(ignoreIsolation = true)
public Health health() {
    long totalPending = mapper.countByStatus("PENDING");
}

// 切换到指定租户执行
@TenantScope(tenantId = 1)
public void migrateDataForTenant() { ... }
```

**程序化租户切换**
```java
// 按租户循环处理
for (Long tenantId : tenantIds) {
    TenantContext.callWithTenant(tenantId, () -> {
        return processForTenant(tenantId);
    });
}

// 临时跨租户查询
TenantContext.runWithoutIsolation(() -> {
    // 跳过租户过滤的查询
});
```

## 数据库迁移

Flyway 脚本位于 `platform-backend/backend-web/src/main/resources/db/migration/`，Schema 定义位于 `platform-backend/db/schema/`：

| 版本 | 说明 |
|------|------|
| V1.0.0 | 核心实体 (account, file, image_store) |
| V1.0.1 | Saga + Outbox 表 |
| V1.0.2 | 操作日志表 |
| V1.0.3 | 审计日志表 |
| V1.0.4 | 多租户支持 |
| V1.1.0 | 消息服务表 (message, announcement, ticket, conversation) |
| V1.1.1 | 性能优化复合索引 (文件、日志、工单查询) |
| 06_permission | 权限控制表 (sys_permission, sys_role_permission, account_role_audit) |

启动时自动执行，或手动初始化：
```sql
CREATE DATABASE RecordPlatform CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

## 定时任务

### 任务清单

| 任务 | Cron / 间隔 | 说明 |
|------|-------------|------|
| 文件清理 | `0 0 3 * * ?` | 清理软删除超过 30 天的文件 |
| Outbox 清理 | `0 0 3 * * ?` | 清理已发送/失败事件 |
| Saga 补偿 | 30s 间隔 | 处理待补偿的 Saga 事务 |
| Outbox 发布 | 2s 间隔 | 发布待发送的 Outbox 事件 |

### 配置示例

```yaml
# 文件清理
file:
  cleanup:
    retention-days: 30      # 保留天数
    batch-size: 100         # 单次处理数量
    cron: 0 0 3 * * ?

# Outbox 清理
outbox:
  cleanup:
    sent-retention-days: 7     # 已发送保留 7 天
    failed-retention-days: 30  # 失败保留 30 天
    cron: 0 0 3 * * ?

# Saga 补偿
saga:
  compensation:
    max-retries: 5
    batch-size: 50
    poll-interval-ms: 30000
```

### 租户隔离模式

所有定时任务按租户分别执行，确保：
- 单租户失败不影响其他租户
- 使用分布式锁防止多实例重复处理
- 自动恢复租户上下文

## 环境变量

主要配置项（完整列表见 `.env.example`）：

| 分类 | 变量 | 说明 |
|------|------|------|
| 数据库 | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 连接 |
| Redis | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis 连接 |
| Nacos | `NACOS_HOST`, `NACOS_USERNAME`, `NACOS_PASSWORD` | 配置中心 |
| S3兼容存储 | `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | 对象存储 |
| RabbitMQ | `RABBITMQ_ADDRESSES`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | 消息队列 |
| 安全 | `JWT_KEY` | JWT 签名 + ID 加密密钥派生（至少 32 字符） |
| 区块链 | `FISCO_PEER_ADDRESS`, `FISCO_STORAGE_CONTRACT`, `FISCO_SHARING_CONTRACT` | 合约配置 |
| 区块链 | `BLOCKCHAIN_ACTIVE` | 激活的区块链类型 (local-fisco/bsn-fisco/bsn-besu) |
| SSL | `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD` | HTTPS 证书配置（生产环境） |
| SSL | `SECURITY_REQUIRE_SSL`, `SECURITY_HTTP_REDIRECT_PORT` | HTTPS 强制和重定向端口 |

> 注：`ID_SECURITY_KEY` 在 v2.0 后不再需要，ID 加密密钥从 `JWT_KEY` 自动派生

## 监控端点

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 健康状态 (DB/Redis/RabbitMQ/S3兼容存储/Saga/Outbox/Encryption) |
| `/actuator/prometheus` | Prometheus 指标 |
| `/actuator/circuitbreakers` | 熔断器状态 |
| `/actuator/retries` | 重试统计 |

### 关键指标
- `saga_total{status=started|completed|compensated|failed}`: Saga 状态计数
- `saga_duration{phase=execution|compensation}`: Saga 执行/补偿耗时
- `saga_running`: 运行中 Saga 数量
- `saga_pending_compensation`: 待补偿 Saga 数量
- `outbox_events_total{status=published|failed}`: Outbox 事件发布计数
- `outbox_publish_latency`: Outbox 发布延迟
- `outbox_pending`: 待发送 Outbox 事件数
- `outbox_exhausted`: 超过最大重试次数的 Outbox 事件数

### 加密健康检查
`/actuator/health` 端点包含加密策略状态：
```json
{
  "encryption": {
    "status": "UP",
    "details": {
      "algorithm": "ChaCha20-Poly1305",
      "selectionReason": "explicitly configured",
      "ivSize": 12,
      "tagBitLength": 128,
      "likelyHasAesNi": true
    }
  }
}
```

### 健康检查阈值配置

```yaml
# Outbox 健康阈值
outbox:
  health:
    pending-threshold: 500    # 待发送 >500 → DEGRADED
    failed-threshold: 20      # 失败 >20 → DOWN

# Saga 健康阈值
saga:
  health:
    running-threshold: 100              # 运行中 >100 → DEGRADED
    failed-threshold: 10                # 失败 >10 → DOWN
    pending-compensation-threshold: 50  # 待补偿 >50 → DEGRADED
```

健康检查使用 `@TenantScope(ignoreIsolation = true)` 进行跨租户统计，返回全局健康状态。

## API 模块

### 认证 `/api/v1/auth`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ask-code` | 获取验证码 |
| POST | `/register` | 用户注册 |
| POST | `/reset-password` | 重置密码 |
| POST | `/sse-token` | 获取 SSE 连接专用短期令牌 (30s 有效期) |

### 用户 `/api/v1/users`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/info` | 获取用户信息 |
| POST | `/modify-email` | 修改邮箱 |
| POST | `/change-password` | 修改密码 |

### 文件管理 `/api/v1/files`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 文件列表 |
| GET | `/page` | 分页查询 |
| GET | `/address` | 获取预签名下载地址 |
| GET | `/download` | 下载文件 |
| GET | `/getTransaction` | 获取链上交易记录 |
| DELETE | `/deleteByHash` | 按哈希批量删除 |
| DELETE | `/deleteById` | 按 ID 批量删除（管理员） |
| POST | `/share` | 分享文件 |
| GET | `/getSharingFiles` | 获取分享文件列表 |
| POST | `/saveShareFile` | 保存分享文件 |

### 分片上传 `/api/v1/files/upload`
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload/start` | 开始上传 |
| POST | `/upload/chunk` | 上传分片 |
| POST | `/upload/complete` | 完成上传 |
| POST | `/upload/pause` | 暂停上传 |
| POST | `/upload/resume` | 恢复上传 |
| POST | `/upload/cancel` | 取消上传 |
| GET | `/upload/check` | 检查上传状态 |
| GET | `/upload/progress` | 获取上传进度 |

### 协作通知

**公告 `/api/v1/announcements`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 公告列表（分页） |
| GET | `/{id}` | 公告详情 |
| GET | `/unread-count` | 未读公告数 |
| POST | `/{id}/read` | 标记已读 |
| POST | `/read-all` | 标记全部已读 |
| POST | `/` | 发布公告（管理员） |
| PUT | `/{id}` | 编辑公告（管理员） |
| DELETE | `/{id}` | 删除公告（管理员） |
| GET | `/admin/list` | 管理员公告列表 |

**私信 `/api/v1/messages`**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/` | 发送私信 |
| POST | `/to/{receiverId}` | 发送私信（指定接收者） |
| GET | `/unread-count` | 未读私信数 |

**会话 `/api/v1/conversations`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 会话列表（分页） |
| GET | `/{id}` | 会话详情及消息 |
| GET | `/unread-count` | 未读会话数 |
| POST | `/{id}/read` | 标记会话已读 |
| DELETE | `/{id}` | 删除会话 |

**工单 `/api/v1/tickets`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 我的工单列表 |
| GET | `/{id}` | 工单详情 |
| POST | `/` | 创建工单 |
| POST | `/{id}/reply` | 回复工单 |
| POST | `/{id}/close` | 关闭工单 |
| POST | `/{id}/confirm` | 确认完成 |
| GET | `/pending-count` | 待处理工单数 |
| GET | `/admin/list` | 管理员工单列表 |
| PUT | `/admin/{id}/assign` | 分配处理人 |
| PUT | `/admin/{id}/status` | 更新状态 |

**SSE `/api/v1/sse`**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/connect` | 建立 SSE 连接 |
| DELETE | `/disconnect` | 断开连接 |
| GET | `/status` | 获取连接状态 |

SSE 事件类型：`NEW_MESSAGE`, `NEW_ANNOUNCEMENT`, `TICKET_UPDATE`, `TICKET_REPLY`, `HEARTBEAT`, `CONNECTED`

### 审计 `/api/v1/system/audit`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/overview` | 审计概览 |
| GET | `/logs/page` | 审计日志 |
| POST | `/logs/export` | 导出日志 |

### 权限管理 `/api/v1/system/permissions`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 权限列表（分页） |
| GET | `/modules` | 获取所有模块名 |
| POST | `/` | 创建权限定义 |
| PUT | `/{id}` | 更新权限定义 |
| DELETE | `/{id}` | 删除权限定义 |
| GET | `/roles/{role}` | 获取角色权限列表 |
| POST | `/roles/{role}/grant` | 为角色授予权限 |
| DELETE | `/roles/{role}/revoke` | 撤销角色权限 |

详细 API 文档请访问 Swagger UI。

## 智能合约

| 合约 | 功能 |
|------|------|
| Storage.sol | 文件元数据存储、查询、删除 |
| Sharing.sol | 文件分享、访问控制、次数限制 |

合约地址通过环境变量配置：`FISCO_STORAGE_CONTRACT`, `FISCO_SHARING_CONTRACT`

### 多链适配

支持多种区块链网络，通过环境变量切换：

| 链类型 | 配置值 | 说明 |
|--------|--------|------|
| Local FISCO | `local-fisco` | 本地 FISCO BCOS 节点 (默认) |
| BSN FISCO | `bsn-fisco` | 区块链服务网络 FISCO |
| Hyperledger Besu | `bsn-besu` | Hyperledger Besu (EVM 兼容) |

**配置**：
```yaml
blockchain:
  active: ${BLOCKCHAIN_ACTIVE:local-fisco}

  bsn-fisco:
    node-id: <bsn-node-id>
    peers: ["<peer-address>"]

  bsn-besu:
    rpc-url: https://<besu-rpc>
    chain-id: <chain-id>
```

## 常见问题

**Q: 启动报 Nacos 连接失败**
A: 确认 Nacos 已启动，检查 `NACOS_HOST` 配置。

**Q: S3兼容存储 上传失败**
A: 检查 S3兼容存储 集群健康：`/actuator/health` 中 `minio` 状态。双节点至少一个在线即可写入。

**Q: Saga 事务卡在 PENDING**
A: 检查 `file_saga` 表状态，查看 `outbox_event` 是否有积压。可通过 `/actuator/health` 查看 Saga/Outbox 健康指标。

**Q: 区块链存证延迟**
A: 检查 FISCO 节点连接和熔断器状态：`/actuator/circuitbreakers`。

**Q: 解密旧版本文件失败**
A: v2.0 不再支持无版本头的旧格式文件。如需迁移旧数据，需重新加密或使用数据迁移工具。

**Q: 如何选择加密算法**
A: 如果服务器有 AES-NI 指令集支持，推荐 `aes-gcm`；容器/ARM 环境推荐 `chacha20`。可设置 `algorithm: auto` + `benchmark-on-startup: true` 让系统自动选择。

## 系统演进状态

**当前版本**：v6.0

| 阶段 | 状态 | 完成内容 |
|------|------|---------|
| P0 | ✅ | Bug 修复、CORS 安全加固、大文件上传超时优化 |
| P1 | ✅ | HTTPS 强制、分布式流控改造、定时任务分布式锁、Saga 补偿原子化 |
| P2 | ✅ | SkyWalking Agent 部署、健康检查指标补全、结构化日志标准化 |
| P3 | ✅ | 存储路径租户隔离、Redis Key 租户隔离、Dubbo 租户传播 |
| P4 | 🔄 | API 版本化、区块链 HA、智能合约优化、CQRS/虚拟线程 |

**系统成熟度**：`8.0/10` - 基础架构完善，生产环境就绪，进入长期优化阶段

详细演进规划参见 [EVOLUTION_PLAN.md](./EVOLUTION_PLAN.md)

## 许可证

Apache License 2.0
