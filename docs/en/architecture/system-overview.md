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

- `FiscoExternalService` - Blockchain operations
- `StorageExternalService` - Storage operations
- Common DTOs and response types

### platform-backend

Multi-module backend service (Dubbo Consumer):

| Submodule           | Responsibility                                        |
| ------------------- | ----------------------------------------------------- |
| **backend-web**     | REST controllers, JWT filters, rate limiting, CORS    |
| **backend-service** | Business logic, Saga orchestration, Outbox publishing |
| **backend-dao**     | MyBatis Plus mappers, entities, VOs                   |
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

### Saga Compensation Flow

| Step          | Forward Action         | Compensation              |
| ------------- | ---------------------- | ------------------------- |
| PENDING       | Initialize             | -                         |
| S3_UPLOADING  | Store chunks           | Clean stored chunks       |
| S3_UPLOADED   | Chunks stored          | Delete S3 files           |
| CHAIN_STORING | Blockchain attestation | Mark chain record deleted |
| COMPLETED     | Commit                 | -                         |

**Compensation Strategy**: Exponential backoff (initial 1s, max 5 retries), then manual queue.

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
