# RecordPlatform 当前代码审查与后续演进方案

> 生成日期：2026-06-09
> 审查对象：`SoarCollab/RecordPlatform` 当前默认分支
> 文档定位：代码审查、技术路线对标、后续演进规划
> 当前修订：2026-06-29，已按 `ai/roadmap-code-optimization` 当前代码校正已落地能力。本文仍保留路线图性质，不能替代 `API_DOCUMENTATION.md` 和 `docs/` 下的接口/架构合同。

---

## 目录

- [1. 总体结论](#1-总体结论)
- [2. 当前架构与代码状态](#2-当前架构与代码状态)
  - [2.1 项目定位](#21-项目定位)
  - [2.2 技术栈与模块划分](#22-技术栈与模块划分)
  - [2.3 后端架构](#23-后端架构)
  - [2.4 文件上传与分布式存储链路](#24-文件上传与分布式存储链路)
  - [2.5 区块链与 Saga 存证链路](#25-区块链与-saga-存证链路)
  - [2.6 多租户、JWT 与权限](#26-多租户jwt-与权限)
  - [2.7 加密与密钥链设计](#27-加密与密钥链设计)
  - [2.8 完整性巡检与告警](#28-完整性巡检与告警)
  - [2.9 CI/CD 与工程治理](#29-cicd-与工程治理)
- [3. 关键风险与修正建议](#3-关键风险与修正建议)
- [4. 同类项目主流技术路线](#4-同类项目主流技术路线)
- [5. 后续演进方案](#5-后续演进方案)
  - [P0：立即修正与稳定性加固](#p0立即修正与稳定性加固)
  - [P1：可信证明模型升级](#p1可信证明模型升级)
  - [P2：大文件与高并发链路重构](#p2大文件与高并发链路重构)
  - [P3：密钥治理与企业安全](#p3密钥治理与企业安全)
  - [P4：VC/DID 与跨平台互操作](#p4vcdid-与跨平台互操作)
  - [P5：存储生态扩展](#p5存储生态扩展)
  - [P6：供应链安全与发布可信化](#p6供应链安全与发布可信化)
- [6. 建议目标架构](#6-建议目标架构)
- [7. 里程碑规划](#7-里程碑规划)
- [8. 优先任务清单](#8-优先任务清单)
- [9. 最终判断](#9-最终判断)
- [10. 参考资料](#10-参考资料)

---

# 1. 总体结论

RecordPlatform 已经不是“文件上传 + 区块链存哈希”的简单原型，而是一个正在向**企业级文件存证平台**演进的系统。当前仓库已经具备以下较成熟的工程基础：

- Java 21 + Spring Boot 3.5.x 后端多模块结构；
- SvelteKit / Svelte 5 前端；
- Dubbo RPC 拆分 `platform-backend`、`platform-fisco`、`platform-storage`；
- FISCO BCOS 链上存证与多链适配抽象；
- S3 兼容分布式存储、故障域、quorum 写入、降级写入、异步修复；
- Saga 编排、Outbox、补偿重试、死信事件；
- 多租户隔离、JWT 租户一致性校验、SSE 一次性短 token；
- OpenTelemetry、Prometheus、Grafana SLO、Jaeger；
- CI 中的后端测试、前端测试、OpenAPI 合约一致性、Trivy 高危漏洞阻断、构建校验。

当前项目的主要短板不在基础技术栈，而在以下四个方面：

1. **可信证明模型已具备离线证明基础，仍需产品化互操作**
   当前代码已经包含 Merkle batch、attestation leaf、`proof-bundle.v1` 导出和基础离线 verifier。后续重点应转向公开 verifier 体验、VC/DID 凭证、签名/撤销状态和司法或监管接口。

2. **大文件主链路已迁移到对象存储直传与 manifest 下载**
   当前代码已经提供 `POST /api/v1/upload-sessions/direct`、预签名 staging PUT、直传完成校验、active chunk manifest、`download-metadata` 和前端流式下载合同。后续重点是压测、失败恢复、迁移存量无 manifest 文件，以及收敛仍存在的后端代理小文件/运维路径。

3. **密钥治理已进入 envelope 阶段，仍缺外部 KMS/HSM**
   当前代码已经将加密文件数据密钥包封到 `file_key_envelope`，按 `OWNER`、`SHARE`、`FRIEND_SHARE` recipient 解封，并支持分享撤销、显式 key envelope rotation 和访问审计。授权下载仍会对加密文件返回 `initialKey` 供前端解密；未加密直传返回 `initialKey=null`、`encryptionAlgorithm=NONE`。后续重点是外部 KMS/HSM、自动轮换调度和前端密钥暴露面继续收敛。

4. **对外互操作能力不足**
   当前更像“平台内存证系统”。后续应向 OpenTimestamps、TradeTrust、OpenAttestation、EAS、W3C VC/DID 等路线靠拢，形成可验证凭证、跨组织验真、司法或监管接口。

推荐后续主线：

> 以 **已落地的 Merkle batch、proof bundle、chunk manifest 和对象存储直传** 为当前工程底座，继续向 **VC/DID、公开 verifier、外部 KMS/HSM、IPFS/Filecoin、PQC 和供应链可信发布** 演进。

---

# 2. 当前架构与代码状态

## 2.1 项目定位

README 将 RecordPlatform 定位为：

> Enterprise-grade file attestation platform powered by blockchain and distributed storage.

现有功能包括：

- 区块链存证；
- 文件加密；
- RBAC 与所有权控制；
- ID 混淆；
- 分布式存储；
- 分片上传；
- 流式下载；
- 文件版本链；
- 文件分享与审计；
- 好友系统与 SSE 通知；
- 租户配额治理；
- 存储完整性检查；
- OpenTelemetry 与 SLO/SLI 观测。

**判断**：项目定位清晰，且 README 描述的能力多数在代码或 ROADMAP 中能找到对应实现。当前适合继续沿企业级可信文件平台方向推进，而不是回退成单纯文件网盘或链上 Demo。

---

## 2.2 技术栈与模块划分

### 后端

关键路径：

- `platform-backend/pom.xml`
- `platform-api/`
- `platform-fisco/`
- `platform-storage/`

技术特征：

- Java 21；
- Spring Boot 3.5.x；
- MyBatis Plus；
- Testcontainers；
- JaCoCo；
- AWS SDK v2；
- Redisson；
- RabbitMQ；
- Dubbo Triple；
- FISCO BCOS SDK；
- Resilience4j；
- Micrometer / OpenTelemetry。

`platform-backend/pom.xml` 中明确使用 Java 21、Spring Boot 3.5.14，并拆分为：

```text
backend-api
backend-common
backend-dao
backend-service
backend-web
```

### 前端

关键路径：

- `platform-frontend/package.json`

技术特征：

- Svelte 5；
- SvelteKit 2；
- Vite 7；
- Tailwind CSS 4；
- Vitest；
- openapi-typescript；
- MSW；
- ESLint / Prettier；
- ECharts。

前端已经配置：

```json
"types:gen": "openapi-typescript ...",
"types:gen:check": "pnpm types:gen && git diff --exit-code -- src/lib/api/types/generated.ts",
"test:coverage": "svelte-kit sync && vitest run --coverage"
```

**判断**：前后端技术栈较新，且与当前主流路线一致。短期不建议大规模替换框架。更应关注架构边界、证明模型、性能链路和密钥治理。

---

## 2.3 后端架构

README 中的架构可概括为：

```text
Frontend
   │
   ▼
platform-backend  REST API
   │
   ├── Dubbo RPC -> platform-fisco   -> FISCO BCOS
   └── Dubbo RPC -> platform-storage -> S3 / MinIO Cluster
```

同时依赖：

- Nacos；
- MySQL；
- Redis；
- RabbitMQ；
- S3 Cluster；
- OpenTelemetry Collector；
- Jaeger；
- Prometheus；
- Grafana。

### CQRS 倾向

`FileController` 的注释明确说明：

```java
采用 CQRS 模式：
Query 操作（读）使用 FileQueryService；
Command 操作（写）使用 FileService。
```

这说明文件模块已经有意识地区分读模型和写模型。

### API 规范化

`FileRestController#getFiles` 中对分页参数做了归一化：

- `pageNum` 最小为 1；
- `pageSize` 限制在 `[1, 100]`；
- `keywordMode` 统一大写；
- 支持 `OffsetDateTime` 时间过滤。

**判断**：后端结构已经不属于简单 CRUD。服务拆分、状态管理、鉴权、事件和补偿机制基本齐全。后续应进一步沉淀为平台能力，包括 proof service、policy service、key service、verification service。

---

## 2.4 文件上传与分布式存储链路

### 当前能力

`FileUploadServiceImpl` 中已经具备：

- 上传会话；
- 断点续传；
- 暂停与恢复；
- 分片大小限制；
- 最大文件大小限制；
- 分片 hash；
- Redis 上传状态；
- 异步处理；
- 配额检查；
- 临时目录清理；
- 分布式锁。

关键常量：

```java
MAX_FILE_SIZE_BYTES = 4096 * 1024 * 1024L; // 4GB
MAX_CHUNK_SIZE_BYTES = 80 * 1024 * 1024;   // 80MB
```

注释说明 80MB 分片上限与 Dubbo 100MB 载荷限制有关。

### 分布式存储

`DistributedStorageServiceImpl` 已经具备：

- 故障域机制；
- 一致性哈希；
- 负载感知读取；
- write quorum；
- 降级写入；
- 异步修复；
- Nacos 动态配置刷新；
- 容量聚合。

上传到存储服务时，代码会：

1. 根据文件 hash 选择目标节点；
2. 计算 required replicas 和 quorum size；
3. 并发上传到候选节点；
4. quorum 达成后返回逻辑路径；
5. 对失败节点安排异步修复；
6. 降级写入时记录 degraded write。

### 旧审查问题与当前状态

2026-06-09 审查时，主上传/下载链路仍存在后端代理字节流和内存聚合。典型旧证据包括：

- `FileSagaOrchestrator` 中读取本地分片：

```java
byte[] chunkData;
try (InputStream in = Files.newInputStream(chunkFile.toPath())) {
    chunkData = in.readAllBytes();
}
fileRemoteClient.storeFileChunk(chunkData, chunkHash);
```

- `DistributedStorageServiceImpl` 中读取对象：

```java
ByteArrayOutputStream outputStream = new ByteArrayOutputStream((int) objectSize)
return Optional.of(fileBytes);
```

这些路径在高并发、多分片、大文件下载时会造成堆内存压力。

**当前状态**：当前代码已提供对象存储直传、staging 对象 hash 校验、最终副本写入、active chunk manifest 和 `download-metadata` 预签名下载主路径。后续关注点从“是否迁移”转为压测、故障恢复、存量文件 manifest 治理，以及收敛仍保留的受控后端代理路径。

---

## 2.5 区块链与 Saga 存证链路

### 区块链适配层

`BlockChainAdapter` 已定义统一 SPI：

```java
ChainReceipt storeFile(...)
List<ChainFileInfo> getUserFiles(...)
ChainFileDetail getFile(...)
ChainReceipt deleteFiles(...)
ChainReceipt shareFiles(...)
ChainShareInfo getSharedFiles(...)
ChainReceipt cancelShare(...)
ChainStatus getChainStatus(...)
ChainTransaction getTransaction(...)
boolean isHealthy()
```

注释中说明支持：

- Local FISCO；
- BSN FISCO；
- BSN Besu。

### FISCO 实现

`AbstractFiscoAdapter` 封装 FISCO SDK 公共逻辑，包括：

- 调用合约 `storeFile`；
- 解析交易 receipt；
- 解析 fileHash；
- 查询文件；
- 分享文件；
- 取消分享。

### Saga 编排

`FileSagaOrchestrator` 负责：

1. 启动或恢复 Saga；
2. 上传文件分片到 S3；
3. 调用区块链写入文件元数据；
4. 发布成功事件；
5. 完成 Saga；
6. 失败时进入补偿；
7. 补偿 S3；
8. 补偿数据库状态；
9. 定时重试待补偿 Saga；
10. 超过重试次数后发布死信事件。

注释中明确说明不使用大事务：

```text
1. Saga 模式通过补偿实现最终一致性；
2. 外部调用不受数据库事务管理；
3. 每个步骤完成后使用独立事务；
4. 避免长事务导致锁竞争和连接占用。
```

**判断**：Saga + Outbox 的方向正确，已经具备生产系统的分布式事务思维。后续应增强链上数据模型，把单文件上链演进为 Merkle batch 和 proof registry。

---

## 2.6 多租户、JWT 与权限

### TenantFilter

`TenantFilter` 从 `X-Tenant-ID` 读取租户 ID，并设置 `TenantContext`。白名单包括：

- health；
- swagger；
- public shares；
- image download 等。

对于非白名单路径，如果缺少 `X-Tenant-ID`，会返回错误。

### JWT 过滤器

`JwtAuthenticationFilter` 会：

- 解析 JWT；
- 读取 userId、role、tenantId；
- 校验请求头 tenantId 与 JWT tenantId 是否一致；
- 设置 Spring Security 上下文；
- 写入 request attribute；
- 写入 MDC；
- finally 中清理 MDC 和 TenantContext。

### JWT 工具

`JwtUtils` 已经有较完善的安全处理：

- HMAC512；
- issuer；
- audience；
- JWT key 长度检查；
- entropy 检查；
- 弱密钥检查；
- token blacklist；
- refresh token invalidation；
- SSE 短 token；
- SSE token 原子 get-and-delete 防重放。

### 当前风险

`FileServiceImpl#getUserFilesList` 的缓存 key 仅使用 `userId`：

```java
@Cacheable(cacheNames = "userFiles", key = "#userId")
```

在多租户系统中，应显式加入 `tenantId`。否则当不同租户存在相同 userId，或者缓存上下文与 DB tenant interceptor 不一致时，存在跨租户缓存污染风险。

---

## 2.7 加密与密钥链设计

### 前端解密

`platform-frontend/src/lib/utils/crypto.ts` 支持：

- AES-GCM；
- ChaCha20-Poly1305；
- magic bytes；
- version；
- algorithm；
- 12B IV；
- 分片 hash；
- next key；
- 流式解密。

分片密钥链设计：

```text
chunk[i] 末尾包含 chunk[i+1] 的密钥
最后一个分片包含 chunk[0] 的密钥
解密从最后一个分片开始，使用 initialKey
```

### 后端加密策略

`EncryptionStrategyFactory` 支持：

- 显式 AES-GCM；
- 显式 ChaCha20；
- auto + benchmark；
- auto 默认 ChaCha20；
- benchmark 中每次加密使用新 IV。

### 当前合同与剩余风险

`FileQueryServiceImpl#getFileDecryptInfo` / `FileServiceImpl` 的授权解密 metadata 当前返回：

- 加密文件：从 active key envelope 解封得到的 `initialKey`；
- 未加密直传文件：`initialKey=null`，`encryptionAlgorithm=NONE`；
- `fileName`；
- `fileSize`；
- `contentType`；
- `chunkCount`；
- `fileHash`。

当前严格合同：

- 新上传文件不再持久化 `fileParam.initialKey`；
- 加密文件必须存在 active owner/share/friend-share envelope；
- envelope 必须具备完整 `algorithmSuite`、`signatureSuite`、`kemSuite`、`proofSuite` 元数据；
- 直传路径必须显式写入 `encryptionAlgorithm=NONE`，不能仅凭 `uploadMode` 推断。

剩余企业级演进方向：

- KMS/HSM；
- tenant master key；
- key policy；
- 自动 key rotation；
- key access audit。

---

## 2.8 完整性巡检与告警

`IntegrityCheckService` 已实现：

- 全租户巡检；
- 单租户手动巡检；
- 分布式锁；
- 按租户查询成功文件；
- 随机采样；
- 告警记录；
- 告警确认；
- 告警解决；
- SSE 通知管理员。

当前校验逻辑：

1. 构造文件路径；
2. 调用存储服务读取文件；
3. 判断返回数据非空；
4. 调用链服务查询文件；
5. 比对 DB hash 和 chain hash；
6. 不一致则创建告警。

### 当前问题

完整性巡检的日常路径不应总是下载对象内容。当前逻辑读取对象后只是判断非空，没有真正重算对象内容 hash。这既造成成本，又不能提供足够强的内容完整性证明。

建议改为三级：

1. **轻量巡检**：`HeadObject`，验证对象存在、大小、metadata hash、etag、tenant path。
2. **中量巡检**：校验 chunk manifest、chunk hash、Merkle root。
3. **重型巡检**：抽样下载并重算内容 hash。

---

## 2.9 CI/CD 与工程治理

`.github/workflows/test.yml` 已经包含：

- path filter；
- backend tests；
- FISCO tests；
- storage tests；
- frontend format check；
- frontend lint；
- frontend type check；
- frontend coverage；
- OpenAPI artifact；
- generated TypeScript contract consistency；
- Trivy SARIF；
- Trivy HIGH/CRITICAL blocking；
- backend build；
- frontend build。

这是非常好的工程基础。

下一步应补：

- SBOM；
- container image signing；
- release provenance；
- SLSA；
- license policy；
- dependency allowlist；
- contract ABI fingerprint；
- smart contract fuzzing；
- security regression tests。

---

# 3. 关键风险与修正建议

| 优先级 | 风险点 | 证据路径 | 影响 | 建议 |
|---|---|---|---|---|
| P0 | 用户文件缓存 key 缺少租户维度 | `platform-backend/backend-service/src/main/java/cn/flying/service/impl/FileServiceImpl.java` | 多租户缓存污染 | key 改为 `tenantId:userId`，补跨租户缓存测试 |
| P0 | 管理员删除接口可能未转换外部 ID | `platform-backend/backend-web/src/main/java/cn/flying/controller/FileController.java` | 外部 ID 与内部主键语义不一致 | 统一 `IdUtils.fromExternalId` |
| 已处理 | 大文件上传链路存在内存聚合 | `UploadSessionController` / `FileUploadServiceImpl` | 高并发下堆内存压力 | 当前已提供 `/api/v1/upload-sessions/direct` 对象存储直传 |
| 已处理 | 下载链路返回 `List<byte[]>` 作为主路径 | `FileQueryServiceImpl` | 大文件下载压垮后端 | 当前主路径为 `download-metadata` + 预签名 URL；`chunks` 仅保留受控代理路径 |
| P0 | 定时清理 SUID 为空时跳过文件系统清理 | `FileUploadServiceImpl.java` | 临时文件泄漏 | 上传状态持久化 suid/path |
| 已处理 | 完整性巡检/下载缺少 manifest 合同 | `ChunkManifestServiceImpl` | 证明弱、迁移困难 | 当前已有 `cn.flying.chunk-manifest.v1` 持久化和 manifest-backed 下载 |
| 部分处理 | 授权解密响应仍返回解封后的 `initialKey` | `FileQueryServiceImpl` / `FileKeyEnvelopeService` | 前端解密仍需短期接触数据密钥 | 当前已用 key envelope 包封和审计，且未加密直传返回 `initialKey=null`；后续接 KMS/HSM 并继续收敛前端密钥暴露 |
| 已处理 | 单文件上链扩展性有限 | `AttestationBatchServiceImpl` / `BlockChainServiceImpl` | 链写入吞吐瓶颈 | 当前已有 Merkle batch root 上链 |
| 部分处理 | 外部验证能力不足 | `ProofBundleServiceImpl` / `ProofBundleVerifierImpl` | 第三方验真门槛高 | 当前已有 proof bundle + 基础 verifier；后续补公开 verifier、签名、VC |
| P2 | 合约升级治理不足 | `platform-fisco/contract/` | ABI/地址/版本兼容风险 | 合约 registry、ABI fingerprint、proxy 策略 |

---

# 4. 同类项目主流技术路线

## 4.1 OpenTimestamps：文件哈希 + 时间戳 + 聚合上链

OpenTimestamps 的核心思想是：

- 客户端对文件计算 hash；
- 生成时间戳证明；
- 日历服务器聚合大量证明；
- 将聚合根锚定到比特币；
- 验证时不需要上传原文。

**对本项目的启发**：

当前平台应从单文件逐笔上链升级为 Merkle batch attestation：

```text
N 个文件 hash -> Merkle Tree -> Merkle Root 上链 -> 每个文件保存 proof path
```

收益：

- 链上交易从 O(N) 降到 O(1)；
- 可离线验证；
- 更适合批量上传；
- 更容易对接 proof bundle 和 VC。

---

## 4.2 OpenAttestation / TradeTrust：可验证文档框架

OpenAttestation 的技术路线强调：

- 文档背书；
- 区块链验证；
- 选择性披露；
- 发行方身份验证；
- 第三方验证器。

TradeTrust 更强调贸易文档、所有权转移和跨组织验真。

**对本项目的启发**：

RecordPlatform 不应只输出“交易哈希”。应输出结构化证据包：

```text
proof.zip
├── manifest.json
├── merkle-proof.json
├── blockchain-receipt.json
├── issuer-signature.jws
├── verification-policy.json
└── README.verify.md
```

第三方拿到原始文件和 proof 包即可验证。

---

## 4.3 W3C VC/DID：凭证化与自主身份

W3C VC 2.0 的路线是：

```text
Issuer -> Credential -> Holder -> Verifier
```

DID Core 则解决去中心化标识和验证方法。

**对本项目的启发**：

将文件存证结果封装成 `FileEvidenceCredential`：

```json
{
  "@context": [
    "https://www.w3.org/ns/credentials/v2",
    "https://record.example.com/contexts/file-evidence-v1"
  ],
  "type": ["VerifiableCredential", "FileEvidenceCredential"],
  "issuer": "did:web:record.example.com",
  "credentialSubject": {
    "fileHash": "sha256:...",
    "merkleRoot": "0x...",
    "blockchainTx": "0x...",
    "storageManifestHash": "sha256:..."
  }
}
```

---

## 4.4 EAS：Schema 化 Attestation

Ethereum Attestation Service 的路线是：

- 注册 schema；
- 生成链上或链下 attestations；
- SDK / CLI / Indexer / GraphQL；
- 支持 private data；
- 支持 ZK attestation。

**对本项目的启发**：

可为文件生命周期定义 schema：

```text
FileCreatedAttestation
FileVersionAttestation
FileShareAttestation
IntegrityCheckAttestation
RetentionPolicyAttestation
LegalEvidenceAttestation
```

这样项目可以从“文件存证系统”演进为“文件生命周期 attestation 平台”。

---

## 4.5 IPFS/Filecoin：内容寻址与可验证存储

IPFS CID 基于内容寻址，但 CID 不等于简单文件 hash，因为大文件分块、DAG layout、codec 都会影响 CID。Filecoin 则通过存储证明与经济激励提供长期存储能力。

**对本项目的启发**：

短期不建议直接替换 S3。应采用冷热分层：

```text
热数据：S3 / MinIO
归档数据：S3 IA / Glacier 类低频存储
长期保全：IPFS pinning + Filecoin storage deal
证明层：ChunkManifest + MerkleProof + StorageProof
```

---

## 4.6 后量子密码与 Crypto Agility

NIST 已标准化 ML-KEM、ML-DSA、SLH-DSA，并选择 HQC 作为 KEM 备选路线。

**对本项目的启发**：

当前不需要立刻替换所有算法，但必须在数据模型中预留：

```text
crypto_suite_id
signature_suite_id
kem_suite_id
key_version
proof_suite
deprecated_after
```

确保未来可以迁移到 PQC 或 hybrid KEM。

---

## 4.7 SLSA / Sigstore / SBOM：供应链可信发布

当前项目已经有较强 CI。下一步应让平台自身交付物可验证：

- SBOM；
- 镜像 digest；
- Cosign 签名；
- SLSA provenance；
- Release attestation；
- OpenAPI fingerprint；
- ABI fingerprint。

这与项目“可信存证”的定位一致：平台自己也应该可被验证。

---

# 5. 后续演进方案

## P0：立即修正与稳定性加固

目标：修复明确代码风险，降低多租户、大文件、清理、权限相关的生产事故概率。

### P0-1. 修复多租户缓存 key

当前：

```java
@Cacheable(cacheNames = "userFiles", key = "#userId")
```

建议：

```java
@Cacheable(
    cacheNames = "userFiles",
    key = "T(cn.flying.common.tenant.TenantContext).getTenantIdOrDefault() + ':' + #userId"
)
```

同时补测试：

- tenant A / userId=1 查询文件；
- tenant B / userId=1 查询文件；
- 验证缓存不串；
- upload/delete/share 后按 tenant 维度驱逐缓存。

---

### P0-2. 修复管理员删除外部 ID 转换

当前管理员删除：

```java
@DeleteMapping("/{id}")
public Result<String> deleteFileById(@PathVariable("id") String id) {
    fileService.removeByIds(List.of(id));
    return Result.success("文件删除成功");
}
```

建议：

```java
Long fileId = IdUtils.fromExternalId(id);
fileService.removeByIds(List.of(fileId));
```

或复用统一删除服务：

```java
fileAdminService.deleteFileByExternalId(id);
```

---

### P0-3. 上传会话清理持久化路径

建议在 `FileUploadState` 中增加：

```java
private Long tenantId;
private Long userId;
private String suid;
private String uploadTempPath;
private String processedTempPath;
private Integer cleanupRetryCount;
```

定时清理时不再依赖外部传入 SUID，而是从状态中读取路径。

---

### P0-4. 完整性巡检改轻量路径

新增存储接口：

```java
Result<StorageObjectHeadVO> headObject(String filePath, String fileHash);
```

返回：

```json
{
  "exists": true,
  "size": 123456,
  "etag": "...",
  "metadataHash": "sha256:...",
  "node": "node-a",
  "tenantId": 1
}
```

巡检逻辑：

```text
HeadObject -> 校验 metadata -> 校验 DB/链 hash -> 必要时抽样下载重算 hash
```

---

## P1：可信证明模型升级

目标：从“平台内可查”升级为“平台外可验”。

### P1-1. Merkle Batch Attestation

新增表：

```sql
CREATE TABLE attestation_batch (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    merkle_root VARCHAR(128) NOT NULL,
    chain_type VARCHAR(32) NOT NULL,
    chain_id VARCHAR(64),
    contract_address VARCHAR(128),
    tx_hash VARCHAR(128),
    block_number BIGINT,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL
);

CREATE TABLE attestation_leaf (
    id BIGINT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    file_hash VARCHAR(128) NOT NULL,
    leaf_hash VARCHAR(128) NOT NULL,
    leaf_index INT NOT NULL,
    proof_path_json JSON,
    proof_algorithm VARCHAR(32) NOT NULL
);
```

链上只写：

```solidity
struct BatchAttestation {
    bytes32 merkleRoot;
    uint256 tenantHash;
    uint256 batchSize;
    string manifestHash;
    uint256 createdAt;
}
```

### P1-2. Proof Bundle

输出：

```text
record-proof-{fileId}-{version}.zip
├── manifest.json
├── file.hash
├── chunk-manifest.json
├── merkle-proof.json
├── blockchain-receipt.json
├── issuer-signature.jws
├── verification-policy.json
└── README.verify.md
```

### P1-3. 独立 Verifier

新增：

```text
platform-verifier/
├── web-verifier
├── cli-verifier
└── sdk
```

支持：

- 本地计算文件 hash；
- 校验 chunk manifest；
- 校验 Merkle proof；
- 查询链上 root；
- 校验证明包签名；
- 展示 issuer、version、tx、block、撤销状态。

---

## P2：大文件与高并发链路重构

目标：让后端不再代理大字节流，只处理元数据、状态和证明。

### P2-1. 上传改为 S3 Multipart 直传

新链路：

```text
1. 前端请求 direct upload session
2. 后端校验 quota、分片连续性、size、plainHash/cipherHash
3. platform-storage 返回 staging PUT 预签名 URL
4. 前端直传 staging 对象并收集 ETag
5. 后端 complete direct upload，读取 staging bytes 并校验 SHA-256 == cipherHash
6. platform-storage 写入最终副本并返回 storagePath/etag/checksum
7. 后端注册文件、生成 active chunk manifest、写文件存证
```

接口示例：

```http
POST /api/v1/upload-sessions/direct
POST /api/v1/upload-sessions/{clientId}/direct/complete
DELETE /api/v1/upload-sessions/{clientId}/direct
```

### P2-2. 下载改为预签名 URL + 前端流式解密

后端返回：

```json
{
  "fileId": "...",
  "manifestHash": "sha256:...",
  "encryptionAlgorithm": "NONE",
  "initialKey": null,
  "parts": [
    {
      "index": 0,
      "downloadUrl": "https://...",
      "cipherHash": "sha256:...",
      "size": 83886080
    }
  ]
}
```

前端使用 `download-metadata` 返回的 manifest、hash、URL 与解密 metadata 执行流式下载，避免大文件主路径返回 `List<byte[]>`。

### P2-3. Chunk Manifest

示例：

```json
{
  "schema": "cn.flying.chunk-manifest.v1",
  "fileHash": "sha256:...",
  "chunkSize": 83886080,
  "chunks": [
    {
      "index": 0,
      "plainHash": "sha256:...",
      "cipherHash": "sha256:...",
      "size": 83886080,
      "storagePath": "storage/tenant/1/chunk/..."
    }
  ],
  "merkleRoot": "sha256:..."
}
```

用途：

- 断点续传；
- 完整性巡检；
- Merkle 上链；
- 外部验证；
- 跨存储后端迁移；
- 局部修复。

---

## P3：密钥治理与企业安全

目标：从应用级密钥传递升级为企业级密钥生命周期管理。

### P3-1. Envelope Encryption

建议密钥层级：

```text
Tenant Master Key    KMS/HSM 管理
File Data Key        每个文件版本一个 DEK
Chunk Key            由 DEK + chunk index 派生，或保留 key chain
Recipient Key        接收方或租户公钥
Wrapped Data Key     被 KMS 或接收方 key 加密后的 DEK
```

数据库保存：

```text
encrypted_dek
kms_key_id
key_version
algorithm_suite
recipient_key_envelopes
rotation_status
```

### P3-2. 密钥轮换与撤销

能力：

- 租户主密钥轮换；
- 文件 DEK 重新包装；
- 分享撤销时删除接收方 wrapped key；
- 下载前校验 key policy；
- key unwrap 操作写审计日志。

### P3-3. Crypto Agility

预留：

```text
algorithm_suite
signature_suite
kem_suite
proof_suite
key_version
created_at
deprecated_after
```

后续可支持：

- AES-256-GCM；
- ChaCha20-Poly1305；
- Ed25519；
- ECDSA P-256；
- ML-DSA；
- SLH-DSA；
- X25519 + ML-KEM hybrid。

---

## P4：VC/DID 与跨平台互操作

目标：让存证结果成为标准化可验证凭证。

### P4-1. FileEvidenceCredential

示例：

```json
{
  "@context": [
    "https://www.w3.org/ns/credentials/v2",
    "https://record.example.com/contexts/file-evidence-v1"
  ],
  "type": ["VerifiableCredential", "FileEvidenceCredential"],
  "issuer": "did:web:record.example.com",
  "validFrom": "2026-06-09T00:00:00Z",
  "credentialSubject": {
    "fileHash": "sha256:...",
    "fileNameHash": "sha256:...",
    "version": 2,
    "merkleRoot": "0x...",
    "blockchainTx": "0x...",
    "storageManifestHash": "sha256:..."
  }
}
```

### P4-2. DID 身份体系

支持：

- `did:web`：企业部署简单；
- `did:key`：离线验证方便；
- `did:pkh`：与链账户绑定。

### P4-3. 撤销状态

新增：

```text
credential_status
- credential_id
- status: active / revoked / superseded / expired
- reason
- updated_at
- tx_hash
```

用于：

- 文件版本被替代；
- 分享被撤销；
- 文件被合法删除；
- 发现完整性异常；
- 发行方密钥泄露。

---

## P5：存储生态扩展

目标：从分布式 S3 演进为多后端、可验证、冷热分层存储。

### P5-1. StorageBackend 抽象

```java
interface StorageBackend {
    PutResult put(ObjectKey key, InputStream data, StoragePolicy policy);
    HeadResult head(ObjectKey key);
    GetResult get(ObjectKey key, Range range);
    DeleteResult delete(ObjectKey key);
    ProofResult prove(ObjectKey key);
}
```

实现：

```text
S3HotStorageBackend
MinioClusterBackend
IpfsPinningBackend
FilecoinArchiveBackend
LocalDevBackend
```

### P5-2. 冷热分层策略

| 数据类型 | 存储策略 |
|---|---|
| 30 天内高频访问 | S3 / MinIO 热存储 |
| 归档文件 | S3 IA / Glacier 类低频存储 |
| 长期保全文件 | IPFS pinning + Filecoin |
| 法务证据包 | 多副本 + WORM + proof bundle |
| 临时上传分片 | staging bucket + lifecycle 自动清理 |

---

## P6：供应链安全与发布可信化

目标：平台自身也可验证。

现有 CI 已经较强。建议补充：

1. **SBOM**
   Maven、pnpm、容器镜像分别生成 CycloneDX SBOM。

2. **镜像签名**
   使用 Sigstore Cosign 签名后端、FISCO、storage、frontend 镜像。

3. **SLSA provenance**
   GitHub Actions 输出构建来源证明。

4. **Release attestation**
   每个 release 包含 commit、SBOM、测试报告、镜像 digest、OpenAPI fingerprint、ABI fingerprint。

5. **依赖策略**
   高危漏洞阻断之外，增加 license policy、transitive dependency allowlist。

---

# 6. 建议目标架构

```text
                    ┌─────────────────────┐
                    │  Web / CLI Verifier  │
                    └──────────┬──────────┘
                               │ proof.zip / VC
┌──────────────┐      ┌────────▼────────┐
│ SvelteKit UI │─────▶│ Backend API     │
└──────┬───────┘      │ Auth/Tenant     │
       │              │ File Metadata   │
       │              │ Proof Service   │
       │              │ Policy Service  │
       │              └───────┬─────────┘
       │                      │
       │ presigned URL        │ metadata/event
       ▼                      ▼
┌──────────────┐      ┌─────────────────┐
│ S3/MinIO     │◀────▶│ Storage Service │
│ Hot Storage  │      │ Quorum/Repair   │
└──────┬───────┘      └─────────────────┘
       │
       │ archive / pin / deal
       ▼
┌──────────────┐
│ IPFS/Filecoin│
│ Archive      │
└──────────────┘

┌────────────────┐     ┌───────────────────┐
│ Merkle Service │────▶│ FISCO / Chain     │
│ Batch/Proof    │     │ Contract Registry │
└────────────────┘     └───────────────────┘

┌────────────────┐     ┌───────────────────┐
│ KMS/HSM        │────▶│ Key Envelope      │
│ Tenant Keys    │     │ Rotation/Audit    │
└────────────────┘     └───────────────────┘
```

---

# 7. 里程碑规划

| 状态 | 目标 | 当前交付物 / 后续动作 |
|---|---|---|
| 已落地 | 基础风险修复 | 租户缓存 key、admin delete ID、上传清理、manifest-backed 下载、key envelope 严格合同 |
| 已落地 | Merkle 证明 MVP | batch 表、leaf 表、Merkle root 上链、proof bundle、基础 verifier |
| 已落地 | 大文件链路 v2 | 对象存储直传、预签名下载、chunk manifest、前端流式下载合同 |
| 部分落地 | 密钥包装 | local AES-GCM envelope、wrapped data key、分享撤销、key audit、显式 rotation；后续接 KMS/HSM 和自动轮换 |
| 待推进 | VC/DID 外部互操作 | FileEvidenceCredential、DID issuer、公开 verifier、撤销状态 |
| 待推进 | 生态扩展 | IPFS/Filecoin 归档、PQC suite 预留、多方签名、SLSA/Sigstore 发布可信化 |

---

# 8. 优先任务清单

当前最值得优先做的 10 个任务：

1. **接入外部 KMS/HSM**
   将当前 local wrapping provider 替换或扩展为真实 KMS provider。

2. **自动 key envelope rotation 调度**
   在显式管理员 rotation 基础上增加定期轮换、批量轮换和失败重试。

3. **公开 verifier 产品化**
   将 `ProofBundleVerifier` 包装为 CLI/网页/独立服务，降低第三方验真门槛。

4. **proof bundle 签名与撤销状态**
   补 issuer 签名、签名 key 管理和撤销检查。

5. **存量文件 manifest 回填策略**
   对缺少 active manifest 的历史文件给出迁移、重传或不可下载状态治理。

6. **直传链路压测与故障注入**
   覆盖 staging 对象缺失、hash mismatch、部分副本失败、degraded write 恢复。

7. **下载端密钥暴露面继续收敛**
   评估前端解密、WebCrypto、短期密钥授权和密钥最小暴露窗口。

8. **VC/DID 凭证模型**
   将 proof bundle 封装为 FileEvidenceCredential，定义 issuer、holder、verifier 边界。

9. **合约 registry 与 ABI fingerprint**
   明确 Sharing/Storage 合约地址、ABI 版本和升级治理。

10. **增加 SBOM + 镜像签名 + release provenance**
    使平台自身具备可验证交付能力。

---

# 9. 最终判断

RecordPlatform 已经具备继续演进为企业级存证平台的基础。

短板不在 Spring、Svelte、Dubbo 或 FISCO 的选型，而在：

- 证明模型已有 proof bundle 和基础 verifier，但还需要公开 verifier、签名和 VC/DID 产品化；
- 大文件主链路已有对象存储直传与 manifest-backed 下载，但仍需要压测、故障注入和存量文件 manifest 治理；
- 密钥治理已有 local envelope、recipient envelope、撤销、审计和显式 rotation，但还需要外部 KMS/HSM、自动轮换和前端密钥暴露面收敛；
- 存证结果需要继续向跨平台互认、监管/司法接口和供应链可信发布方向产品化。

推荐路线：

> 以当前已落地的 Merkle batch、proof bundle、chunk manifest、对象存储直传和 key envelope 为工程底座，继续扩展到公开 verifier、VC/DID、外部 KMS/HSM、IPFS/Filecoin、PQC 和供应链可信发布。

---

# 10. 参考资料

## 仓库内关键文件

| 文件 | 说明 |
|---|---|
| `README.md` | 项目定位、架构、功能、技术栈 |
| `ROADMAP.md` | 当前路线图、自动化基线、P0/P1/P2/P3 规划 |
| `platform-backend/pom.xml` | 后端 Maven 父工程与依赖管理 |
| `platform-frontend/package.json` | 前端技术栈与脚本 |
| `.github/workflows/test.yml` | 核心 CI 门禁 |
| `platform-backend/backend-web/src/main/java/cn/flying/controller/FileController.java` | 文件命令与版本接口 |
| `platform-backend/backend-web/src/main/java/cn/flying/controller/FileRestController.java` | 文件查询 REST 接口 |
| `platform-backend/backend-service/src/main/java/cn/flying/service/impl/FileServiceImpl.java` | 文件业务服务 |
| `platform-backend/backend-service/src/main/java/cn/flying/service/impl/FileUploadServiceImpl.java` | 上传会话、分片与处理 |
| `platform-backend/backend-service/src/main/java/cn/flying/service/saga/FileSagaOrchestrator.java` | Saga 上传编排与补偿 |
| `platform-storage/src/main/java/cn/flying/storage/service/DistributedStorageServiceImpl.java` | 分布式存储、quorum、修复 |
| `platform-fisco/src/main/java/cn/flying/fisco_bcos/service/BlockChainServiceImpl.java` | 链服务入口 |
| `platform-fisco/src/main/java/cn/flying/fisco_bcos/adapter/BlockChainAdapter.java` | 多链适配 SPI |
| `platform-fisco/src/main/java/cn/flying/fisco_bcos/adapter/impl/AbstractFiscoAdapter.java` | FISCO 适配公共逻辑 |
| `platform-backend/backend-web/src/main/java/cn/flying/filter/TenantFilter.java` | 租户过滤器 |
| `platform-backend/backend-web/src/main/java/cn/flying/filter/JwtAuthenticationFilter.java` | JWT 与租户一致性校验 |
| `platform-backend/backend-common/src/main/java/cn/flying/common/util/JwtUtils.java` | JWT、黑名单、SSE token |
| `platform-backend/backend-service/src/main/java/cn/flying/service/integrity/IntegrityCheckService.java` | 完整性巡检 |
| `platform-frontend/src/lib/utils/crypto.ts` | 前端分片解密与流式解密 |
| `platform-backend/backend-service/src/main/java/cn/flying/service/encryption/EncryptionStrategyFactory.java` | 加密策略选择 |

## 外部技术与项目

| 项目 / 标准 | 参考价值 | 链接 |
|---|---|---|
| OpenTimestamps | Merkle 聚合、时间戳证明、离线验证 | https://opentimestamps.org/ |
| OpenAttestation | 可验证文档、选择性披露、发行方身份 | https://www.openattestation.com/ |
| TradeTrust | 政府/贸易文档存证与验证 | https://www.tradetrust.io/ |
| Ethereum Attestation Service | Schema 化 attestation、SDK、Indexer、Private Data | https://attest.org/ |
| W3C VC Data Model 2.0 | 可验证凭证模型 | https://www.w3.org/TR/vc-data-model-2.0/ |
| W3C DID Core | 去中心化标识符 | https://www.w3.org/TR/did-1.0/ |
| IPFS Content Addressing | CID 与内容寻址 | https://docs.ipfs.tech/concepts/content-addressing/ |
| Filecoin Docs | 可验证存储、长期存储证明 | https://docs.filecoin.io/ |
| NIST FIPS 203 | ML-KEM 后量子密钥封装 | https://csrc.nist.gov/pubs/fips/203/final |
| NIST FIPS 204 | ML-DSA 后量子签名 | https://csrc.nist.gov/pubs/fips/204/final |
| NIST FIPS 205 | SLH-DSA 后量子签名 | https://csrc.nist.gov/pubs/fips/205/final |
| OpenTelemetry | traces / metrics / logs 采集标准 | https://opentelemetry.io/ |
| SLSA | 软件供应链安全等级与 provenance | https://slsa.dev/ |
| Sigstore | 镜像、SBOM、发布物签名 | https://docs.sigstore.dev/ |
