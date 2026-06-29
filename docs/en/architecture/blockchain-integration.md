# Blockchain Integration

RecordPlatform uses FISCO BCOS blockchain for immutable file attestation.

## Smart Contracts

### Storage.sol

Stores and manages file metadata on-chain.

| Method       | Parameters         | Description            |
| ------------ | ------------------ | ---------------------- |
| `storeFile`  | fileHash, metadata | Store file attestation |
| `getFile`    | fileHash           | Query file metadata    |
| `deleteFile` | fileHash           | Mark file as deleted   |
| `exists`     | fileHash           | Check if file exists   |

### Sharing.sol

Manages file sharing with access control.

| Method              | Parameters                            | Description                         |
| ------------------- | ------------------------------------- | ----------------------------------- |
| `shareFiles`        | uploader, fileHashes[], expireMinutes | Create share, returns 6-char code   |
| `getSharedFiles`    | shareCode                             | Get shared files (validates expiry) |
| `cancelShare`       | shareCode                             | Cancel share (isValid=false)        |
| `getUserShareCodes` | uploader                              | Get all share codes for user        |
| `getShareInfo`      | shareCode                             | Get share details (no validation)   |

### Contract Events

| Event            | Parameters                                    | Trigger         |
| ---------------- | --------------------------------------------- | --------------- |
| `FileShared`     | shareCode, uploader, fileHashes[], expireTime | Share created   |
| `ShareCancelled` | shareCode, uploader                           | Share cancelled |

## Multi-Chain Adapters

RecordPlatform supports multiple blockchain networks through an adapter pattern.

### Supported Chains

| Chain            | Config Value  | Description                      |
| ---------------- | ------------- | -------------------------------- |
| Local FISCO      | `local-fisco` | Local FISCO BCOS node (default)  |
| BSN FISCO        | `bsn-fisco`   | Blockchain Service Network FISCO |
| Hyperledger Besu | `bsn-besu`    | EVM-compatible Besu network      |

### Configuration

```yaml
blockchain:
  active: ${BLOCKCHAIN_ACTIVE:local-fisco}

  # BSN FISCO BCOS (active=bsn-fisco)
  bsn-fisco:
    node-id: <bsn-node-id>
    peers:
      - <peer-address>

  # Hyperledger Besu (active=bsn-besu)
  bsn-besu:
    rpc-url: https://<besu-rpc>
    chain-id: <chain-id>

# Local FISCO BCOS (Java SDK)
bcos:
  network:
    peers[0]: ${FISCO_PEER_ADDRESS:127.0.0.1:20200}

# Contract addresses (used by local-fisco and bsn)
contract:
  storageAddress: ${FISCO_STORAGE_CONTRACT:}
  sharingAddress: ${FISCO_SHARING_CONTRACT:}
```

### Adapter Architecture

```mermaid
flowchart TB
    %% Styles %%
    classDef interface fill:#3b82f6,stroke:#2563eb,stroke-width:2px,color:#ffffff
    classDef adapter fill:#8b5cf6,stroke:#7c3aed,stroke-width:2px,color:#ffffff
    classDef sdk fill:#10b981,stroke:#059669,stroke-width:2px,color:#ffffff

    subgraph API["Core Interface"]
        Interface([BlockChainService]):::interface
    end

    subgraph Impls["Adapter Implementations"]
        LocalAdapter["LocalFiscoAdapter<br/>(Standard)"]:::adapter
        BsnAdapter["BsnFiscoAdapter<br/>(BSN SaaS)"]:::adapter
        BesuAdapter["BsnBesuAdapter<br/>(EVM Compat)"]:::adapter
    end

    subgraph SDKs["Underlying SDKs"]
        FiscoSDK["FISCO Java SDK"]:::sdk
        BsnSDK["BSN PCN Gateway"]:::sdk
        Web3j["Web3j"]:::sdk
    end

    Interface --> LocalAdapter
    Interface --> BsnAdapter
    Interface --> BesuAdapter

    LocalAdapter --> FiscoSDK
    BsnAdapter --> BsnSDK
    BesuAdapter --> Web3j
```

### BlockChainAdapter Interface

All adapters implement the `BlockChainAdapter` interface:

```java
public interface BlockChainAdapter {
    // File operations
    Result<StoreFileResponse> storeFile(StoreFileRequest request);
    Result<List<FileVO>> getUserFiles(String uploader);
    Result<FileDetailVO> getFile(String uploader, String fileHash);
    Result<Boolean> deleteFiles(DeleteFilesRequest request);
    
    // Sharing operations
    Result<String> shareFiles(ShareFilesRequest request);
    Result<SharingVO> getSharedFiles(String shareCode);
    Result<Boolean> cancelShare(CancelShareRequest request);
    
    // Chain status
    Result<ChainStatusVO> getCurrentBlockChainMessage();
    Result<TransactionVO> getTransactionByHash(String txHash);
}
```

### Adapter Selection

Adapter selection is controlled by the `blockchain.active` configuration:

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

## Certificate Management

### FISCO BCOS Certificates

Place certificates in `platform-fisco/src/main/resources/conf/`:

```txt
conf/
├── ca.crt        # CA certificate
├── sdk.crt       # SDK certificate
└── sdk.key       # SDK private key
```

### BSN Configuration

BSN networks require additional authentication:

- Node ID from BSN portal
- API key/secret for BSN gateway

## Transaction Flow

### File Attestation

```mermaid
sequenceDiagram
    participant Backend
    participant Fisco as FISCO Service
    participant Chain as Blockchain

    Backend->>Fisco: storeFile(hash, meta)
    Fisco->>Chain: Storage.storeFile()
    Chain-->>Fisco: Transaction hash
    Fisco-->>Backend: txHash
```

### Merkle Batch Foundation

P1 introduces a backend-side Merkle batch foundation for exportable proof metadata:

1. `AttestationBatchService` loads successful file records for the current tenant.
2. `MerkleTreeService` canonicalizes file hashes, calculates one Merkle root, and stores each leaf proof path.
3. `attestation_batch` and `attestation_leaf` persist the batch root, proof algorithm, leaf hash, leaf index, and proof path JSON.
4. `FileRemoteClient.storeAttestationBatch` writes the batch root through the dedicated authenticated `storeAttestationBatch` RPC and Sharing contract method.

The batch root chain write is intentionally separate from ordinary file attestation. It does not create `Storage.storeFile` records, so Merkle batch roots are not returned as user file entries by file-listing contract reads.

### Proof Bundle Export

P1-2 exports verifier-ready JSON proof bundles from the persisted Merkle batch data:

- `GET /api/v1/files/{id}/proof-bundle` exports by external file ID.
- `GET /api/v1/files/attestation-leaves/{leafId}/proof-bundle` exports by external attestation leaf ID.
- `ProofBundleService` performs tenant and owner checks before reading file, batch, leaf, storage HEAD, and chain receipt metadata.
- `ProofBundleVO` is the stable v1 contract for P1-3 verifier input.

The bundle intentionally includes public proof inputs only:

- file name, file hash, file size, content type, version, and file transaction hash
- storage object path and HEAD metadata
- Merkle root, leaf hash, leaf index, proof algorithm, and proof path
- batch transaction hash and batch chain file hash
- issuer and verification policy metadata

The bundle does not include raw file bytes, decrypt keys, RPC tokens, raw database-only IDs, or full `file_param`. The v1 bundle is unsigned; `issuer.signatureAlgorithm` and `issuer.signature` are reserved for a later signing phase.

### Independent Proof Verifier

P1-3 adds an offline verifier boundary for the `proof-bundle.v1` contract:

- `ProofBundleVerifier.verify(byte[] originalFile, ProofBundleVO bundle)` validates a parsed bundle without backend session state.
- `ProofBundleVerifier.verify(byte[] originalFile, String bundleJson)` parses exported JSON and returns the same structured result.
- `ProofVerificationResult` reports `valid`, machine-readable issue codes, computed file hash, computed leaf hash, computed Merkle root, chain receipt fields, and issuer status.

The verifier checks:

- SHA-256 of the original file against `file.fileHash`
- `merkle.proofAlgorithm` against `SHA-256-MERKLE-V1`
- `merkle.leafHash` from the public `leaf\n{fileHash}` rule
- `merkle.proofPath` from leaf to `merkle.merkleRoot`
- `chain.batchChainFileHash` against the Merkle root when present
- issuer batch status and storage metadata mismatch flags

The verifier does not call platform APIs, query the database, read tenant context, or authenticate to FISCO. Direct transaction receipt validation remains a separate online verification step until a public chain gateway or signed receipt contract is introduced.

### Transaction Verification

Query blockchain for attestation proof:

```java
// Get transaction by hash
TransactionReceipt receipt = fiscoService.getTransactionByHash(txHash);

// Verify file exists on-chain
boolean exists = fiscoService.fileExists(fileHash);
```

## Resilience

### Circuit Breaker

```yaml
resilience4j:
  circuitbreaker:
    instances:
      blockChainService:
        sliding-window-size: 50
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

### Retry Policy

```yaml
resilience4j:
  retry:
    instances:
      blockChainService:
        max-attempts: 3
        wait-duration: 2s
        exponential-backoff-multiplier: 2
```

### Fallback Behavior

When blockchain service is unavailable:

1. Saga marks step as CHAIN_STORING
2. File storage continues (degraded mode)
3. Background job retries attestation
4. User notified when attestation succeeds

## Gas and Performance

### Optimization Tips

- Batch multiple files in single share transaction
- Use events for state queries (cheaper than storage reads)
- Cache frequently accessed on-chain data in Redis

### Typical Gas Costs

| Operation            | Approximate Gas |
| -------------------- | --------------- |
| storeFile            | ~50,000         |
| shareFiles (5 files) | ~100,000        |
| cancelShare          | ~30,000         |
