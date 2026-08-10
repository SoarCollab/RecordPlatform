# API 参考

RecordPlatform REST API 索引文档（与当前 Controller 和安全配置对齐）。

## 交互式文档

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI 规范**: http://localhost:8000/record-platform/v3/api-docs

Swagger Basic 认证（Knife4j）：`admin` / `123456`（默认，可配置）。

## 基础 URL

```text
http://localhost:8000/record-platform
```

## 认证与公开规则

### 1) 常规认证

大多数端点需要 JWT：

```http
Authorization: Bearer <token>
```

### 2) 明确公开端点（`permitAll`）

根据 `SecurityConfiguration`：

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/verification-codes`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/password-resets/confirm`
- `PUT /api/v1/auth/password-resets`
- `GET /api/v1/shares/{shareCode}/info`
- `GET /api/v1/shares/{shareCode}/files`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/download-metadata`
- `GET /api/v1/images/download/images/**`
- `GET /api/v1/public/proofs/{proofId}/status`
- `GET /api/v1/public/proof-keys/{keyId}/versions/{keyVersion}`
- `GET /api/v1/sse/connect`（需短期令牌，见下文）

匿名公开分享面仅限上面的五条分享类精确 `GET` 路由；其他分享路由不会因此隐式公开。

### 3) SSE 双令牌模式

- `POST /api/v1/auth/tokens/sse`：需要常规 JWT（登录态）
- `GET /api/v1/sse/connect?token=...&x-tenant-id=...`：公开路由，但必须携带有效一次性短期令牌。租户 query 值只用于定位 Redis namespace，连接的租户、用户和角色以消费后的短令牌为准。

## API 端点（按模块）

### 认证（`/api/v1/auth`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/verification-codes` | 请求邮件验证码 |
| POST | `/api/v1/auth/register` | 用户注册 |
| POST | `/api/v1/auth/password-resets/confirm` | 密码重置确认 |
| PUT | `/api/v1/auth/password-resets` | 执行密码重置 |
| POST | `/api/v1/auth/tokens/refresh` | 刷新访问令牌 |
| POST | `/api/v1/auth/tokens/sse` | 获取 SSE 短期令牌（需 JWT） |

> 登录/登出接口由 Spring Security 处理（非 Controller 直接声明）：`POST /api/v1/auth/login`、`POST /api/v1/auth/logout`

### 用户（`/api/v1/users`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/users/info` | 获取用户信息 |
| PUT | `/api/v1/users/info` | 更新用户信息 |
| PUT | `/api/v1/users/email` | 修改邮箱 |
| PUT | `/api/v1/users/password` | 修改密码 |

### 文件上传（`/api/v1/upload-sessions`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/upload-sessions` | 开始分片上传 |
| POST | `/api/v1/upload-sessions/direct` | 开始对象存储直传并返回预签名 URL |
| PUT | `/api/v1/upload-sessions/{clientId}/chunks/{chunkNumber}` | 上传分片 |
| POST | `/api/v1/upload-sessions/{clientId}/complete` | 完成上传 |
| POST | `/api/v1/upload-sessions/{clientId}/direct/complete` | 完成对象存储直传 |
| POST | `/api/v1/upload-sessions/{clientId}/pause` | 暂停上传 |
| POST | `/api/v1/upload-sessions/{clientId}/resume` | 恢复上传 |
| DELETE | `/api/v1/upload-sessions/{clientId}` | 取消上传 |
| DELETE | `/api/v1/upload-sessions/{clientId}/direct` | 中止对象存储直传 |
| GET | `/api/v1/upload-sessions/{clientId}` | 检查上传状态 |
| GET | `/api/v1/upload-sessions/{clientId}/progress` | 查询上传进度 |

### 文件与分享（`/api/v1/files`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/files/{id}` | 按文件 ID 查询详情 |
| GET | `/api/v1/files/{id}/proof-bundle` | 按文件 ID 导出可验证证明包 |
| GET | `/api/v1/files/attestation-leaves/{leafId}/proof-bundle` | 按存证叶子 ID 导出可验证证明包 |
| GET | `/api/v1/files/{id}/proof-bundle.zip` | 按文件 ID 导出确定性 Ed25519 签名证明 ZIP |
| GET | `/api/v1/files/attestation-leaves/{leafId}/proof-bundle.zip` | 按存证叶子 ID 导出确定性签名证明 ZIP |
| POST | `/api/v1/files/attestation-leaves/{leafId}/proof-status/revoke` | 幂等撤销当前用户有权管理的签名证明 |
| GET | `/api/v1/files/hash/{fileHash}` | 按文件哈希查询详情 |
| GET | `/api/v1/files` | 用户文件分页（支持 `keyword`、`keywordMode=FUZZY/PREFIX/EXACT_HASH/AUTO`、`status`、`startTime`、`endTime`） |
| GET | `/api/v1/files/stats` | 用户文件统计 |
| GET | `/api/v1/files/hash/{fileHash}/download-metadata` | 获取基于 manifest 的预签名分片下载元数据 |
| GET | `/api/v1/files/hash/{fileHash}/addresses` | 获取仅 URL 下载地址 |
| GET | `/api/v1/transactions/{transactionHash}` | 查询链上交易信息 |
| GET | `/api/v1/files/hash/{fileHash}/chunks` | 后端 byte payload 下载 |
| GET | `/api/v1/files/hash/{fileHash}/decrypt-info` | 获取解密信息（登录态） |
| POST | `/api/v1/files/key-grants/consume` | 消费登录态短期下载密钥 grant |
| GET | `/api/v1/shares/{shareCode}/files` | 公开分享文件列表（公开） |
| GET | `/api/v1/files/shares` | 获取我的分享列表 |
| DELETE | `/api/v1/files` | 批量删除（支持 hash/id） |
| DELETE | `/api/v1/files/{id}` | 按文件 ID 删除（管理员） |
| POST | `/api/v1/shares` | 创建分享 |
| PATCH | `/api/v1/shares/{shareCode}` | 更新分享 |
| DELETE | `/api/v1/files/share/{shareCode}` | 取消分享 |
| POST | `/api/v1/shares/{shareCode}/files/save` | 保存分享文件到我的文件 |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/chunks` | 登录态分享下载 |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info` | 登录态分享解密信息 |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/download-metadata` | 登录态分享的 manifest 下载元数据 |
| GET | `/api/v1/files/share/{shareCode}/access-logs` | 分享访问日志（管理员） |
| GET | `/api/v1/files/share/{shareCode}/stats` | 分享访问统计（管理员） |
| GET | `/api/v1/files/{id}/provenance` | 文件溯源链路（管理员） |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/chunks` | 公开分享下载（公开） |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info` | 公开分享解密信息（公开） |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/download-metadata` | 公开分享的 manifest 下载元数据（公开） |
| POST | `/api/v1/public/key-grants/consume` | 消费公开分享短期密钥 grant（公开） |
| POST | `/api/v1/files/download-batches/report` | 上报批量下载质量指标 |
| GET | `/api/v1/files/{id}/versions` | 查询文件版本链列表 |
| POST | `/api/v1/files/{id}/versions` | 将文件标记为新版本的父版本 |

自有文件 `download-metadata` 要求 active `cn.flying.chunk-manifest.v1`。成功响应包含 `canonicalManifestJson`、`manifestStatus=ACTIVE`、`manifestClassification=ALREADY_MANIFEST`、`manifestErrorCode=null`、`legacyDownloadAllowed=false`。缺失 manifest 时返回 `FILE_RECORD_ERROR`；标准 `ErrorPayload` 把四个机器可读治理字段放在 `data.detail` 下，未分类默认值为 `REUPLOAD_REQUIRED / UNCLASSIFIED / MISSING_MANIFEST_UNCLASSIFIED / false`。客户端不得解析 `message` 或自行推断 legacy 回退。详见[分片 Manifest 与历史数据治理](/zh/architecture/chunk-manifest)。

加密 metadata/decrypt-info 客户端发送 `X-Key-Delivery-Protocol: grant-v1` 和密码学随机、仅存在内存中的 `X-Download-Session-ID`。响应返回 `keyGrant`，不返回 plaintext `initialKey`。在解密前通过对应 POST 接口提交 `{ "grantReference": "...", "sessionId": "..." }` 即时消费，二者都不得放入 URL。登录态 consume 按用户 20 次/60 秒限流；公开 consume 按规范化可信客户端 IP 20 次/60 秒限流，不要求 Bearer 或 `X-Tenant-ID`。metadata、decrypt-info 和 consume 响应都为 `no-store`；短窗内允许的一次同会话重试只用于响应丢失/provider 失败，不能跨 tab、用户、会话或客户端共享 grant。未加密文件不返回 grant 或 key。`plaintext-v0` 只用于显式、服务端默认关闭且有硬截止时间的迁移，新客户端不得依赖。

精确的匿名公开分享合同仅包含 `GET /api/v1/shares/{shareCode}/info`、`GET /api/v1/shares/{shareCode}/files`、`GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`、`GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`、`GET /api/v1/public/shares/{shareCode}/files/{fileHash}/download-metadata` 和 `POST /api/v1/public/key-grants/consume`。这些路由既不需要 Bearer，也不需要租户头。调用者即使提供 `X-Tenant-ID=0`、其他租户或畸形值，该值也会在授权和数据选择中被忽略。后端只从匹配的 `shareCode` 元数据解析 owner tenant；跨租户范围仅覆盖这次元数据查询，后续文件、key envelope、访问计数和分享访问审计都在 owner tenant 内完成。匿名 `sys_operation_log` 固定使用 system tenant `0`；产生的 `share_access_log` 使用 owner tenant。两类审计使用同一个规范化可信客户端 IP。

公开 chunks、decrypt-info 与 download-metadata 路由按 `rate:limit:public:share-access:v2:ip:<canonical-ip>` 共享一个无租户的固定 30 次/60 秒桶。除非 direct peer 命中已配置的 trusted-proxy allowlist 且提供合法代理链，否则修改 `X-Tenant-ID`、JWT 角色、端点、`X-Forwarded-For` 或 `X-Real-IP` 都不能拆分该桶。前 30 次合计请求可以进入 controller；当前第 31 次仍保持 HTTP 200，并返回业务码 `70005`。分享公开性、有效/过期状态、未知类型/状态和 included-file 校验继续失败关闭。当前模型没有分享密码字段；密码分享需要另建端到端功能任务。分享写入、保存分享文件到当前用户空间，以及登录态 `/api/v1/shares/{shareCode}/files/{fileHash}/chunks`、`/decrypt-info` 和 `/download-metadata` 路由仍必须携带 Bearer。

所有 owner 与 share-code metadata 都携带 `accessIdentity`。当某个分片返回 401/403 时，浏览器最多刷新一次 metadata；只有 file/version/manifest/suite/access identity 及所有稳定分片字段均未变化时，才会从当前分片边界恢复。身份漂移、二次过期、刷新失败、取消、截断或完整性错误都会 abort sink。公开/认证分享与自有文件共用 64 MiB 内存硬上限和事务型 File System Access 路径。

`.zip` 是当前规范合同：固定八个根条目、固定顺序/时间戳/STORED metadata，canonical `manifest.json` 摘要绑定六个证据条目，`issuer-signature.jws` 使用专用 Ed25519 key 对 manifest 字节做 compact JWS 签名。导出前重新校验租户/owner、原文件 `contentHash`、active manifest、存储 HEAD、`MANIFEST_HASH` Merkle 路径、已完成 batch 和不可变 contract registry。完成批次只接受携带合法 32-byte 交易哈希的 `CHAIN_WRITE`，或交易哈希为空的两种链查询恢复来源；32-byte 链根必须等于 Merkle 根。`contentHash`、`chainRecordId`、`manifestHash`、`cipherHash`、`merkleRoot`、`abiFingerprint` 不允许互换。单条目上限 1 MiB，总逻辑 payload 上限 4 MiB，额外条目、嵌套路径和路径穿越均失败关闭。

两个 ZIP 路由共享同一个租户/用户限流桶：普通用户每分钟 10 次，管理员和监控员每分钟 30 次。每个应用实例最多并发执行 8 个完整证明校验，每个证明最多接受 128 个 manifest 分片，并对 storage HEAD 校验施加 60 秒总预算。并发隔离饱和或存储预算耗尽属于可重试失败，不会把证明推进为 `INVALID`。

签名 JSON schema 固定为 `record-platform-proof-manifest.v2`、`record-platform-proof-chunk-manifest.v2`、`record-platform-proof-merkle.v2`、`record-platform-proof-chain-receipt.v2` 和 `record-platform-proof-verification-policy.v2`。Canonical JSON 按字母排序 object key，并省略值为 null 的可选字段。被签名的 policy 以机器可读形式携带来源/交易/根模式、完整 registry 规则与指纹字段顺序、生命周期规则以及 ZIP/文本编码规则。`file.hash` 与 ASCII `issuer-signature.jws` 都以且仅以一个 LF 字节结尾。ZIP 成功响应公开 `Content-Disposition`、`Cache-Control`、`X-Proof-Manifest-Hash`；容量或 deadline 失败返回带 `Retry-After: 5` 的 JSON 503。

公开只读接口 `GET /api/v1/public/proofs/{proofId}/status` 返回 `ACTIVE/REVOKED/SUPERSEDED/INVALID` 当前状态；`GET /api/v1/public/proof-keys/{keyId}/versions/{keyVersion}` 返回版本化 SPKI/指纹。二者无需 JWT 或租户头，且不返回租户、内部文件/叶子 ID 或私钥；无论 JWT tenant/role 如何，都按规范化可信客户端 IP 累计共享一个无租户、固定 120 次/60 秒桶，因此两个端点合计第 121 次请求会被拒绝。默认身份是 direct socket peer；只有立即 peer 命中显式配置的数字 trusted-proxy allowlist 时才使用转发头。`statusVersion` 是十进制 JSON 字符串。`issuedAt` 是第一次 Ed25519 签名真正成功的时间，不回溯为 leaf/batch 创建时间；确定性重建复用持久化时间与原 JWS。签名 manifest 中的 `issuedStatus` 只允许 `ACTIVE/SUPERSEDED`，验证方必须查询 `statusLocation`。`INVALID` 是终态，只能用于已持久化 canonical manifest、JWS、签名 key 身份或不可变签发快照发生确定性漂移，固定原因为 `immutable_snapshot_validation_failed`；storage、Merkle、registry、receipt 或其他依赖/读取失败只拒绝当前导出，不改变生命周期。租户内 owner 或管理员可撤销；撤销接口按用户 10 次/60 秒限流（管理员/监控角色 30 次），`REVOKED`、`INVALID` 不能恢复或再次撤销。旧 JSON 端点继续返回未签名 `proof-bundle.v1.1` 以兼容现有集成，但已弃用。该合同中的 `file.fileHash` 是链记录 ID 与 Merkle 叶子 preimage；原文只由有序 `storage.objects[].plainHash` 和 `plainSize` 绑定，`size` 继续表示存储对象/密文长度。新导出总是包含 `plainSize`。历史缺失值只有在规范化 `plainHash == cipherHash` 时才可回退到 `size`，否则在精确字段失败关闭。上述证据禁止相互替代；即使结构全部自洽，未签名 JSON 仍只能返回 `AUTHENTICITY_NOT_VERIFIED`，因为整个 JSON 可被重写，不能作为签名真实性凭据。

### 管理员文件审计（`/api/v1/admin/files`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/files` | 获取所有文件（分页） |
| GET | `/api/v1/admin/files/{id}` | 文件详情（含审计信息） |
| PUT | `/api/v1/admin/files/{id}/status` | 更新文件状态 |
| DELETE | `/api/v1/admin/files/{id}` | 强制物理删除文件 |
| GET | `/api/v1/admin/files/shares` | 获取所有分享（分页） |
| DELETE | `/api/v1/admin/files/shares/{shareCode}` | 强制取消分享 |
| GET | `/api/v1/admin/files/shares/{shareCode}/logs` | 分享访问日志 |
| GET | `/api/v1/admin/files/shares/{shareCode}/stats` | 分享访问统计 |

### 管理员存证批次生产（`/api/v1/admin/attestation-batches/production`）

两个操作都要求管理员角色，并且只使用认证上下文中的当前租户。

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/admin/attestation-batches/production/trigger` | 为当前租户强制执行一轮有界生产处理 |
| GET | `/api/v1/admin/attestation-batches/production/status` | 查询有效配置、candidate backlog 和 due batch 数 |

### 管理员 Manifest 回填（`/api/v1/admin/manifest-backfill-runs`）

所有操作要求管理员角色，并按当前租户隔离。`SCAN` 创建源快照；`DRY_RUN` 与 `APPLY` 必须提供该快照的外部 ID。Apply、sweep mark、sweep delete 仍使用独立 feature gate。

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/admin/manifest-backfill-runs` | 创建 `SCAN`、`DRY_RUN` 或 `APPLY` 任务 |
| GET | `/api/v1/admin/manifest-backfill-runs` | 查询有界任务历史 |
| GET | `/api/v1/admin/manifest-backfill-runs/{runId}` | 查看单个任务 |
| GET | `/api/v1/admin/manifest-backfill-runs/{runId}/items` | 游标查询分类项（limit 最大 100） |
| POST | `/api/v1/admin/manifest-backfill-runs/{runId}/pause` | 在持久化边界暂停 |
| POST | `/api/v1/admin/manifest-backfill-runs/{runId}/resume` | 从同一快照/游标继续 |
| POST | `/api/v1/admin/manifest-backfill-runs/{runId}/items/{itemId}/retry` | 重试符合条件的失败项 |
| POST | `/api/v1/admin/manifest-backfill-runs/reference-census` | 封存当前引用普查证据 |
| POST | `/api/v1/admin/manifest-backfill-runs/reference-sweep/marks` | 对精确存储对象执行 grace mark |

默认启用回填 worker，但禁用 apply；引用 mark/delete 均默认关闭，并使用 30 天保护窗口。完整状态、分类和默认值见[分片 Manifest 与历史数据治理](/zh/architecture/chunk-manifest)。

### 管理员自动密钥轮换（`/api/v1/admin/key-rotation`）

所有操作要求管理员角色，并只使用认证上下文中的当前租户。策略/run 响应不返回原始 provider key ID；item 响应不返回 recipient ID 和源/候选 envelope ID。执行 APPLY 或外部 key 退休前先阅读[自动密钥轮换运维手册](../../operations/key-rotation.md)。

| 方法 | 端点 | 说明 |
|------|------|------|
| PUT | `/api/v1/admin/key-rotation/policy` | 创建或替换有界租户策略 |
| GET | `/api/v1/admin/key-rotation/policy` | 查询脱敏策略与退休状态 |
| POST | `/api/v1/admin/key-rotation/policy/pause` | 暂停后续调度 |
| POST | `/api/v1/admin/key-rotation/policy/resume` | 恢复后续调度 |
| POST | `/api/v1/admin/key-rotation/policy/disable` | 禁用后续调度 |
| POST | `/api/v1/admin/key-rotation/policy/retirement/acknowledge` | 全部门禁通过后记录外部退休完成 |
| POST | `/api/v1/admin/key-rotation/runs` | 幂等启动 `DRY_RUN` 或 `APPLY` |
| GET | `/api/v1/admin/key-rotation/runs` | 查询有界租户运行历史 |
| GET | `/api/v1/admin/key-rotation/runs/{runId}` | 查询不可变目标与进度计数 |
| GET | `/api/v1/admin/key-rotation/runs/{runId}/items` | 游标分页查询脱敏 item 结果 |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/pause` | 暂停持久化 run |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/resume` | 保留 cursor 和尝试次数恢复 |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/cancel` | 停止后续发现与领取 |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/retry` | 重新排队仍标记为 retryable 的终态失败 item |

### 管理员运行时密码敏捷（`/api/v1/admin/crypto-agility`）

所有操作要求当前租户管理员。响应只包含 stable suite/provider/contract、生命周期、能力和策略指纹，不返回 key ID、token、wrapped blob、私钥或 recipient。更新使用 optimistic `expectedVersion`；`0` 表示首次创建租户覆盖。

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/crypto-agility/policy` | 查询当前租户有效策略和 SHA-256 指纹 |
| PUT | `/api/v1/admin/crypto-agility/policy` | 按期望版本创建或更新新写策略 |
| GET | `/api/v1/admin/crypto-agility/diagnostics` | 查询闭集 suite 生命周期和脱敏 provider 能力 |

历史 envelope/proof 不读取当前默认，而按持久化 provider/contract/suite 精确路由。弃用、禁用、迁移与回滚顺序见[运行时密码敏捷运维手册](../../operations/crypto-agility.md)。

### 管理员完整性告警（`/api/v1/admin/integrity-alerts`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/integrity-alerts` | 查询完整性告警（分页，支持 `status`、`alertType` 过滤） |
| POST | `/api/v1/admin/integrity-alerts/check` | 手动触发完整性校验 |
| PUT | `/api/v1/admin/integrity-alerts/{id}/acknowledge` | 确认完整性告警 |
| PUT | `/api/v1/admin/integrity-alerts/{id}/resolve` | 解决完整性告警（附备注） |

告警响应新增 `severity` 和有界 `evidence`，现有字段保持不变。Manifest 驱动的 `alertType` 包括 `MANIFEST_MISSING`、`MANIFEST_INVALID`、`OBJECT_NOT_FOUND`、`METADATA_MISMATCH`、`CONTENT_HASH_MISMATCH`、`CHAIN_NOT_FOUND`、`CHAIN_MISMATCH`；历史类型仍可读取。

### 配额（`/api/v1/files/quota`、`/api/v1/admin/quota`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/files/quota` | 查询当前用户配额状态 |
| POST | `/api/v1/admin/quota/rollout/audits` | 写入或更新配额灰度审计记录（管理员） |
| GET | `/api/v1/admin/quota/rollout/audits` | 查询配额灰度审计记录（管理员，参数：`batchId`；租户来自认证上下文） |

### 公开分享页（`/api/v1/shares`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/shares/{shareCode}/info` | 获取分享详情（公开） |

### 图片（`/api/v1/images`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/images/upload/avatar` | 上传头像 |
| POST | `/api/v1/images/upload/image` | 上传图片 |
| GET | `/api/v1/images/download/images/**` | 下载图片（公开） |

### 好友（`/api/v1/friends`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/friends/requests` | 发送好友请求 |
| GET | `/api/v1/friends/requests/received` | 收到的请求 |
| GET | `/api/v1/friends/requests/sent` | 发出的请求 |
| PUT | `/api/v1/friends/requests/{requestId}/status` | 接受好友请求 |
| PUT | `/api/v1/friends/requests/{requestId}/status` | 拒绝好友请求 |
| DELETE | `/api/v1/friends/requests/{requestId}` | 取消好友请求 |
| GET | `/api/v1/friends/requests/pending-count` | 待处理好友请求数 |
| GET | `/api/v1/friends` | 好友列表（分页） |
| GET | `/api/v1/friends/all` | 全量好友列表 |
| DELETE | `/api/v1/friends/{friendId}` | 解除好友关系 |
| PUT | `/api/v1/friends/{friendId}/remark` | 更新好友备注 |
| GET | `/api/v1/friends/search` | 搜索用户 |

### 好友文件分享（`/api/v1/friend-shares`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/friend-shares` | 分享文件给好友 |
| GET | `/api/v1/friend-shares/received` | 收到的好友分享 |
| GET | `/api/v1/friend-shares/sent` | 发出的好友分享 |
| GET | `/api/v1/friend-shares/{shareId}` | 分享详情 |
| PUT | `/api/v1/friend-shares/{shareId}/read-status` | 标记已读 |
| DELETE | `/api/v1/friend-shares/{shareId}` | 取消分享 |
| GET | `/api/v1/friend-shares/unread-count` | 未读数量 |

### 会话（`/api/v1/conversations`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/conversations` | 会话列表（分页） |
| GET | `/api/v1/conversations/{id}` | 会话详情与消息列表 |
| GET | `/api/v1/conversations/unread-count` | 未读会话数 |
| PUT | `/api/v1/conversations/{id}/read-status` | 标记会话已读 |
| DELETE | `/api/v1/conversations/{id}` | 删除会话 |

### 消息（`/api/v1/messages`）

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/messages` | 发送私信 |
| GET | `/api/v1/messages/unread-count` | 未读私信总数 |

### 公告（`/api/v1/announcements`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/announcements/latest` | 最新公告 |
| GET | `/api/v1/announcements` | 公告列表 |
| GET | `/api/v1/announcements/{id}` | 公告详情 |
| GET | `/api/v1/announcements/unread-count` | 未读公告数 |
| PUT | `/api/v1/announcements/{id}/read-status` | 标记公告已读 |
| PUT | `/api/v1/announcements/read-status` | 全部公告标记已读 |
| GET | `/api/v1/admin/announcements` | 管理员公告列表 |
| POST | `/api/v1/announcements` | 发布公告（管理员） |
| PUT | `/api/v1/announcements/{id}` | 更新公告（管理员） |
| DELETE | `/api/v1/announcements/{id}` | 删除公告（管理员） |

### 工单（`/api/v1/tickets`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/tickets` | 我的工单列表 |
| GET | `/api/v1/tickets/{id}` | 工单详情 |
| POST | `/api/v1/tickets` | 创建工单 |
| PUT | `/api/v1/tickets/{id}` | 更新工单 |
| POST | `/api/v1/tickets/{id}/reply` | 回复工单 |
| POST | `/api/v1/tickets/{id}/close` | 关闭工单 |
| POST | `/api/v1/tickets/{id}/confirm` | 确认完成 |
| GET | `/api/v1/tickets/pending-count` | 待处理工单数 |
| GET | `/api/v1/tickets/unread-count` | 未读工单数 |
| GET | `/api/v1/admin/tickets` | 管理员工单列表 |
| PUT | `/api/v1/admin/tickets/{ticketId}/assignee` | 分配处理人（管理员） |
| PUT | `/api/v1/admin/tickets/{ticketId}/status` | 更新状态（管理员） |
| GET | `/api/v1/admin/tickets/pending-count` | 管理员待处理工单数 |

### 权限（`/api/v1/system/permissions`，管理员）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/permissions` | 权限树 |
| GET | `/api/v1/system/permissions/list` | 权限分页列表 |
| GET | `/api/v1/system/permissions/modules` | 模块列表 |
| POST | `/api/v1/system/permissions` | 创建权限 |
| PUT | `/api/v1/system/permissions/{id}` | 更新权限 |
| DELETE | `/api/v1/system/permissions/{id}` | 删除权限 |
| GET | `/api/v1/system/permissions/roles/{role}` | 查询角色权限 |
| POST | `/api/v1/system/roles/{role}/permissions` | 授权 |
| DELETE | `/api/v1/system/roles/{role}/permissions/{permissionCode}` | 撤销授权 |

### 系统监控（`/api/v1/system`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/stats` | 系统统计 |
| GET | `/api/v1/system/chain-status` | 链状态 |
| GET | `/api/v1/system/health` | 健康状态 |
| GET | `/api/v1/system/storage-capacity` | 存储容量聚合（集群/节点/故障域） |
| GET | `/api/v1/system/monitor` | 聚合监控指标 |

### 系统审计（`/api/v1/system/audit`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/system/audit/overview` | 审计概览 |
| GET | `/api/v1/system/audit/logs` | 日志分页（GET） |
| POST | `/api/v1/system/audit/logs/query` | 日志分页（POST） |
| GET | `/api/v1/system/audit/logs/{id}` | 日志详情 |
| POST | `/api/v1/system/audit/logs/export` | 导出审计日志 |
| GET | `/api/v1/system/audit/high-frequency` | 高频操作统计 |
| POST | `/api/v1/system/audit/sensitive/page` | 敏感操作分页 |
| GET | `/api/v1/system/audit/error-stats` | 错误统计 |
| GET | `/api/v1/system/audit/time-distribution` | 时间分布 |
| GET | `/api/v1/system/audit/configs` | 审计配置 |
| PUT | `/api/v1/system/audit/configs` | 更新审计配置 |
| POST | `/api/v1/system/audit/anomalies/check` | 检查异常 |
| POST | `/api/v1/system/audit/logs/backups` | 备份日志 |

### SSE（`/api/v1/sse`）

| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/api/v1/sse/connect` | 建立 SSE 连接（短期 token） |
| DELETE | `/api/v1/sse/disconnect` | 断开连接 |
| GET | `/api/v1/sse/status` | 查询连接状态 |

推荐接入流程：

```text
1) POST /api/v1/auth/tokens/sse   (Authorization: Bearer <jwt>)
2) GET  /api/v1/sse/connect?token=<sseToken>&x-tenant-id=<tenantHint>
```

可设置自定义请求头的客户端也可使用 `X-Tenant-ID`；旧 `tenantId` query 参数继续作为 `x-tenant-id` 的兼容别名。提示缺失或与令牌租户不一致、令牌无效/过期/已消费、载荷损坏时，服务端会在创建 emitter 前失败关闭；一次性 token 原文不会写入操作日志或数据库审计参数。

常见事件类型：

- `connected`
- `heartbeat`
- `message-received`
- `file-record-success`
- `file-record-failed`
- `announcement-published`
- `ticket-updated`
- `friend-request`
- `friend-accepted`
- `friend-share`
- `audit-alert`

## 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

- 详细业务错误码请参考：`/docs/zh/api/error-codes.md`
- 更完整字段级说明请参考：`/API_DOCUMENTATION.md`
