# 密钥管理安全文档

## 概述

RecordPlatform 使用 provider-neutral 的文件数据密钥包封边界。文件分片仍由每文件唯一的 `initialKey` 加密，但该数据密钥不会持久化到 `file.file_param`，只会以包封后的 `file_key_envelope.encrypted_data_key` 保存。

当前实现支持两种版本化 provider：

| Provider | 合同版本 | 用途 | 密钥边界 |
|---|---:|---|---|
| `local` | 1 | 本地开发、历史信封兼容、显式配置的独立部署 | 应用进程持有 local master key |
| `vault-transit` | 1 | 生产外部集中式 KMS | 应用只持有最小权限 Vault token，named key 留在 Vault |

新写入只使用显式配置的 active provider。历史读取严格按信封中持久化的 `(kms_provider, provider_contract_version)` 路由，未知 provider、合同版本或 context schema 会失败关闭，不会猜测或回退到 local。

P3-3 在 provider 边界之上增加闭集运行时 suite registry 和租户策略。当前真正可执行的能力如下；目录中出现不等于实现可用：

| 类型 | Stable suite ID | Provider/合同 | 新写 | 历史读 |
|---|---|---|---|---|
| 内容加密 | `RP-AES256-GCM-CHUNK-CHAIN-V1` | `file-content`/1 | 支持 | 支持 |
| 内容加密 | `RP-AES256-GCM-FRAMED-V2` | `file-content`/2 | 支持 | 支持 |
| 密钥包封 | `AES-256-GCM` | `local`/1 | 支持 | 支持 |
| 密钥包封 | `VAULT-TRANSIT-AES256-GCM96-DERIVED` | `vault-transit`/1 | 支持 | 支持 |
| 信封签名/KEM | `UNSIGNED-V1` / `NONE-V1` | `none`/1 | 支持 | 支持 |
| 结构证明 | `RP-MERKLE-SHA256-V1` | `merkle-local`/1 | 支持 | 支持 |
| 签名证明 | `JWS-EDDSA-ED25519-V1` + `RP-SIGNED-PROOF-ZIP-V2` | `local-ed25519`/1 | 支持 | 支持 |
| PQC/KEM 草案 | `ML-DSA-65-DRAFT` / `ML-KEM-768-DRAFT` | `unimplemented-pqc`/0 | 禁止 | 禁止 |

ML-DSA/ML-KEM 只用于显式呈现“尚未实现”的评估边界。即使把 `allow-experimental-writes` 设为 `true`，没有 executable provider 且 `productionWriteAllowed=false` 的条目仍不能被选择，不能据此声明 PQC 支持。

## 1. 信封与上下文合同

### 1.1 持久化字段

`file_key_envelope` 保存：

- 逻辑 `key_version`，用于 RecordPlatform 密码套件和既有兼容合同；
- `kms_provider` 与 `provider_contract_version`，用于精确选择 provider 实现；
- `kms_key_id` 与 `provider_key_version`，用于标识 provider-native 包封 key；
- `wrapping_algorithm`、`algorithm_suite` 与 `context_schema`；
- `encrypted_data_key`、可空的 `wrapping_iv` 和规范上下文的 `aad_hash`；
- tenant、file、fileHash、recipient 与生命周期状态。

逻辑 key 版本和 provider-native key 版本是两个不同维度。Vault native 版本来自 ciphertext 的 `vault:vN:` 前缀，不得用逻辑 `key_version` 代替。

### 1.2 Context schema

`rp-file-envelope-aad-v1` 是不可变的历史 local 合同，UTF-8 字节顺序永久固定为：

```text
tenantId|fileId|fileHash|recipientType|recipientId|keyVersion|algorithmSuite
```

`rp-file-envelope-context-v2` 用于外部 provider，规范字段绑定 tenant、file、fileHash、recipient 和 algorithm suite。Vault derived key 的 `context` 只接收：

```text
base64(SHA-256(canonical-v2-bytes))
```

原始 tenant ID、file ID、file hash、recipient 和 suite 不发送给 Vault。v2 context 不包含逻辑或 provider-native key version，因此同一 Vault named key 的版本升级可通过服务端 `rewrap` 完成；ciphertext 前缀仍认证实际 provider key 版本。

每次 unwrap/rewrap 都会先按持久化 schema 重建规范字节，并使用常量时间比较 `aad_hash`。schema 未知、字段缺失或摘要不匹配时，不会调用 provider。

## 2. Vault Transit 部署

### 2.1 Key 与策略

Vault key 必须是 derived `aes256-gcm96` Transit key。应用 token 只需要目标 key 的以下权限：

```hcl
path "transit/encrypt/record-platform-file-key" {
  capabilities = ["update"]
}

path "transit/decrypt/record-platform-file-key" {
  capabilities = ["update"]
}

path "transit/rewrap/record-platform-file-key" {
  capabilities = ["update"]
}
```

应用使用的接口为：

- `POST /v1/{mount}/encrypt/{key}`：传递 Base64 plaintext、派生 context 和显式 `key_version`；
- `POST /v1/{mount}/decrypt/{key}`：传递持久化的 `vault:vN:` ciphertext 和同一 context；
- `POST /v1/{mount}/rewrap/{key}`：仅用于同一 named key 和相同 v2 context 的版本升级。

应用使用 Java 21 `HttpClient`，禁用重定向，并限制连接超时、请求超时、请求体和响应体大小。token 只存在于配置绑定对象和单次 `X-Vault-Token` header 中。

### 2.2 生产配置

```yaml
file:
  key-envelope:
    active-provider: vault-transit
    active-provider-contract-version: 1
    providers:
      vault-transit:
        address: https://vault.example.com
        token: ${FILE_KEY_ENVELOPE_VAULT_TOKEN}
        namespace: ${FILE_KEY_ENVELOPE_VAULT_NAMESPACE:}
        mount: transit
        key-name: record-platform-file-key
        key-version: 1
        allow-http: false
        connect-timeout: 2s
        request-timeout: 5s
        max-request-bytes: 65536
        max-response-bytes: 65536
```

生产 profile 强制 HTTPS。地址、token、mount、key name、版本、超时或资源边界无效时启动失败。生产不得通过 `allow-http` 绕过该限制。

Vault token 必须由部署 secret 系统注入，不能提交到 Git、写入 Nacos 明文、镜像层、命令历史或普通应用日志。轮换 Vault token 时，不需要改写现有信封；轮换 named key 或 provider 时，必须保留旧 key/provider 的读取能力直到信封迁移完成。

### 2.3 Community 与 HSM 边界

CI 使用固定官方镜像 `hashicorp/vault:1.21.4` 的 dev server，真实验证 Transit wrap/unwrap、context tamper、key rotate + native rewrap、permission denied 和 missing key。GitHub Actions 会读取 Failsafe XML，并要求 5 个测试、0 skip、0 failure、0 error。

这项证据只证明 Vault Community Transit HTTP API 合同，不证明 HSM 托管、FIPS 认证或生产高可用。若生产要求 KEK 处于 HSM 边界，最低部署形态为：

- Vault Enterprise 与对应许可证；
- PKCS#11 seal wrap，或受支持的 Managed Keys；
- 已验证的 HSM 驱动、分区、凭据、备份和灾难恢复流程；
- 多节点 Vault HA、存储后端、unseal/recovery 和审计设备设计；
- 对实际硬件、固件、Vault 版本和故障切换路径进行独立验收。

不能把 Community dev 容器测试表述为 HSM 验证。

## 3. Local provider 安全边界

`local` 合同 v1 保持历史 AES-256-GCM ciphertext、12 字节 IV、SHA-256 master-key 派生、版本 map 解析和 AAD v1 字节完全不变。

基础 profile 为本地开发兼容，可在未设置 `FILE_KEY_ENVELOPE_MASTER_KEY` 时复用 `JWT_KEY`。`application-prod.yml` 会移除该回退，并要求：

- 显式选择 `local`；
- 配置非空 `FILE_KEY_ENVELOPE_LOCAL_KEY_ID`；
- 切换 local key id 时，将仍有历史信封引用的旧值加入 `FILE_KEY_ENVELOPE_LOCAL_HISTORICAL_KEY_IDS`，直至轮换完成；
- master key 至少 32 字符；
- master key 与 `spring.security.jwt.key` 不相等。

local provider 不能抵御应用主机完全失陷。生产优先使用外部 KMS；若因部署约束选择 local，master key 必须由独立 secret 系统管理，并保留旧版本映射直至历史信封全部轮换。

## 4. 错误、审计和可观测性

Provider 错误按稳定内部分类处理：

| 条件 | 分类 | 重试语义 |
|---|---|---|
| Vault 400、无效请求、context/ciphertext 不一致 | `INVALID_REQUEST` / `INVALID_CIPHERTEXT` | 不重试 |
| Vault 403（含仅有 update 权限时访问不存在的 named key） | `PERMISSION_DENIED` | 不重试 |
| Vault 404 | `KEY_NOT_FOUND` | 不重试 |
| Vault 429 | `THROTTLED` | 可重试 |
| Vault 5xx、网络不可用 | `UNAVAILABLE` | 可重试 |
| 请求超时 | `TIMEOUT` | 可重试 |
| malformed success response | `INVALID_RESPONSE` | 不重试 |
| 未注册 provider/contract 或配置无效 | `CONFIGURATION` | 不重试 |

Vault 没有可依赖的公开 disabled-key 独立状态码，因此实现不会解析原始错误文案来伪造该分类。所有失败只转换为项目既有 `GeneralException` 或 `RetryableException`，原始 Vault `errors` 文本不会进入异常、审计或健康详情。

`file_key_audit_log` 记录 provider ID、合同版本、provider key version、wrapping algorithm、algorithm suite、稳定 failure category，以及 `kms_key_id` 的 SHA-256 指纹。它不记录原始 key ID、明文 DEK、wrapped blob、IV、token、context 原值或 provider error body。

Micrometer 指标只使用低基数标签 `provider`、`operation`、`outcome` 和 `failure_category`。Actuator health 只暴露 active provider ID、合同版本、能力列表、availability 和安全配置状态，不使用 `Health.withException`。

Suite 决策指标为 `app.crypto.suite.decision`，标签只包含 `type`、`operation`、`outcome` 和闭集 `reason`；proof provider 分派指标为 `app.crypto.proof_signing.decision`。两者都不允许 tenant、file、key ID、任意请求 suite 文本或 provider 错误体成为标签。稳定失败原因区分 `UNKNOWN_SUITE`、`TYPE_MISMATCH`、`PROVIDER_MISMATCH`、`UNSUPPORTED`、`EXPERIMENTAL_NOT_ALLOWED`、`DEPRECATED_FOR_WRITE`、`DISABLED_FOR_READ`、`DOWNGRADE_BLOCKED` 和 `REENCRYPT_REQUIRED`。

日志脱敏器覆盖 `encryptedDataKey`、`wrappingIv`、`kmsKeyId`/`keyId`、`ciphertext`、Vault token 和 wrapping context 等字段。密钥模型的 `toString()` 对 plaintext、ciphertext、IV 和 key ID 进行固定脱敏。

## 5. 自动轮换与事务语义

管理员可继续使用 `POST /api/v1/admin/files/{id}/key-envelopes/rotate` 对单文件执行显式轮换；租户级自动轮换控制面位于 `/api/v1/admin/key-rotation`。所有入口均要求当前租户管理员权限。

租户策略冻结目标 provider 合同、provider key version、逻辑 key version、批量/限流、调度、最大尝试次数、指数退避、claim lease 和回滚宽限期。每次 manual、scheduled 或 dry-run 都创建不可变 run；候选扫描以创建时的 `snapshot_max_envelope_id` 为上界并使用 keyset cursor，不会一次加载全租户信封。

目标相等性使用完整身份：

```text
(provider, contract, keyId, providerKeyVersion,
 logicalKeyVersion, wrappingAlgorithm, contextSchema)
```

同一 Vault named key 和 v2 context 优先调用 Transit `rewrap`；为了证明新信封可恢复同一 DEK，编排层仍会分别解封源和候选并进行常量时间比较。跨 provider、named key 或 schema 使用受控 unwrap → wrap。候选先以 `PENDING_VERIFICATION` 保存，不参与任何下载授权；校验通过后，短事务锁定源和候选，通过 CAS 将旧 `ACTIVE` 改为 `SUPERSEDED`，再将候选改为 `ACTIVE`。任一步失败都会回滚，旧信封继续可用。

数据库生成列 `active_slot` 和唯一键保证每个 tenant/file/hash/recipient 最多一个可读信封。share/friend-share 撤销与轮换使用相同的行锁/CAS 边界：撤销先发生时候选会被废弃；轮换先发生时撤销随后锁定并撤销新 active，任务不会恢复已撤销权限。

每个 item 由随机 claim token、过期 lease 和 `FOR UPDATE SKIP LOCKED` 领取。KMS throttle、timeout、unavailable 按策略退避重试；进程在 provider 调用或提交前崩溃时，lease 到期后其他 worker 可重领。候选 ID 固定为 item ID，因此“已激活但 item 未提交”的崩溃重放会识别同一候选并幂等完成。

dry-run 只记录候选，不调用 provider、不改变信封，也不会覆盖上一轮 APPLY 的退休资格。pause/resume 保留 cursor 和尝试次数；cancel 只阻止后续发现/领取，已进入 provider 调用的 item 按 claim fence 收敛；显式 retry 只重置仍被稳定分类为 retryable 的失败项尝试计数，非重试分类修复后必须创建新的 run。

## 6. 运行时策略与弃用治理

租户管理员通过 `/api/v1/admin/crypto-agility` 管理当前租户策略：

- `GET /policy` 返回当前有效策略；没有 tenant row 时 `policyVersion=0`，表示使用已启动校验的 operator defaults；
- `PUT /policy` 必须携带 `expectedVersion`，首次创建为 `0`；过期版本返回稳定的 `POLICY_VERSION_CONFLICT`，不会局部更新；
- `GET /diagnostics` 返回 suite 生命周期、provider 合同/能力和当前策略指纹，不返回 key ID、wrapped blob、token、私钥、公钥内容或 recipient；
- 所有入口要求当前租户管理员，写入请求体不进入 `OperationLog`；成功和失败写入 `tenant_crypto_policy_audit` 时只保留策略 SHA-256 指纹和闭集原因。

新信封和新 proof 使用一次性解析的当前租户策略。历史信封/proof 只按记录中持久化的 suite/provider/contract 路由；修改默认值或租户策略不会重解释历史 ciphertext/JWS。`DEPRECATED` 允许历史读但拒绝新写，`DISABLED` 同时拒绝读写。未知 ID、类型混用、provider 合同漂移或能力不一致不会回退到当前默认。

Operator 可在 `crypto.agility.suite-lifecycle` 中仅收紧内建条目。例如：

```yaml
crypto:
  agility:
    production-mode: true
    allow-experimental-writes: false
    suite-lifecycle:
      RP-AES256-GCM-CHUNK-CHAIN-V1:
        deprecated-at: 2027-01-01T00:00:00Z
        disabled-at: 2028-01-01T00:00:00Z
```

未知条目、把 experimental/unsupported 提升为 active、早于 introduced time 的窗口或 `disabledAt < deprecatedAt` 会阻止启动。内容套件从 chunk-chain v1 切换为 framed v2 需要重新加密内容，不能通过 envelope rewrap 伪装完成；local 与 Vault wrapping suite 间允许迁移，但仍要遵守 P3-2 的候选验证和 active CAS。

详细变更步骤、诊断和回滚门禁见[运行时密码敏捷运维手册](../operations/crypto-agility.md)。

## 7. 下载密钥交付与暴露面

### 7.1 `grant-v1` 默认合同

加密下载的 `download-metadata` 和 decrypt-info 默认不再携带 plaintext `initialKey`。owner、friend-share、认证分享和公开分享在现有授权检查通过后返回短期 `keyGrant`。浏览器为单次下载创建内存会话标识，通过 `X-Download-Session-ID` 绑定 metadata 请求，并只在即将解密时把 grant 引用和同一会话放入 POST JSON 请求体：

| 场景 | Metadata/decrypt-info | Consume |
|---|---|---|
| owner / friend-share | `GET /api/v1/files/hash/{fileHash}/download-metadata` 或 `/decrypt-info` | `POST /api/v1/files/key-grants/consume` |
| 认证分享 | `GET /api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info` | `POST /api/v1/files/key-grants/consume` |
| 公开分享 | `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info` | `POST /api/v1/public/key-grants/consume` |

grant 引用是 256-bit Base64URL 随机值，只允许出现在 POST body 和当前下载执行作用域，不进入 URL、query、referrer、任务持久化、日志、指标或错误。Redis key 只含引用的 SHA-256 摘要；hash 字段绑定 tenant、访问类型、文件 ID/hash/version、recipient、精确信封、provider/contract/key version、suite、会话摘要和签发/到期时间。actor 或规范化可信客户端身份与高熵下载会话共同摘要，避免 Redis 只读者直接对低熵用户 ID/IP 做离线字典反推；Redis 不保存原始身份、引用、分享码、预签名 URL、wrapped key 或 plaintext key。

签发与 TTL 设置由同一 Lua 脚本原子完成。消费 Lua 把 `ISSUED` 原子推进到 `CONSUMED`。默认允许首次消费后在 10 秒内由同 actor/client 和同会话重试一次，用于响应丢失或 provider 短暂失败；它不会缓存 DEK，每次允许的尝试都会重新加载精确文件版本并要求原绑定信封仍为 `ACTIVE`。第三次、跨会话、跨 actor、跨租户、跨公开客户端身份、过期或损坏状态均拒绝。信封轮换/supersede/revoke、分享取消/到期/可见性收紧、好友分享取消或接收者变化都会在解封前重新核验并使旧 grant 失败，因此 grant 不会延长底层授权寿命。

metadata、decrypt-info 和 consume 响应使用 `Cache-Control: no-store`（认证响应同时为 `private`）及 `Pragma: no-cache`。consume 采用独立的每用户或可信客户端 IP 20 次/60 秒限流。常规请求日志把 grant/session 视为敏感字段并跳过 key 路由响应体；`OperationLog` 不保存请求或响应 payload。`file_key_audit_log` 和 `app.file.key.grant` 只使用稳定操作、访问类型、结果和闭集原因，不记录引用、会话、客户端 IP 或密钥材料。通用 `FileVO.fileParam` 响应还会递归剥离历史明文/包封 key、key 标识、grant/session 与下载 URL 字段，损坏 JSON 失败关闭为 `null`，不能作为 decrypt-info 之外的旁路。

### 7.2 浏览器生命周期与限制

前端下载任务和恢复数据不包含 `initialKey`、grant 或 session。每次恢复都会重新授权并取得新 grant。framed v2 把 Base64 DEK 解码到可变 `Uint8Array`，立即导入 non-extractable HKDF `CryptoKey`，并在 `finally` 中覆盖原始数组；frame AES key 由 HKDF `deriveKey` 生成。legacy AES-GCM 每分片导入 non-extractable key 并覆盖解码字节。浏览器 WebCrypto 不提供 JavaScript 可验证的物理内存清零保证，Base64 字符串也不可原地覆盖；legacy ChaCha20-Poly1305 库仍需要短暂 raw key bytes。因此这里的保证是缩短生命周期、消除持久化和常规观测面，而不是声称浏览器内存取证不可能。

### 7.3 `plaintext-v0` 迁移窗口

旧协议只有在客户端显式发送 `X-Key-Delivery-Protocol: plaintext-v0`、服务端显式开启兼容开关且当前时间早于硬截止时间时才返回 `initialKey`。安全默认关闭：

```yaml
file:
  key-delivery:
    grant-ttl: 60s
    retry-window: 10s
    max-same-session-retries: 1
    legacy-plaintext-enabled: false
    legacy-plaintext-not-after: 2026-10-01T00:00:00Z
```

迁移期间先按 owner、friend-share、认证分享、公开分享分别观测 `legacy` 指标和审计；确认所有受支持客户端发送 `grant-v1`、恢复/并发下载成功、连续一个发布周期无 `plaintext-v0` 使用后，保持开关关闭并删除旧客户端支持。回滚只能在截止时间前短期开启，必须有变更单、告警和新的提前关闭时间；到达截止时间后即使误开开关也会失败关闭。不得延后截止时间来代替客户端迁移。

## 8. 数据库迁移与恢复

`V1.18.0__key_wrapping_provider_metadata.sql` 增加 provider-neutral 元数据；`V1.19.0__automated_key_rotation.sql` 前向增加自动轮换治理；`V1.20.0__runtime_crypto_agility.sql` 再增加运行时 suite/provider 身份和租户策略：

- 新增 provider contract version、provider key version 和 context schema；
- 将 `wrapping_iv` 改为可空，以支持 Vault ciphertext；
- 扩展 `kms_key_id` 并增加完整目标索引；
- 扩展 provider-neutral 审计字段；
- 将历史 local 行确定性回填为 local contract v1、AAD v1，provider key version 来自既有逻辑版本。
- 迁移前检查历史数据是否存在同 recipient 多个 `ACTIVE`；存在歧义时失败关闭，要求人工核对后重试；
- 通过生成列唯一键约束单 active，并新增 policy/run/item/audit 四张表；
- run trigger、item source/candidate、claim token/lease 和索引共同提供幂等与多 worker 栅栏。
- 把历史信封空白的 signature/KEM/proof 字段确定性回填为 `UNSIGNED-V1`、`NONE-V1`、`RP-MERKLE-SHA256-V1` 后改为 `NOT NULL`；
- 把既有 signed-proof v2 的 provider/signature/proof 身份回填为 `local-ed25519`/1、`JWS-EDDSA-ED25519-V1`、`RP-SIGNED-PROOF-ZIP-V2`，移除兼容默认后强制未来写入显式提供；
- 新增 `tenant_crypto_policy` 与脱敏 audit 表，唯一键保证每租户一条有效策略，复合外键禁止 audit 跨租户引用 policy。

已发布迁移不会改写。CI 除既有 V1.19 门禁外，还要求 `RuntimeCryptoAgilityMigrationIT` 的 fresh install、V1.19 upgrade/backfill、mandatory/tenant constraint、concurrent row lock 四个真实 MySQL 8 场景全部执行且零跳过。

数据库备份必须与 Vault snapshot/raft storage、unseal/recovery 材料和历史 key 可用性一起设计。只恢复数据库但无法访问对应 provider/key 时，wrapped DEK 无法解封。灾难恢复演练至少验证：

1. 历史 local v1 信封可读取；
2. 当前与旧 Vault key version 均可 decrypt；
3. Vault token 轮换不影响历史信封；
4. 恢复后 audit、tenant 和 recipient 隔离仍有效。

## 9. 已知限制与后续边界

- Vault 可用性会直接影响加密文件下载；当前只提供稳定可重试语义，不在请求内做无界重试，也不回退 local。
- TLS 终止点、浏览器进程和执行中的页面仍会在 consume 响应/解密期间短暂接触 plaintext DEK；`grant-v1` 不等同于端到端硬件隔离。
- Redis 是 grant 可用性依赖；不可用时加密下载失败关闭，不回退到 plaintext。
- 当前不存在可执行的 PQC/KEM provider，ML-DSA/ML-KEM 目录项不构成实现或合规声明。
- 当前 proof 签名执行 provider 只有 `local-ed25519`/1；registry 已冻结历史路由合同，但新增外部 signer 必须以独立 adapter、真实集成测试和迁移计划验收。
- AWS KMS adapter 需在仓库具备受控 AWS 账户、OIDC role 和测试 key 后单独验收，不能用 mock 冒充真实云 KMS 证据。

## 10. 运维检查清单

- [ ] 生产 active provider 与合同版本显式配置。
- [ ] Vault 地址为 HTTPS，token 为最小权限且由 secret 系统注入。
- [ ] Vault key 为 derived `aes256-gcm96`，目标版本与部署变更单一致。
- [ ] 选择 local 时，master key 独立于 JWT 且至少 32 字符。
- [ ] 历史 provider/key 读取能力在信封轮换完成前持续保留。
- [ ] 先执行 dry-run，确认候选数量、recipient 分布和目标 provider version，再启动 APPLY。
- [ ] `remaining` 与 `failed` 归零且回滚宽限期结束前，不禁用或删除旧 provider key。
- [ ] 退休只由外部变更流程执行；RecordPlatform 仅记录 READY 和管理员 acknowledgement。
- [ ] 审计、指标、health 和日志采集链路中不存在 token、plaintext DEK、wrapped blob 或原始 key ID。
- [ ] `FILE_KEY_DELIVERY_LEGACY_PLAINTEXT_ENABLED=false`，TTL/重试窗口保持短且 Redis HA/ACL/传输加密符合部署基线。
- [ ] 代理不会缓存 metadata/decrypt-info/consume 响应，也不会把 POST body、grant、session 或 `initialKey` 记录到访问日志、WAF 或 APM payload。
- [ ] `plaintext-v0` 使用量为零且兼容截止时间、客户端移除条件和回滚负责人已登记。
- [ ] 变更租户 suite/provider 策略前保存 diagnostics 和 policy fingerprint，并使用正确 `expectedVersion`。
- [ ] 弃用窗口先进入 `DEPRECATED` 并证明历史读取仍可用；只有历史引用清零后才进入 `DISABLED`。
- [ ] 任何内容加密 suite 变化都走重新加密任务，不使用 rewrap 冒充。
- [ ] PQC/experimental 条目未被生产策略选择，也未对外宣称已支持。
- [ ] 数据库与 Vault 备份、恢复和 HA 故障切换已联合演练。
- [ ] 若声明 HSM-backed，已验证 Enterprise 许可证、PKCS#11/Managed Keys、实际硬件与故障切换证据。

## 11. 参考资料

- [Vault Transit secrets engine](https://developer.hashicorp.com/vault/docs/secrets/transit)
- [Vault Transit HTTP API](https://developer.hashicorp.com/vault/api-docs/secret/transit)
- [Vault seal wrap](https://developer.hashicorp.com/vault/docs/enterprise/sealwrap)
- [Vault Managed Keys](https://developer.hashicorp.com/vault/docs/enterprise/managed-keys)
- [NIST SP 800-57 Part 1 Revision 5](https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final)
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)

---

**文档版本**：v5.0
**最后更新**：2026-07-27
**维护者**：Security Team
