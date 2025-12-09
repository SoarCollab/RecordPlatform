# RecordPlatform

基于 FISCO BCOS 区块链和 MinIO 分布式存储的企业级文件存证平台。

## 核心能力

**业务特性**
- 区块链存证：文件元数据上链，保证不可篡改和可追溯
- 分布式存储：MinIO 双副本冗余，任一副本可读
- 分片上传：大文件断点续传，AES-GCM 加密存储
- 文件分享：生成带访问次数限制的分享码
- 协作通知：站内消息、公告广播、工单系统、会话管理
- SSE 实时推送：文件状态变更、消息通知实时送达

**平台能力**
- 分布式事务：Saga + Outbox 模式保证跨服务数据一致性
- 弹性容错：Resilience4j 熔断、重试、限流
- 安全机制：JWT 认证、ID 混淆、CORS 白名单、登录限流
- 多租户隔离：数据库/Redis/MinIO 路径租户隔离
- 审计追踪：完整的操作日志和审计记录

## 系统架构

```
                         Infrastructure
    ┌────────┐  ┌───────┐  ┌──────────┐  ┌───────┐  ┌────────────┐
    │ Nacos  │  │ MySQL │  │ RabbitMQ │  │ Redis │  │ MinIO 集群  │
    │ :8848  │  │ :3306 │  │  :5672   │  │ :6379 │  │   :9000    │
    └────┬───┘  └───┬───┘  └────┬─────┘  └───┬───┘  └─────┬──────┘
         │          │           │            │            │
         └──────────┴───────────┴────────────┴────────────┘
                               │
    ┌──────────────────────────┴──────────────────────────┐
    │                    platform-api                     │
    │         BlockChainService, DistributedStorageService│
    └──────────────────────────┬──────────────────────────┘
                               │ implements
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     │                     ▼
┌─────────────────┐            │            ┌─────────────────┐
│ platform-fisco  │            │            │  platform-minio │
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
Client                Backend               MinIO              Blockchain
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
2. **chunk**: 分片上传，每片 AES-GCM 加密后双写 MinIO
3. **complete**: 触发 Saga 事务，合并分片、计算哈希、上链存证
4. **SSE**: 实时推送上传进度和存证结果

### Saga 补偿机制

| 步骤 | 正向操作 | 补偿操作 |
|------|---------|---------|
| PENDING | 初始化 | - |
| MINIO_UPLOADING | MinIO 存储分片 | 清理已存储分片 |
| MINIO_UPLOADED | 分片存储完成 | 删除 MinIO 文件 |
| CHAIN_STORING | 区块链存证 | 标记链上记录删除 |
| COMPLETED | 提交成功 | - |

补偿策略：指数退避重试（初始 1s，最大 5 次），失败后进入人工处理队列。

## 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言/框架 | Java, Spring Boot | 21, 3.2.11 |
| 微服务 | Apache Dubbo (Triple), Nacos | 3.3.3 |
| 区块链 | FISCO BCOS, Solidity | 3.8.0, ^0.8.11 |
| 存储 | MinIO, MySQL, Redis | 8.5.9, 8.0+, 6.0+ |
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
| MinIO | 9000 | 对象存储（建议双节点） |
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
mvn -f platform-minio/pom.xml clean package -DskipTests
```

### 启动

```bash
# 使用启动脚本（推荐）
./scripts/start.sh all

# 或手动启动（按顺序）
source .env
java -jar platform-minio/target/platform-minio-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
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
│   │   └── resources/db/migration/  # Flyway 迁移脚本
│   ├── backend-service/          # 业务逻辑、Saga、Outbox
│   ├── backend-dao/              # MyBatis Plus Mapper、实体
│   └── backend-common/           # 工具类、常量、分布式锁
│
├── platform-fisco/               # 区块链服务 (Dubbo Provider)
│   └── contract/                 # Solidity 智能合约
│
├── platform-minio/               # 存储服务 (Dubbo Provider)
│
└── scripts/                      # 启动脚本
```

## 安全机制

### JWT 认证
- 签发：登录成功后签发 Token，有效期配置化
- 刷新：临近过期自动刷新，无感续期
- 黑名单：退出登录后 Token 加入 Redis 黑名单

### 登录安全
- IP 限流：单 IP 5 分钟内最多 10 次失败尝试
- 账号锁定：同账号连续失败 5 次锁定 30 分钟
- 验证码：登录失败 3 次后强制验证码

### ID 混淆
外部 API 使用 UUID 映射，内部使用雪花 ID，映射关系存储于 Redis：
```
外部请求 → UUID → Redis 查询 → 内部 Snowflake ID → 数据库查询
```

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
| MinIO | 路径 `/{tenantId}/{userId}/` |
| Dubbo | Context 透传 `TenantContext` |

### 租户标识来源
JWT Token 中的 `tenantId` 字段，由 `JwtAuthenticationFilter` 解析后写入 `TenantContext`。

## 数据库迁移

Flyway 脚本位于 `platform-backend/backend-web/src/main/resources/db/migration/`：

| 版本 | 说明 |
|------|------|
| V1.0.0 | 核心实体 (account, file, image_store) |
| V1.0.1 | Saga + Outbox 表 |
| V1.0.2 | 操作日志表 |
| V1.0.3 | 审计日志表 |
| V1.0.4 | 多租户支持 |
| V1.1.0 | 消息服务表 (message, announcement, ticket, conversation) |

启动时自动执行，或手动初始化：
```sql
CREATE DATABASE RecordPlatform CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

## 环境变量

主要配置项（完整列表见 `.env.example`）：

| 分类 | 变量 | 说明 |
|------|------|------|
| 数据库 | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 连接 |
| Redis | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis 连接 |
| Nacos | `NACOS_HOST`, `NACOS_USERNAME`, `NACOS_PASSWORD` | 配置中心 |
| MinIO | `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | 对象存储 |
| RabbitMQ | `RABBITMQ_ADDRESSES`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | 消息队列 |
| 安全 | `JWT_KEY`, `ID_SECURITY_KEY` | 签名密钥 |
| 区块链 | `FISCO_PEER_ADDRESS`, `FISCO_STORAGE_CONTRACT`, `FISCO_SHARING_CONTRACT` | 合约配置 |

## 监控端点

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 健康状态 (DB/Redis/RabbitMQ/MinIO/Saga/Outbox) |
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

## API 模块

### 认证 `/api/v1/auth`
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ask-code` | 获取验证码 |
| POST | `/register` | 用户注册 |
| POST | `/reset-password` | 重置密码 |

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

详细 API 文档请访问 Swagger UI。

## 智能合约

| 合约 | 功能 |
|------|------|
| Storage.sol | 文件元数据存储、查询、删除 |
| Sharing.sol | 文件分享、访问控制、次数限制 |

合约地址通过环境变量配置：`FISCO_STORAGE_CONTRACT`, `FISCO_SHARING_CONTRACT`

## 常见问题

**Q: 启动报 Nacos 连接失败**
A: 确认 Nacos 已启动，检查 `NACOS_HOST` 配置。

**Q: MinIO 上传失败**
A: 检查 MinIO 集群健康：`/actuator/health` 中 `minio` 状态。双节点至少一个在线即可写入。

**Q: Saga 事务卡在 PENDING**
A: 检查 `file_saga` 表状态，查看 `outbox_event` 是否有积压。可通过 `/actuator/health` 查看 Saga/Outbox 健康指标。

**Q: 区块链存证延迟**
A: 检查 FISCO 节点连接和熔断器状态：`/actuator/circuitbreakers`。

## 许可证

Apache License 2.0
