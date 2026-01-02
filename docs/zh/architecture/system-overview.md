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

- `FiscoExternalService` - 区块链操作
- `StorageExternalService` - 存储操作
- 公共 DTO 和响应类型

### platform-backend

多模块后端服务（Dubbo Consumer）：

| 子模块              | 职责                                |
| ------------------- | ----------------------------------- |
| **backend-web**     | REST 控制器、JWT 过滤器、限流、CORS |
| **backend-service** | 业务逻辑、Saga 编排、Outbox 发布    |
| **backend-dao**     | MyBatis Plus 映射、实体、VO         |
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
    Client->>Backend: POST /files/init (Hash, Size)
    Backend->>Backend: 校验配额 & 文件是否存在
    Backend-->>Client: 200 OK (UploadID, ChunkSize)

    Note over Client, S3: 阶段 2: 分片上传
    loop 每个分片
        Client->>Backend: PUT /files/chunk/{index}
        Backend->>Storage: RPC: storeChunk()
        Storage->>S3: PutObject (加密)
        S3-->>Storage: 成功
        Storage-->>Backend: 分片哈希
        Backend-->>Client: 200 OK
    end

    Note over Client, Chain: 阶段 3: 存证 & 确认
    Client->>Backend: POST /files/finish
    Backend->>Backend: 合并分片元数据
    Backend->>Chain: 异步 Transaction (Saga)
    Chain-->>Backend: TxHash
    Backend-->>Client: 200 OK (FileID)

    Note right of Backend: 通过 SSE 推送最终链上状态
    Backend--)Client: SSE: UPLOAD_COMPLETED
```

### Saga 补偿流程

| 步骤          | 正向操作     | 补偿操作         |
| ------------- | ------------ | ---------------- |
| PENDING       | 初始化       | -                |
| S3_UPLOADING  | 存储分片     | 清理已存储分片   |
| S3_UPLOADED   | 分片存储完成 | 删除 S3 文件     |
| CHAIN_STORING | 区块链存证   | 标记链上记录删除 |
| COMPLETED     | 提交         | -                |

**补偿策略**：指数退避重试（初始 1s，最多 5 次），失败后进入人工处理队列。

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
