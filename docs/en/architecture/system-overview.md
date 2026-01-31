# System Overview

## Architecture Diagram

```mermaid
flowchart TB
    %% Style Definitions %%
    classDef service fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff
    classDef infra fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff
    classDef chain fill:#8b5cf6,stroke:#7c3aed,stroke-width:2px,color:#ffffff
    classDef client fill:#f97316,stroke:#ea580c,stroke-width:2px,color:#ffffff

    subgraph ClientLayer["Client Layer"]
        Frontend["Frontend App<br/>(Web/H5/App)"]:::client
    end

    subgraph ServiceLayer["Microservices (Dubbo)"]
        direction TB
        Backend["platform-backend<br/>(API Gateway & Consumer)"]:::service
        Fisco["platform-fisco<br/>(Blockchain Provider)"]:::service
        Storage["platform-storage<br/>(Storage Provider)"]:::service
    end

    subgraph InfraLayer["Infrastructure"]
        direction TB
        Nacos["Nacos<br/>(Registry & Config)"]:::infra
        MySQL[("MySQL<br/>(Business Data)")]:::infra
        Redis[("Redis<br/>(Cache)")]:::infra
        RabbitMQ["RabbitMQ<br/>(Async Messaging)"]:::infra
    end

    subgraph ExternalLayer["External Dependencies"]
        S3["S3 Object Storage"]:::infra
        BCOS["FISCO BCOS Node"]:::chain
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
    Backend -.->|Register/Discover| Nacos
    Fisco -.->|Register| Nacos
    Storage -.->|Register| Nacos

    %% Service to External %%
    Fisco -->|Channel/RPC| BCOS
    Storage -->|S3 API| S3
```

## Module Responsibilities

### platform-api

Shared Dubbo interface definitions that all modules depend on:

- `BlockChainService` - Blockchain operations
- `DistributedStorageService` - Storage operations
- Common DTOs and response types

### platform-backend

Multi-module backend service (Dubbo Consumer):

| Submodule           | Responsibility                                        |
| ------------------- | ----------------------------------------------------- |
| **backend-web**     | REST controllers, JWT filters, rate limiting, CORS    |
| **backend-service** | Business logic, Saga orchestration, Outbox publishing |
| **backend-dao**     | MyBatis Plus mappers, entities, VOs                   |
| **backend-api**     | Internal API interfaces                               |
| **backend-common**  | Utilities, constants, annotations                     |

### platform-fisco

Blockchain integration service (Dubbo Provider):

- Smart contract interaction (Storage.sol, Sharing.sol)
- Multi-chain adapters (Local FISCO, BSN FISCO, Besu)
- Certificate management

### platform-storage

Distributed storage service (Dubbo Provider):

- Multi-node S3 client management
- Fault domain management
- Consistent hashing and rebalancing
- File encryption/decryption

## Core Business Flow

### File Upload & Attestation

```mermaid
sequenceDiagram
    participant Client
    participant Backend
    participant S3 as S3 Storage
    participant Chain as Blockchain

    Client->>Backend: 1. Start upload
    Backend-->>Client: Upload session

    Client->>Backend: 2. Upload chunk
    Backend->>S3: Encrypt & Write A+B
    Backend-->>Client: Chunk ACK

    Client->>Backend: 3. Complete
    Backend->>Chain: Saga Transaction
    Chain->>Chain: Store metadata
    Chain-->>Backend: TX hash
    Backend-->>Client: File record

    Backend-->>Client: SSE: status update
```

### File Download Flow

```mermaid
sequenceDiagram
    autonumber

    participant Client
    participant Backend
    participant Storage as platform-storage
    participant S3 as S3 Cluster

    Note over Client, Backend: Phase 1: Get Download Info
    Client->>Backend: GET /api/v1/files/address
    Backend->>Backend: Verify permissions & get file metadata
    Backend->>Storage: RPC: generatePresignedUrls()
    Storage->>S3: Generate presigned URLs
    S3-->>Storage: Presigned URL list
    Storage-->>Backend: URLs + Decrypt key chain
    Backend-->>Client: 200 OK (URLs, ChunkInfo)

    Note over Client, Backend: Phase 1.5: Get Decrypt Info
    Client->>Backend: GET /api/v1/files/decryptInfo
    Backend-->>Client: 200 OK (DecryptKeys)

    Note over Client, S3: Phase 2: Concurrent Chunk Download
    par Concurrent downloads
        Client->>S3: GET presigned URL (chunk 1)
        S3-->>Client: Encrypted chunk data
    and
        Client->>S3: GET presigned URL (chunk 2)
        S3-->>Client: Encrypted chunk data
    and
        Client->>S3: GET presigned URL (chunk N)
        S3-->>Client: Encrypted chunk data
    end

    Note over Client: Phase 3: Decrypt & Assemble
    Client->>Client: Decrypt chunks in key chain order
    Client->>Client: Merge chunks & trigger browser download
```

**Download Strategy Comparison**:

| Strategy | Use Case | Characteristics |
|----------|----------|-----------------|
| **Memory Mode** | Small files (< 50MB) | Load all chunks into memory then decrypt, fast |
| **Streaming Mode** | Large files (≥ 50MB) | Uses StreamSaver.js, download while writing, low memory |
| **Backend Proxy** | Special scenarios | Backend proxies download, for environments without direct S3 access |

**Key Chain Decryption**: Each chunk is encrypted with an independent key. During download, keys are matched by `chunkIndex` order for decryption.

### File Sharing Flow

#### Link Sharing

```mermaid
sequenceDiagram
    autonumber

    participant Owner as File Owner
    participant Backend
    participant Chain as FISCO BCOS
    participant DB as MySQL
    participant Visitor

    Note over Owner, Chain: Phase 1: Generate Share
    Owner->>Backend: POST /api/v1/files/share
    Backend->>Chain: RPC: generateSharingCode()
    Chain->>Chain: Generate share code & store metadata
    Chain-->>Backend: ShareCode
    Backend->>DB: Sync share record
    Backend-->>Owner: 200 OK (ShareCode, ShareUrl)

    Note over Visitor, DB: Phase 2: Access Share
    Visitor->>Backend: GET /api/v1/share/{shareCode}/info
    Backend->>DB: Query share record
    alt Database hit
        DB-->>Backend: Share info
    else Database miss
        Backend->>Chain: RPC: querySharingInfo()
        Chain-->>Backend: Share info
    end
    Backend->>Backend: Validate expiry & access password
    Backend->>DB: Log access
    Backend-->>Visitor: 200 OK (FileInfo)
```

#### Friend Sharing

```mermaid
sequenceDiagram
    autonumber

    participant Owner as File Owner
    participant Backend
    participant DB as MySQL
    participant SSE as SSE Manager
    participant Friend

    Note over Owner, DB: Phase 1: Initiate Share
    Owner->>Backend: POST /api/v1/friend-shares
    Backend->>DB: Verify friendship
    Backend->>DB: Create friend share record
    Backend->>SSE: Push share notification
    SSE--)Friend: SSE: friend-file-shared
    Backend-->>Owner: 200 OK

    Note over Friend, DB: Phase 2: View Shares
    Friend->>Backend: GET /api/v1/friend-shares/received
    Backend->>DB: Query received shares
    Backend->>DB: Update read status
    Backend-->>Friend: 200 OK (SharedFiles)

    Note over Friend, Backend: Phase 3: Download Shared File
    Friend->>Backend: GET /api/v1/files/address (with shared file hash)
    Backend->>Backend: Query file using original uploader ID
    Backend-->>Friend: 200 OK (DownloadInfo)
```

**Share Type Comparison**:

| Type | Access Control | Expiry | Characteristics |
|------|----------------|--------|-----------------|
| **Public Share** | None | Configurable | Anyone can access via link |
| **Private Share** | Password | Configurable | Password required for access |
| **Friend Share** | Friendship | Permanent | Only specified friend can view, supports read status |

### Saga Compensation Flow

| Step          | Forward Action         | Compensation              |
| ------------- | ---------------------- | ------------------------- |
| PENDING       | Initialize             | -                         |
| S3_UPLOADING  | Store chunks           | Clean stored chunks       |
| S3_UPLOADED   | Chunks stored          | Delete S3 files           |
| CHAIN_STORING | Blockchain attestation | Mark chain record deleted |
| COMPLETED     | Commit                 | -                         |

**Compensation Strategy**: Exponential backoff (initial 1s, max 5 retries), then manual queue.

### Saga State Machine

The `FileSagaOrchestrator` manages the complete state machine:

```mermaid
stateDiagram-v2
    [*] --> PENDING: Initialize
    PENDING --> S3_UPLOADING: Start upload
    S3_UPLOADING --> S3_UPLOADED: Chunks stored
    S3_UPLOADING --> FAILED: Storage error
    S3_UPLOADED --> CHAIN_STORING: Start attestation
    CHAIN_STORING --> COMPLETED: TX confirmed
    CHAIN_STORING --> FAILED: Chain error
    FAILED --> COMPENSATING: Trigger compensation
    COMPENSATING --> COMPENSATED: Cleanup done
```

## Transactional Outbox Pattern

RecordPlatform uses the Outbox pattern for reliable event publishing to RabbitMQ.

### How It Works

```mermaid
flowchart LR
    classDef service fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff
    classDef db fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff
    classDef mq fill:#f97316,stroke:#ea580c,stroke-width:2px,color:#ffffff

    Service["Business<br/>Service"]:::service
    Outbox[("Outbox<br/>Table")]:::db
    Publisher["Outbox<br/>Publisher"]:::service
    MQ["RabbitMQ"]:::mq

    Service -->|1. Save event in same TX| Outbox
    Publisher -->|2. Poll pending events| Outbox
    Publisher -->|3. Publish event| MQ
    Publisher -->|4. Mark delivered| Outbox
```

### Components

| Component | Responsibility |
|-----------|----------------|
| `OutboxService` | Appends events within business transaction |
| `OutboxPublisher` | Background polling and publishing (30s interval) |
| `outbox_event` table | Persistent event store with tenant isolation |

### Guarantees

- **At-least-once delivery**: Events survive broker unavailability
- **Transactional consistency**: Event created in same DB transaction as business data
- **Tenant-aware polling**: Each tenant's events processed independently

### Configuration

```yaml
outbox:
  enabled: true
  poll-interval: 30s
  batch-size: 100
  retention-days: 7  # Auto-cleanup after 7 days
```

## CQRS Architecture

File module uses Command Query Responsibility Segregation:

```mermaid
flowchart LR
    %% Styles %%
    classDef cmd fill:#ef4444,stroke:#dc2626,stroke-width:2px,color:#ffffff
    classDef qry fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff
    classDef db fill:#64748b,stroke:#475569,stroke-width:2px,color:#ffffff

    subgraph Command["Command Side (Write Path)"]
        direction TB
        C1(storeFile):::cmd
        C2(deleteFile):::cmd
        C3(generateSharingCode):::cmd
        C4(cancelShare):::cmd
    end

    subgraph Query["Query Side (Read Path)"]
        direction TB
        Q1(getUserFilesList):::qry
        Q2(getFileAddress):::qry
        Q3(getTransactionByHash):::qry
        Q4(getFileDecryptInfo):::qry
    end

    subgraph Store["Data Store"]
        DB[("MySQL Primary")]:::db
        Cache[("Redis/Caffeine Cache")]:::db
    end

    %% Wiring %%
    Command -->|Write| DB
    DB -.->|Binlog Sync / Event| Cache
    Query -->|Read| Cache
    Query -.->|Fallback| DB
```

### Virtual Thread Async Methods

Query service provides async methods using Java 21 Virtual Threads:

- `getUserFilesListAsync()`
- `getFileAddressAsync()`
- `getFileDecryptInfoAsync()`

## Multi-tenancy

### Isolation Strategy

| Layer      | Isolation Method                       |
| ---------- | -------------------------------------- |
| Database   | `tenant_id` field, MyBatis auto-inject |
| Redis      | Key prefix `tenant:{tenantId}:`        |
| S3 Storage | Path `/{tenantId}/{userId}/`           |
| Dubbo      | Context propagation `TenantContext`    |

### Tenant Context Control

`@TenantScope` annotation for declarative tenant isolation:

```java
// Cross-tenant query (scheduled tasks)
@TenantScope(ignoreIsolation = true)
@Scheduled(cron = "0 0 3 * * ?")
public void cleanupDeletedFiles() { ... }

// Switch to specific tenant
@TenantScope(tenantId = 1)
public void migrateDataForTenant() { ... }
```

## Real-time Notifications (SSE)

Server-Sent Events provide real-time updates to connected clients.

### Multi-Connection Architecture

The system supports multiple simultaneous connections per user:

```mermaid
flowchart LR
    classDef browser fill:#f97316,stroke:#ea580c,stroke-width:2px,color:#ffffff
    classDef server fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff

    subgraph User["User's Devices"]
        Tab1["Browser Tab 1"]:::browser
        Tab2["Browser Tab 2"]:::browser
        Mobile["Mobile App"]:::browser
    end

    subgraph Backend["SSE Manager"]
        Manager["Connection Manager"]:::server
        Emitter1["SseEmitter 1"]:::server
        Emitter2["SseEmitter 2"]:::server
        Emitter3["SseEmitter 3"]:::server
    end

    Tab1 -->|connectionId: abc| Emitter1
    Tab2 -->|connectionId: def| Emitter2
    Mobile -->|connectionId: ghi| Emitter3

    Manager --> Emitter1 & Emitter2 & Emitter3
```

### Connection Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| Max connections per user | 5 | Oldest connection closed when exceeded |
| Heartbeat interval | 30s | Keep-alive signal |
| Connection timeout | 30m | Auto-close after inactivity |
| Reconnect delay | 1s | Client reconnect backoff |

### Event Types

| Event | Payload | Description |
|-------|---------|-------------|
| `connected` | `{ connectionId }` | Initial connection confirmation |
| `notification` | `{ title, content }` | General notification |
| `message-received` | `{ conversationId, preview }` | New message in conversation |
| `file-processed` | `{ fileId, status }` | File upload/processing complete |
| `announcement-published` | `{ id, title }` | System announcement |
| `ticket-updated` | `{ ticketId, status }` | Ticket status change |
| `badge-update` | `{ unreadMessages, tickets }` | UI badge count update |

### Frontend Leader Election

For multi-tab scenarios, frontend uses `BroadcastChannel` for leader election:

- **Leader tab**: Maintains single SSE connection
- **Follower tabs**: Receive events via BroadcastChannel
- **Failover**: Auto-elect new leader when leader tab closes

This prevents multiple SSE connections from same browser, reducing server load.
