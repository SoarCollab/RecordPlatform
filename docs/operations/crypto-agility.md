# 运行时密码敏捷运维手册

## 1. 适用范围

本手册用于变更 RecordPlatform 的租户级 suite/provider 策略、安排弃用窗口并验证历史读取。它不执行文件内容重新加密，也不退休 Vault/local key；信封重包封继续使用[自动密钥轮换运维手册](key-rotation.md)。

P3-3 的核心边界是：新写使用当前 tenant policy，历史读使用记录中冻结的 provider、contract 和 suite。任何未知身份、算法类型混用、provider 能力不一致或自动 downgrade 都失败关闭。

## 2. 变更前门禁

1. 确认上一版本数据库已备份，Vault 历史 key/version 和 local 历史 key ID 仍可读。
2. 调用 `GET /api/v1/admin/crypto-agility/diagnostics`，保存当前 `policyFingerprint`、`policyVersion`、suite 生命周期与 provider 能力。
3. 确认目标条目为 `ACTIVE` 且 `productionWriteAllowed=true`，目标 provider/contract 的 `available=true` 并声明所需操作与算法。
4. 若只改变 wrapping provider/suite，先按 P3-2 创建 `DRY_RUN`；若改变 content encryption suite，必须停止并创建独立重新加密计划。
5. 不得把 `ML-DSA-65-DRAFT`、`ML-KEM-768-DRAFT` 或 `unimplemented-pqc` 写入生产策略。

## 3. 读取和更新策略

读取当前策略：

```http
GET /api/v1/admin/crypto-agility/policy
```

首次租户没有持久化覆盖时返回 `policyVersion: 0`。更新必须回传刚读取的版本：

```http
PUT /api/v1/admin/crypto-agility/policy
Content-Type: application/json

{
  "expectedVersion": 0,
  "contentEncryptionSuite": "RP-AES256-GCM-CHUNK-CHAIN-V1",
  "envelopeSignatureSuite": "UNSIGNED-V1",
  "kemSuite": "NONE-V1",
  "proofSuite": "RP-MERKLE-SHA256-V1",
  "wrappingProvider": "vault-transit",
  "wrappingProviderContract": 1,
  "signedProofSignatureSuite": "JWS-EDDSA-ED25519-V1",
  "signedProofSuite": "RP-SIGNED-PROOF-ZIP-V2",
  "signingProvider": "local-ed25519",
  "signingProviderContract": 1
}
```

`POLICY_VERSION_CONFLICT` 表示另一个管理员已经更新；重新读取并审阅差异，不能盲目覆盖。响应与 `tenant_crypto_policy_audit` 只包含稳定 ID、版本和指纹，不包含 provider key ID、Vault token、wrapped blob 或私钥。

## 4. 弃用和禁用顺序

生命周期配置是部署级收紧，不是 tenant API 字段：

1. 配置未来 `deprecated-at` 并发布；到期后新写拒绝该 suite，历史读继续。
2. 把所有 tenant 新写策略迁移到目标 suite/provider。
3. 对 wrapping 变化执行 P3-2 `DRY_RUN` → `APPLY`，确认 `remaining=0`、`failed=0` 并等待 grace period。
4. 查询数据库/审计确认没有历史 envelope/proof 引用；保留恢复证据。
5. 再配置 `disabled-at`。到期后历史读取也会失败，这是不可用性边界，必须通过变更审批。

`disabled-at` 不能早于 `deprecated-at` 或 suite 的 `introducedAt`。未知 lifecycle key、能力提升或无效时间会阻止应用启动。

## 5. 诊断与告警

`GET /api/v1/admin/crypto-agility/diagnostics` 只返回安全摘要：

- suite：ID、type、provider/contract、status、窗口、constraints、compatibility；
- wrapping provider：操作能力、wrapping algorithms、availability、configuration state；
- proof provider：provider/contract、signature/proof suites；
- 当前 policy 与 SHA-256 fingerprint。

重点指标：

- `app.crypto.suite.decision`：按 `type/operation/outcome/reason` 观察 unknown、deprecated、disabled、downgrade 和 re-encryption 拒绝；
- `app.crypto.proof_signing.decision`：观察 proof provider 的 current/sign/verify 分派；
- `app.file.key_wrapping.duration`：观察 wrapping provider 操作与稳定 failure category。

不得把 tenant ID、file ID、key ID 或请求提供的任意 suite 文本加入指标标签。对 `PROVIDER_MISMATCH`、`DISABLED_FOR_READ`、`DOWNGRADE_BLOCKED` 和 `REENCRYPT_REQUIRED` 设置告警时，应同时附带部署版本和 policy fingerprint，不附带密钥材料。

## 6. 回滚

- 策略误配但尚未产生新写：使用最新 `policyVersion` 写回上一份已验证策略。
- 已产生新 wrapping suite 信封：保留新旧 provider，按 P3-2 创建反向 `DRY_RUN/APPLY`；不能直接删除 tenant policy 或关闭新 provider。
- content suite 已变化：不能通过修改标签或 envelope rewrap 回滚，必须使用能够读取两种格式的内容重新加密/恢复流程。
- lifecycle 误设为 `DISABLED`：发布收紧配置的回退版本；恢复后验证历史 envelope/proof 的 persisted routing，不修改历史记录 ID。
- 数据库迁移 V1.20 为 forward-only；应用回滚前必须确认旧二进制能容忍新增列和 `NOT NULL` 约束。不得修改已发布 migration checksum。

## 7. 验收清单

- [ ] 管理员 RBAC 和 tenant 隔离验证通过。
- [ ] policy version、旧/新 fingerprint 和审计 outcome 可追溯。
- [ ] local 与 Vault 两类 wrapping 路由均有真实/回归证据。
- [ ] signed proof 历史验证只使用持久化 provider/suite，默认漂移不破坏旧 proof。
- [ ] unknown、deprecated-for-write、disabled-for-read、downgrade、reencrypt-required 均稳定失败。
- [ ] PQC/KEM 草案条目无法被生产选择。
- [ ] 指标、诊断、日志和 API 不包含 key ID、token、plaintext DEK、wrapped blob 或私钥。
- [ ] V1.20 的 4 个 MySQL 真实场景零跳过，required check 通过。
