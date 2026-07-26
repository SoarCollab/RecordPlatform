# 分片 Manifest 与历史数据治理

`cn.flying.chunk-manifest.v1` 是大文件传输、完整性检查、证明生成、存储迁移和局部修复共用的元数据合同。Manifest 只保存有序对象引用与哈希，文件字节仍保存在对象存储中。

## Canonical 合同

持久化的 `manifestHash` 是移除 `manifestHash` 字段后，对 canonical UTF-8 JSON 计算得到的 SHA-256：

```json
{
  "schema": "cn.flying.chunk-manifest.v1",
  "fileHash": "chain-record-id",
  "hashAlgorithm": "SHA-256",
  "chunkSize": 83886080,
  "totalSize": 167772160,
  "storageBackend": "S3",
  "chunks": [
    {
      "index": 0,
      "plainHash": "sha256:...",
      "cipherHash": "sha256:...",
      "size": 83886080,
      "storagePath": "storage/tenant/1/chunk/...",
      "storageBackend": "S3",
      "checksumAlgorithm": "SHA-256"
    }
  ]
}
```

`ChunkManifestCanonicalizer` 按 `index` 排序，要求索引从 `0` 开始连续，并从 canonical JSON 字节生成 `sha256:<小写十六进制>`。

v1 保留 `fileHash` 字段名以兼容文件表，其值对应 `file.fileHash`，当前语义是链上记录标识，并非对象内容摘要。证据字段必须按以下语义使用：

- 分片 `plainHash`：明文字节证据；
- 分片 `cipherHash`：存储对象字节证据；
- `manifestHash`：有序 canonical manifest 摘要；
- 签名证明中的 `contentHash`/`file.hash`：完整原文件摘要。

v1 的 `merkleRoot` 可选，且本 schema 不定义分片 Merkle 构造算法。该值存在时会被 `manifestHash` 覆盖；完整性检查只校验 SHA-256 形态，不臆造构造规则。

## 持久化与 Active Slot

`file_chunk_manifest` 保存 manifest 头和 canonical JSON，`file_chunk_manifest_item` 保存有序分片。迁移 `V1.17.0` 增加租户/文件 active-slot 约束，保证每条文件记录最多只有一个未删除的 active manifest。版本由稳定文件记录承载；`file_version` 是证据元数据，不属于唯一键。

直接分片上传完成时，系统先用声明的 `cipherHash` 校验所有暂存对象，再持久化 active manifest。当前直传合同保存未加密对象字节，因此 `plainHash` 与 `cipherHash` 必须一致，`encryptionAlgorithm` 为 `NONE`。

完整性检查批量读取 active manifest 头和分片。轻量、中量检查对每个 `storagePath`/`cipherHash` 执行 `HeadObject`；重量检查仅下载配置的抽样分片并重算 `cipherHash`。缺少 manifest 会分类为 `MANIFEST_MISSING`，不会伪装成对象损坏。

## 缺失 Manifest 的机器合同

`GET /api/v1/files/hash/{fileHash}/download-metadata` 不会为需要 active manifest 的自有文件静默回退到旧式 URL 元数据。成功响应包含：

- `canonicalManifestJson`
- `manifestStatus`
- `manifestClassification`
- `manifestErrorCode`
- `legacyDownloadAllowed`

存在 active manifest 时返回 `manifestStatus=ACTIVE`、`manifestClassification=ALREADY_MANIFEST`、`legacyDownloadAllowed=false`。缺失时返回业务码 `FILE_RECORD_ERROR`；标准 `ErrorPayload` 在 `data.detail` 中携带结构化 `ManifestErrorDetail`。尚未生成治理项时，该明细为：

```json
{
  "manifestStatus": "REUPLOAD_REQUIRED",
  "manifestClassification": "UNCLASSIFIED",
  "manifestErrorCode": "MISSING_MANIFEST_UNCLASSIFIED",
  "legacyDownloadAllowed": false
}
```

完成分类后，`data.detail` 反映最新治理项。客户端必须按这些字段分支，不得解析 `message`。`legacyDownloadAllowed` 是显式策略输出，不能因为记录缺失而自行推断。

## 回填模式与分类

管理员用以下三种模式之一创建持久化任务：

| 模式 | 行为 |
| --- | --- |
| `SCAN` | 发现并分类候选项，不生成或应用 manifest。 |
| `DRY_RUN` | 生成可审查快照与 proposed manifest，不修改 active slot。 |
| `APPLY` | 仅在显式 apply 开关启用后应用符合条件的 proposal。 |

任务状态为 `PLANNED`、`SCANNING`、`SNAPSHOT_READY`、`APPLYING`、`PAUSED`、`COMPLETED`、`FAILED`。条目状态为 `PENDING`、`RUNNING`、`BACKFILLED`、`REUPLOAD_REQUIRED`、`UNRECOVERABLE`、`FAILED`、`IGNORED`。

每个候选项会得到一种分类：

| 分类 | 含义 |
| --- | --- |
| `ALREADY_MANIFEST` | 已存在有效 active manifest。 |
| `BACKFILLABLE` | 现有证据足以构造并验证 canonical manifest。 |
| `REUPLOAD_REQUIRED` | 无法重建可信分片证据，需要所有者重新上传。 |
| `UNRECOVERABLE` | 所需源数据永久缺失或互相矛盾。 |
| `FAILED` | 处理失败，可在有界重试策略内重试。 |
| `IGNORED` | 记录不在本次治理范围。 |

Worker 每页读取 100 条、每次最多 claim 20 条、每条最多尝试三次，claim lease 为 120 秒。

## 安全默认值

回填和破坏性 sweep 均采用默认关闭、失败闭合的策略：

| 配置 | 默认值 |
| --- | ---: |
| 回填 worker | `true` |
| Apply 开关 | `false` |
| Run lease | 300 秒 |
| Worker 延迟 | 5 秒 |
| Sweep mark 开关 | `false` |
| Sweep delete 开关 | `false` |
| Sweep 保护窗口 | 30 天 |
| Sweep 批量 | 20（硬上限 100） |
| Sweep lease | 120 秒（最小 30 秒） |
| Sweep worker 延迟 | 60 秒 |

`APPLY` 模式不会绕过 `apply-enabled=false`。标记与删除使用独立开关，删除候选项仍受保护窗口和持久化 ledger 约束。

## 管理 API

全部端点仅限管理员，根路径为 `/api/v1/admin/manifest-backfill-runs`：

- `POST /`：创建任务；
- `GET /`、`GET /{runId}`：列出和查看任务；
- `GET /{runId}/items`：按状态、分类或原因游标查询条目；
- `POST /{runId}/pause`、`POST /{runId}/resume`：控制 worker 准入；
- `POST /{runId}/items/{itemId}/retry`：重试符合条件的失败项；
- `POST /reference-census`：生成持久化引用普查；
- `POST /reference-sweep/marks`：在普查和保护检查后标记候选项。

详细请求和响应合同见 [API 文档](/zh/api/)。生产启用顺序应为 `SCAN`、审查快照、演练 `DRY_RUN`，最后仅在批准的回滚窗口内开启 apply 或 sweep 开关。
