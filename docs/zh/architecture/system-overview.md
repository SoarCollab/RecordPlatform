# 系统架构总览

## 架构图

```mermaid
flowchart TB
    %% Style Definitions %%
    classDef service fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff
    classDef infra fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff
    classDef chain fill:#8b5cf6,stroke:#7c3aed,stroke-width:2px,color:#ffffff
    classDef client fill:#f97316,stroke:#ea580c,stroke-width:2px,color:#ffffff

    subgraph ClientLayer["客户端层"]
        Frontend["前端应用 (Web/H5/App)"]:::client
    end

    subgraph ServiceLayer["微服务层 (Dubbo)"]
        direction TB
        Backend["platform-backend<br/>(API 网关 & 消费者)"]:::service
        Fisco["platform-fisco<br/>(区块链提供者)"]:::service
        Storage["platform-storage<br/>(存储提供者)"]:::service
    end

    subgraph InfraLayer["基础设施层"]
        direction TB
        Nacos["Nacos<br/>(注册中心 & 配置中心)"]:::infra
        MySQL[("MySQL<br/>(业务数据)")]:::infra
        Redis[("Redis<br/>(缓存)")]:::infra
        RabbitMQ["RabbitMQ<br/>(异步消息)"]:::infra
    end

    subgraph ExternalLayer["外部依赖层"]
        S3["S3 对象存储集群"]:::infra
        BCOS["FISCO BCOS 节点"]:::chain
    end

    %% Connections %%
    Frontend -->|HTTP/REST| Backend

    Backend -->|Dubbo RPC| Fisco
    Backend -->|Dubbo RPC| Storage

    %% Service to Infra %%
    Backend --> MySQL & Redis & RabbitMQ
    Fisco --> MySQL & Redis
    Storage --> MySQL & Redis

    %% Service Registry %%
    Backend -.->|注册/发现| Nacos
    Fisco -.->|注册| Nacos
    Storage -.->|注册| Nacos

    %% Service to External %%
    Fisco -->|通道/RPC| BCOS
    Storage -->|S3 接口| S3
```

## 模块职责

### platform-api

所有模块依赖的共享 Dubbo 接口定义：

- `BlockChainService` - 区块链操作
- `DistributedStorageService` - 存储操作
- 公共 DTO 和响应类型

### platform-backend

多模块后端服务（Dubbo Consumer）：

| 子模块              | 职责                                |
| ------------------- | ----------------------------------- |
| **backend-web**     | REST 控制器、JWT 过滤器、限流、CORS |
| **backend-service** | 业务逻辑、Saga 编排、Outbox 发布    |
| **backend-dao**     | MyBatis Plus 映射、实体、VO         |
| **backend-api**     | 内部 API 接口定义                   |
| **backend-common**  | 工具类、常量、注解                  |

### platform-fisco

区块链集成服务（Dubbo Provider）：

- 智能合约交互（Storage.sol, Sharing.sol）
- 多链适配器（本地 FISCO、BSN FISCO、Besu）
- 证书管理

### platform-storage

分布式存储服务（Dubbo Provider）：

- 多节点 S3 客户端管理
- 故障域管理
- 一致性哈希和再平衡
- 文件加密/解密

## 核心业务流程

### 文件上传存证

```mermaid
sequenceDiagram
    autonumber

    participant Client as 客户端
    participant Backend as platform-backend
    participant Storage as platform-storage
    participant S3 as S3 Cluster
    participant Chain as FISCO BCOS

    Note over Client, Backend: 阶段 1: 初始化上传
    Client->>Backend: POST /api/v1/files/upload/start
    Backend->>Backend: 校验配额 & 文件是否存在
    Backend-->>Client: 200 OK (ClientId, ChunkSize)

    Note over Client, S3: 阶段 2: 分片上传
    loop 每个分片
        Client->>Backend: POST /api/v1/files/upload/chunk
        Backend->>Storage: RPC: storeChunk()
        Storage->>S3: PutObject (加密)
        S3-->>Storage: 成功
        Storage-->>Backend: 分片哈希
        Backend-->>Client: 200 OK
    end

    Note over Client, Chain: 阶段 3: 存证 & 确认
    Client->>Backend: POST /api/v1/files/upload/complete
    Backend->>Backend: 合并分片元数据
    Backend->>Chain: 异步 Transaction (Saga)
    Chain-->>Backend: TxHash
    Backend-->>Client: 200 OK (FileID)

    Note right of Backend: 通过 SSE 推送最终链上状态
    Backend--)Client: SSE: UPLOAD_COMPLETED
```

### 文件下载流程

```mermaid
sequenceDiagram
    autonumber

    participant Client as 客户端
    participant Backend as platform-backend
    participant Storage as platform-storage
    participant S3 as S3 Cluster

    Note over Client, Backend: 阶段 1: 获取下载信息
    Client->>Backend: GET /api/v1/files/address
    Backend->>Backend: 校验权限 & 获取文件元数据
    Backend->>Storage: RPC: generatePresignedUrls()
    Storage->>S3: 生成预签名 URL
    S3-->>Storage: 预签名 URL 列表
    Storage-->>Backend: URL + 解密密钥链
    Backend-->>Client: 200 OK (URLs, ChunkInfo)

    Note over Client, Backend: 阶段 1.5: 获取解密信息
    Client->>Backend: GET /api/v1/files/decryptInfo
    Backend-->>Client: 200 OK (DecryptKeys)

    Note over Client, S3: 阶段 2: 并发下载分片
    par 并发下载
        Client->>S3: GET 预签名 URL (分片 1)
        S3-->>Client: 加密分片数据
    and
        Client->>S3: GET 预签名 URL (分片 2)
        S3-->>Client: 加密分片数据
    and
        Client->>S3: GET 预签名 URL (分片 N)
        S3-->>Client: 加密分片数据
    end

    Note over Client: 阶段 3: 解密 & 组装
    Client->>Client: 按密钥链顺序解密分片
    Client->>Client: 合并分片 & 触发浏览器下载
```

**下载策略对比**：

| 策略 | 适用场景 | 特点 |
|------|----------|------|
| **内存模式** | 小文件 (< 50MB) | 全部分片加载到内存后解密，速度快 |
| **流式模式** | 大文件 (≥ 50MB) | 使用 StreamSaver.js，边下载边写入，内存占用低 |
| **后端代理** | 特殊场景 | 后端代理下载，适用于无法直连 S3 的环境 |

**密钥链解密**：每个分片使用独立密钥加密，下载时按 `chunkIndex` 顺序匹配密钥进行解密。

### 文件分享流程

#### 普通分享（链接分享）

```mermaid
sequenceDiagram
    autonumber

    participant Owner as 文件所有者
    participant Backend as platform-backend
    participant Chain as FISCO BCOS
    participant DB as MySQL
    participant Visitor as 访问者

    Note over Owner, Chain: 阶段 1: 生成分享
    Owner->>Backend: POST /api/v1/files/share
    Backend->>Chain: RPC: generateSharingCode()
    Chain->>Chain: 生成分享码 & 存储元数据
    Chain-->>Backend: ShareCode
    Backend->>DB: 同步分享记录
    Backend-->>Owner: 200 OK (ShareCode, ShareUrl)

    Note over Visitor, DB: 阶段 2: 访问分享
    Visitor->>Backend: GET /api/v1/share/{shareCode}/info
    Backend->>DB: 查询分享记录
    alt 数据库命中
        DB-->>Backend: 分享信息
    else 数据库未命中
        Backend->>Chain: RPC: querySharingInfo()
        Chain-->>Backend: 分享信息
    end
    Backend->>Backend: 校验有效期 & 访问密码
    Backend->>DB: 记录访问日志
    Backend-->>Visitor: 200 OK (FileInfo)
```

#### 好友分享

```mermaid
sequenceDiagram
    autonumber

    participant Owner as 文件所有者
    participant Backend as platform-backend
    participant DB as MySQL
    participant SSE as SSE Manager
    participant Friend as 好友

    Note over Owner, DB: 阶段 1: 发起分享
    Owner->>Backend: POST /api/v1/friend-shares
    Backend->>DB: 校验好友关系
    Backend->>DB: 创建好友分享记录
    Backend->>SSE: 推送分享通知
    SSE--)Friend: SSE: friend-file-shared
    Backend-->>Owner: 200 OK

    Note over Friend, DB: 阶段 2: 查看分享
    Friend->>Backend: GET /api/v1/friend-shares/received
    Backend->>DB: 查询收到的分享列表
    Backend->>DB: 更新已读状态
    Backend-->>Friend: 200 OK (SharedFiles)

    Note over Friend, Backend: 阶段 3: 下载分享文件
    Friend->>Backend: GET /api/v1/files/address (使用分享文件哈希)
    Backend->>Backend: 使用原上传者 ID 查询文件
    Backend-->>Friend: 200 OK (DownloadInfo)
```

**分享类型对比**：

| 类型 | 访问控制 | 有效期 | 特点 |
|------|----------|--------|------|
| **公开分享** | 无限制 | 可设置 | 任何人可通过链接访问 |
| **私密分享** | 访问密码 | 可设置 | 需要密码才能访问 |
| **好友分享** | 好友关系 | 永久 | 仅指定好友可见，支持已读状态 |

### Saga 补偿流程

| 步骤          | 正向操作     | 补偿操作         |
| ------------- | ------------ | ---------------- |
| PENDING       | 初始化       | -                |
| S3_UPLOADING  | 存储分片     | 清理已存储分片   |
| S3_UPLOADED   | 分片存储完成 | 删除 S3 文件     |
| CHAIN_STORING | 区块链存证   | 标记链上记录删除 |
| COMPLETED     | 提交         | -                |

**补偿策略**：指数退避重试（初始 1s，最多 5 次），失败后进入人工处理队列。

### Saga 状态机

`FileSagaOrchestrator` 管理完整的状态机：

```mermaid
stateDiagram-v2
    [*] --> PENDING: 初始化
    PENDING --> S3_UPLOADING: 开始上传
    S3_UPLOADING --> S3_UPLOADED: 分片存储完成
    S3_UPLOADING --> FAILED: 存储错误
    S3_UPLOADED --> CHAIN_STORING: 开始存证
    CHAIN_STORING --> COMPLETED: 交易确认
    CHAIN_STORING --> FAILED: 链上错误
    FAILED --> COMPENSATING: 触发补偿
    COMPENSATING --> COMPENSATED: 清理完成
```

## 事务性 Outbox 模式

RecordPlatform 使用 Outbox 模式实现到 RabbitMQ 的可靠事件发布。

### 工作原理

```mermaid
flowchart LR
    classDef service fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff
    classDef db fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff
    classDef mq fill:#f97316,stroke:#ea580c,stroke-width:2px,color:#ffffff

    Service["业务<br/>服务"]:::service
    Outbox[("Outbox<br/>表")]:::db
    Publisher["Outbox<br/>发布器"]:::service
    MQ["RabbitMQ"]:::mq

    Service -->|1. 同事务保存事件| Outbox
    Publisher -->|2. 轮询待发送事件| Outbox
    Publisher -->|3. 发布事件| MQ
    Publisher -->|4. 标记已发送| Outbox
```

### 组件

| 组件 | 职责 |
|------|------|
| `OutboxService` | 在业务事务中追加事件 |
| `OutboxPublisher` | 后台轮询和发布（2 秒间隔）|
| `outbox_event` 表 | 带租户隔离的持久化事件存储 |

### 保证

- **至少一次投递**：事件在消息队列不可用时仍能存活
- **事务一致性**：事件在同一数据库事务中与业务数据一起创建
- **租户感知轮询**：每个租户的事件独立处理

### 配置

```yaml
outbox:
  publisher:
    batch-size: 100
    poll-interval-ms: 2000
    max-retries: 5
  cleanup:
    sent-retention-days: 7
    failed-retention-days: 30
    cron: 0 0 3 * * ?
```

## CQRS 架构

文件模块采用命令查询职责分离：

```mermaid
flowchart LR
    %% Styles %%
    classDef cmd fill:#ef4444,stroke:#dc2626,stroke-width:2px,color:#ffffff
    classDef qry fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff
    classDef db fill:#64748b,stroke:#475569,stroke-width:2px,color:#ffffff

    subgraph Commands["命令端 (写路径)"]
        direction TB
        C1(上传文件):::cmd
        C2(删除文件):::cmd
        C3(生成分享):::cmd
        C4(取消分享):::cmd
    end

    subgraph Queries["查询端 (读路径)"]
        direction TB
        Q1(文件列表):::qry
        Q2(文件详情):::qry
        Q3(分享信息):::qry
        Q4(统计数据):::qry
    end

    subgraph Store["数据存储"]
        DB[("MySQL 主库")]:::db
        Cache[("Redis/Caffeine 缓存")]:::db
    end

    %% Wiring %%
    Commands -->|写入| DB
    DB -.->|Binlog 同步 / 事件| Cache
    Queries -->|读取| Cache
    Queries -.->|回源| DB
```

### Virtual Thread 异步方法

查询服务使用 Java 21 Virtual Thread 提供异步方法：

- `getUserFilesListAsync()`
- `getFileAddressAsync()`
- `getFileDecryptInfoAsync()`

## 多租户

### 隔离策略

| 层级    | 隔离方式                           |
| ------- | ---------------------------------- |
| 数据库  | `tenant_id` 字段，MyBatis 自动注入 |
| Redis   | Key 前缀 `tenant:{tenantId}:`      |
| S3 存储 | 路径 `/{tenantId}/{userId}/`       |
| Dubbo   | Context 透传 `TenantContext`       |

### 租户上下文控制

`@TenantScope` 注解用于声明式租户隔离：

```java
// 跨租户查询（定时任务）
@TenantScope(ignoreIsolation = true)
@Scheduled(cron = "0 0 3 * * ?")
public void cleanupDeletedFiles() { ... }

// 切换到指定租户
@TenantScope(tenantId = 1)
public void migrateDataForTenant() { ... }
```

## 实时通知（SSE）

服务器推送事件（Server-Sent Events）为连接的客户端提供实时更新。

### 多连接架构

系统支持同一用户的多个同时连接：

```mermaid
flowchart LR
    classDef browser fill:#f97316,stroke:#ea580c,stroke-width:2px,color:#ffffff
    classDef server fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff

    subgraph User["用户设备"]
        Tab1["浏览器标签页 1"]:::browser
        Tab2["浏览器标签页 2"]:::browser
        Mobile["移动应用"]:::browser
    end

    subgraph Backend["SSE 管理器"]
        Manager["连接管理器"]:::server
        Emitter1["SseEmitter 1"]:::server
        Emitter2["SseEmitter 2"]:::server
        Emitter3["SseEmitter 3"]:::server
    end

    Tab1 -->|connectionId: abc| Emitter1
    Tab2 -->|connectionId: def| Emitter2
    Mobile -->|connectionId: ghi| Emitter3

    Manager --> Emitter1 & Emitter2 & Emitter3
```

### 连接配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 每用户最大连接数 | 5 | 超出时关闭最旧连接 |
| 心跳间隔 | 30 秒 | 保活信号 |
| 连接超时 | 30 分钟 | 无活动后自动关闭 |
| 重连延迟（基础） | 2 秒 | 客户端指数退避重连基础间隔 |
| 重连延迟（上限） | 30 秒 | 客户端指数退避重连上限 |
| 最大重连次数 | 5 | 达到上限后转为手动重连 |

### 事件类型

| 事件 | 载荷 | 说明 |
|------|------|------|
| `connected` | `{ connectionId }` | 连接确认 |
| `notification` | `{ title, content }` | 通用通知 |
| `message-received` | `{ conversationId, preview }` | 会话新消息 |
| `file-processed` | `{ fileId, status }` | 文件上传/处理完成 |
| `announcement-published` | `{ id, title }` | 系统公告 |
| `ticket-updated` | `{ ticketId, status }` | 工单状态变更 |
| `badge-update` | `{ unreadMessages, tickets }` | UI 徽章数量更新 |
| `friend-request` | `{ requesterName, ... }` | 新好友请求 |
| `friend-accepted` | `{ friendName, ... }` | 好友请求被接受 |
| `friend-share` | `{ sharerName, fileCount, ... }` | 好友文件分享 |
| `audit-alert` | `{ type, message, details, severity }` | 审计异常告警（管理员/监控员） |

### SSE 认证握手

SSE 连接采用短期一次性令牌：

1. 登录态下调用 `POST /api/v1/auth/sse-token` 获取短期令牌（需常规 JWT）
2. 使用 `GET /api/v1/sse/connect?token={sseToken}&connectionId={optional}` 建立连接

> `GET /api/v1/sse/connect` 为公开端点，但依赖短期令牌完成认证；不是匿名开放连接。

### 下载策略（前端）

前端按文件大小和浏览器能力动态选择下载策略：

- 小文件：内存下载（in-memory）
- 大文件：优先流式下载（streaming）
- 超大文件或浏览器能力不足：后端代理或阻断并提示

默认阈值（`platform-frontend/src/lib/utils/fileSize.ts`）：

| 阈值常量 | 默认值 |
|----------|--------|
| `LARGE_FILE_WARNING_THRESHOLD` | 500MB |
| `STREAMING_RECOMMENDED_THRESHOLD` | 1GB |
| `MAX_SAFE_INMEMORY_SIZE` | 2GB |
| `MAX_DOWNLOADABLE_SIZE` | 100GB |

### 前端 Leader 选举

对于多标签页场景，前端使用 `BroadcastChannel` 进行 Leader 选举：

- **Leader 标签页**：维护单一 SSE 连接
- **Follower 标签页**：通过 BroadcastChannel 接收事件
- **故障转移**：Leader 标签页关闭时自动选举新 Leader

这可以防止同一浏览器建立多个 SSE 连接，减少服务器负载。
