# 密钥管理安全文档

## 概述

RecordPlatform 使用 provider-neutral 的文件数据密钥包封边界。文件分片仍由每文件唯一的 `initialKey` 加密，但该数据密钥不会持久化到 `file.file_param`，只会以包封后的 `file_key_envelope.encrypted_data_key` 保存。

当前实现支持两种版本化 provider：

| Provider | 合同版本 | 用途 | 密钥边界 |
|---|---:|---|---|
| `local` | 1 | 本地开发、历史信封兼容、显式配置的独立部署 | 应用进程持有 local master key |
| `vault-transit` | 1 | 生产外部集中式 KMS | 应用只持有最小权限 Vault token，named key 留在 Vault |

新写入只使用显式配置的 active provider。历史读取严格按信封中持久化的 `(kms_provider, provider_contract_version)` 路由，未知 provider、合同版本或 context schema 会失败关闭，不会猜测或回退到 local。

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

## 6. 数据库迁移与恢复

`V1.18.0__key_wrapping_provider_metadata.sql` 增加 provider-neutral 元数据；`V1.19.0__automated_key_rotation.sql` 在其后前向增加自动轮换治理：

- 新增 provider contract version、provider key version 和 context schema；
- 将 `wrapping_iv` 改为可空，以支持 Vault ciphertext；
- 扩展 `kms_key_id` 并增加完整目标索引；
- 扩展 provider-neutral 审计字段；
- 将历史 local 行确定性回填为 local contract v1、AAD v1，provider key version 来自既有逻辑版本。
- 迁移前检查历史数据是否存在同 recipient 多个 `ACTIVE`；存在歧义时失败关闭，要求人工核对后重试；
- 通过生成列唯一键约束单 active，并新增 policy/run/item/audit 四张表；
- run trigger、item source/candidate、claim token/lease 和索引共同提供幂等与多 worker 栅栏。

已发布迁移不会改写。CI 的真实 MySQL 8 测试覆盖空库安装、V1.18 → V1.19 重复 active 失败预检、单 active 约束、双 worker `SKIP LOCKED`、trigger 唯一键和 claim-token 完成栅栏，且要求 4 个测试零跳过。

数据库备份必须与 Vault snapshot/raft storage、unseal/recovery 材料和历史 key 可用性一起设计。只恢复数据库但无法访问对应 provider/key 时，wrapped DEK 无法解封。灾难恢复演练至少验证：

1. 历史 local v1 信封可读取；
2. 当前与旧 Vault key version 均可 decrypt；
3. Vault token 轮换不影响历史信封；
4. 恢复后 audit、tenant 和 recipient 隔离仍有效。

## 7. 已知限制与后续边界

- Vault 可用性会直接影响加密文件下载；当前只提供稳定可重试语义，不在请求内做无界重试，也不回退 local。
- 多套件运行时策略和 provider 协商属于 P3-3。
- 授权下载/解密 metadata 当前仍可能向前端返回 `initialKey`；更广泛的数据密钥暴露收敛属于 P3-4。
- AWS KMS adapter 需在仓库具备受控 AWS 账户、OIDC role 和测试 key 后单独验收，不能用 mock 冒充真实云 KMS 证据。

## 8. 运维检查清单

- [ ] 生产 active provider 与合同版本显式配置。
- [ ] Vault 地址为 HTTPS，token 为最小权限且由 secret 系统注入。
- [ ] Vault key 为 derived `aes256-gcm96`，目标版本与部署变更单一致。
- [ ] 选择 local 时，master key 独立于 JWT 且至少 32 字符。
- [ ] 历史 provider/key 读取能力在信封轮换完成前持续保留。
- [ ] 先执行 dry-run，确认候选数量、recipient 分布和目标 provider version，再启动 APPLY。
- [ ] `remaining` 与 `failed` 归零且回滚宽限期结束前，不禁用或删除旧 provider key。
- [ ] 退休只由外部变更流程执行；RecordPlatform 仅记录 READY 和管理员 acknowledgement。
- [ ] 审计、指标、health 和日志采集链路中不存在 token、plaintext DEK、wrapped blob 或原始 key ID。
- [ ] 数据库与 Vault 备份、恢复和 HA 故障切换已联合演练。
- [ ] 若声明 HSM-backed，已验证 Enterprise 许可证、PKCS#11/Managed Keys、实际硬件与故障切换证据。

## 9. 参考资料

- [Vault Transit secrets engine](https://developer.hashicorp.com/vault/docs/secrets/transit)
- [Vault Transit HTTP API](https://developer.hashicorp.com/vault/api-docs/secret/transit)
- [Vault seal wrap](https://developer.hashicorp.com/vault/docs/enterprise/sealwrap)
- [Vault Managed Keys](https://developer.hashicorp.com/vault/docs/enterprise/managed-keys)
- [NIST SP 800-57 Part 1 Revision 5](https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final)
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)

---

**文档版本**：v3.0
**最后更新**：2026-07-27
**维护者**：Security Team
