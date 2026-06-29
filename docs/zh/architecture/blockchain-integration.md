# 区块链集成

RecordPlatform 使用 FISCO BCOS 区块链实现不可篡改的文件存证。

## 智能合约

### Storage.sol

在链上存储和管理文件元数据。

| 方法         | 参数               | 说明             |
| ------------ | ------------------ | ---------------- |
| `storeFile`  | fileHash, metadata | 存储文件存证     |
| `getFile`    | fileHash           | 查询文件元数据   |
| `deleteFile` | fileHash           | 标记文件删除     |
| `exists`     | fileHash           | 检查文件是否存在 |

### Sharing.sol

管理带访问控制的文件分享。

| 方法                | 参数                                  | 说明                         |
| ------------------- | ------------------------------------- | ---------------------------- |
| `shareFiles`        | uploader, fileHashes[], expireMinutes | 创建分享，返回 6 位分享码    |
| `getSharedFiles`    | shareCode                             | 获取分享文件（校验有效期）   |
| `cancelShare`       | shareCode                             | 取消分享（isValid=false）    |
| `getUserShareCodes` | uploader                              | 获取用户所有分享码           |
| `getShareInfo`      | shareCode                             | 获取分享详情（不校验有效性） |

### 合约事件

| 事件             | 参数                                          | 触发时机   |
| ---------------- | --------------------------------------------- | ---------- |
| `FileShared`     | shareCode, uploader, fileHashes[], expireTime | 创建分享时 |
| `ShareCancelled` | shareCode, uploader                           | 取消分享时 |

## 多链适配器

RecordPlatform 通过适配器模式支持多种区块链网络。

### 支持的链

| 链               | 配置值        | 说明                         |
| ---------------- | ------------- | ---------------------------- |
| 本地 FISCO       | `local-fisco` | 本地 FISCO BCOS 节点（默认） |
| BSN FISCO        | `bsn-fisco`   | 区块链服务网络 FISCO         |
| Hyperledger Besu | `bsn-besu`    | EVM 兼容的 Besu 网络         |

### 配置

```yaml
blockchain:
  active: ${BLOCKCHAIN_ACTIVE:local-fisco}

  # BSN FISCO BCOS（active=bsn-fisco）
  bsn-fisco:
    node-id: <bsn-node-id>
    peers:
      - <peer-address>

  # Hyperledger Besu（active=bsn-besu）
  bsn-besu:
    rpc-url: https://<besu-rpc>
    chain-id: <chain-id>

# 本地 FISCO BCOS（Java SDK）
bcos:
  network:
    peers[0]: ${FISCO_PEER_ADDRESS:127.0.0.1:20200}

# 合约地址（local-fisco 与 bsn 共用）
contract:
  storageAddress: ${FISCO_STORAGE_CONTRACT:}
  sharingAddress: ${FISCO_SHARING_CONTRACT:}
```

### 适配器架构

```mermaid
flowchart TB
    %% Styles %%
    classDef interface fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff
    classDef adapter fill:#8b5cf6,stroke:#7c3aed,stroke-width:2px,color:#ffffff
    classDef sdk fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff

    subgraph API["核心接口"]
        Interface([BlockChainService]):::interface
    end

    subgraph Impls["适配器实现"]
        LocalAdapter["LocalFiscoAdapter<br/>(标准模式)"]:::adapter
        BsnAdapter["BsnFiscoAdapter<br/>(BSN SaaS)"]:::adapter
        BesuAdapter["BsnBesuAdapter<br/>(EVM 兼容)"]:::adapter
    end

    subgraph SDKs["底层 SDK"]
        FiscoSDK["FISCO Java SDK"]:::sdk
        BsnSDK["BSN PCN 网关"]:::sdk
        Web3j["Web3j"]:::sdk
    end

    Interface --> LocalAdapter
    Interface --> BsnAdapter
    Interface --> BesuAdapter

    LocalAdapter --> FiscoSDK
    BsnAdapter --> BsnSDK
    BesuAdapter --> Web3j
```

### BlockChainAdapter 接口

所有适配器实现 `BlockChainAdapter` 接口：

```java
public interface BlockChainAdapter {
    // 文件操作
    Result<StoreFileResponse> storeFile(StoreFileRequest request);
    Result<List<FileVO>> getUserFiles(String uploader);
    Result<FileDetailVO> getFile(String uploader, String fileHash);
    Result<Boolean> deleteFiles(DeleteFilesRequest request);
    
    // 分享操作
    Result<String> shareFiles(ShareFilesRequest request);
    Result<SharingVO> getSharedFiles(String shareCode);
    Result<Boolean> cancelShare(CancelShareRequest request);
    
    // 链状态
    Result<ChainStatusVO> getCurrentBlockChainMessage();
    Result<TransactionVO> getTransactionByHash(String txHash);
}
```

### 适配器选择

适配器选择由 `blockchain.active` 配置控制：

```java
@Configuration
public class BlockChainConfig {
    @Bean
    @ConditionalOnProperty(name = "blockchain.active", havingValue = "local-fisco")
    public BlockChainAdapter localFiscoAdapter() { ... }
    
    @Bean
    @ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-fisco")
    public BlockChainAdapter bsnFiscoAdapter() { ... }
    
    @Bean
    @ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-besu")
    public BlockChainAdapter bsnBesuAdapter() { ... }
}
```

## 证书管理

### FISCO BCOS 证书

将证书放置于 `platform-fisco/src/main/resources/conf/`：

```txt
conf/
├── ca.crt        # CA 证书
├── sdk.crt       # SDK 证书
└── sdk.key       # SDK 私钥
```

### BSN 配置

BSN 网络需要额外认证：

- BSN 门户的节点 ID
- BSN 网关的 API 密钥/密码

## 交易流程

### 文件存证

```mermaid
sequenceDiagram
    autonumber

    participant Backend as 后端服务
    participant FiscoService as 区块链服务
    participant Chain as FISCO BCOS 节点

    Backend->>FiscoService: storeFile(hash, metadata)
    activate FiscoService
    FiscoService->>FiscoService: 构建交易 (ABI 编码)
    FiscoService->>Chain: 发送签名交易
    activate Chain
    Chain->>Chain: 执行智能合约
    Chain-->>FiscoService: 交易回执 (TxHash)
    deactivate Chain
    FiscoService-->>Backend: 返回 TxHash
    deactivate FiscoService
```

### Merkle 批量存证基础

当前后端已具备 Merkle 批量存证基础，用于导出可独立验证的证明元数据：

1. `AttestationBatchService` 按当前租户加载成功文件记录。
2. `MerkleTreeService` 规范化文件哈希，计算 Merkle root，并为每个叶子保存 proof path。
3. `attestation_batch` 和 `attestation_leaf` 持久化批次根、证明算法、叶子哈希、叶子索引和 proof path JSON。
4. `FileRemoteClient.storeAttestationBatch` 通过独立的 `storeAttestationBatch` RPC 和 Sharing 合约方法写入批次根。

批次根上链与普通单文件存证分离，不创建 `Storage.storeFile` 文件记录，因此不会被用户文件列表的合约读取当作普通文件返回。

### 证明包导出

当前提供两个 verifier-ready JSON 证明包导出入口：

- `GET /api/v1/files/{id}/proof-bundle`：按文件外部 ID 导出。
- `GET /api/v1/files/attestation-leaves/{leafId}/proof-bundle`：按存证叶子外部 ID 导出。

`ProofBundleService` 会先校验租户和文件所有权，再读取文件、批次、叶子、存储 HEAD 和链上回执元数据。`ProofBundleVO` 是当前 `proof-bundle.v1` 的稳定输入合同。

证明包只包含公开证明输入：

- 文件名、文件哈希、文件大小、内容类型、版本和文件交易哈希；
- 存储对象路径和 HEAD 元数据；
- Merkle root、leaf hash、leaf index、proof algorithm 和 proof path；
- batch transaction hash 和 batch chain file hash；
- issuer 与 verification policy 元数据。

证明包不包含原始文件字节、解密密钥、RPC token、数据库内部 ID 或完整 `file_param`。v1 证明包未签名，`issuer.signatureAlgorithm` 和 `issuer.signature` 预留给后续签名阶段。

### 离线证明校验器

`ProofBundleVerifier` 提供不依赖平台会话和数据库的离线校验边界：

- `verify(byte[] originalFile, ProofBundleVO bundle)` 校验已解析证明包；
- `verify(byte[] originalFile, String bundleJson)` 解析 JSON 后复用同一套校验逻辑；
- `ProofVerificationResult` 返回 `valid`、机器可读 issue code、计算出的 file hash、leaf hash、Merkle root、链上回执字段和 issuer 状态。

校验器会检查：

- `verificationPolicy` 必须包含 `algorithmSuite`、`signatureSuite`、`kemSuite`、`proofSuite`；
- 原始文件 SHA-256 是否匹配 `file.fileHash`；
- `merkle.proofAlgorithm` 是否为 `SHA-256-MERKLE-V1`；
- `merkle.leafHash` 是否符合公开的 `leaf\n{fileHash}` 规则；
- `merkle.proofPath` 是否能从叶子推导到 `merkle.merkleRoot`；
- `chain.batchChainFileHash` 存在时是否等于 Merkle root；
- issuer batch 状态和 storage metadata mismatch 标记。

缺失或不支持的 suite 元数据会返回 `UNSUPPORTED_ALGORITHM`；校验器不会用运行时默认值补齐缺失字段。

### 交易验证

查询区块链获取存证证明：

```java
// 通过哈希获取交易
TransactionReceipt receipt = fiscoService.getTransactionByHash(txHash);

// 验证文件是否在链上存在
boolean exists = fiscoService.fileExists(fileHash);
```

## 弹性设计

### 熔断器

```yaml
resilience4j:
  circuitbreaker:
    instances:
      blockChainService:
        sliding-window-size: 50
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

### 重试策略

```yaml
resilience4j:
  retry:
    instances:
      blockChainService:
        max-attempts: 3
        wait-duration: 2s
        exponential-backoff-multiplier: 2
```

### 降级行为

当区块链服务不可用时：

1. Saga 标记步骤为 CHAIN_STORING
2. 文件存储继续（降级模式）
3. 后台任务重试存证
4. 存证成功后通知用户

## Gas 和性能

### 优化建议

- 在单个分享交易中批量处理多个文件
- 使用事件进行状态查询（比存储读取更便宜）
- 在 Redis 中缓存频繁访问的链上数据

### 典型 Gas 消耗

| 操作                  | 大约 Gas |
| --------------------- | -------- |
| storeFile             | ~50,000  |
| shareFiles (5 个文件) | ~100,000 |
| cancelShare           | ~30,000  |
