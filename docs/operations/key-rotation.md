# 自动密钥轮换运维手册

## 1. 适用范围与安全边界

本手册用于管理员把租户内所有 `OWNER`、`SHARE`、`FRIEND_SHARE` 文件数据密钥信封迁移到当前 active wrapping provider/key version。轮换只重包封 DEK，不重新加密文件内容。

RecordPlatform 只计算旧 key 的退休就绪状态并记录外部操作确认；它不会调用 KMS/Vault 的 disable、delete 或 destroy API。任何旧 key 退役都必须由独立变更流程执行，并保留数据库、KMS 备份和回滚证据。

## 2. 启用调度器

默认调度器关闭。部署环境或 Nacos 中配置：

```yaml
key:
  rotation:
    enabled: true
    poll-interval-ms: 30000
    initial-delay-ms: 30000
    max-tenants-per-poll: 100
```

边界如下：

| 配置 | 范围 | 说明 |
|---|---:|---|
| `poll-interval-ms` | 1,000–3,600,000 | 集群调度轮询间隔 |
| `initial-delay-ms` | 0–3,600,000 | 启动后的首次延迟 |
| `max-tenants-per-poll` | 1–10,000 | 单轮处理的租户上限 |

调度器只触发已启用的持久化租户策略。多实例通过分布式调度锁、策略行锁、唯一 trigger key 和 item `SKIP LOCKED` 协同，不需要把实例数固定为 1。

## 3. 标准执行流程

所有接口以 `/api/v1/admin/key-rotation` 为前缀，要求已认证的当前租户管理员。

### 3.1 创建策略

先确认 active provider 已切到目标 key/version，再保存策略：

```json
{
  "expectedProvider": "vault-transit",
  "expectedProviderContract": 1,
  "expectedProviderKeyVersion": "7",
  "targetLogicalKeyVersion": 2,
  "batchSize": 50,
  "maxItemsPerMinute": 600,
  "scheduleEnabled": false,
  "scheduleIntervalSeconds": null,
  "maxAttempts": 5,
  "initialBackoffSeconds": 5,
  "maxBackoffSeconds": 300,
  "leaseSeconds": 120,
  "gracePeriodSeconds": 604800
}
```

`PUT /policy` 会从 provider registry 解析真实目标并校验 expected 字段，客户端不能提交原始 key ID。响应同样不返回 key ID，只返回 provider、合同和 provider-native version。

### 3.2 先执行 dry-run

```http
POST /api/v1/admin/key-rotation/runs
Content-Type: application/json

{"mode":"DRY_RUN","requestId":"change-20260727-preview"}
```

轮询 `GET /runs/{runId}`，直到状态为 `COMPLETED`。检查 `totalCount`、`skippedCount`、`remainingCount`，并通过 `GET /runs/{runId}/items?limit=100` 分页核对 recipient type。dry-run 不调用 KMS、不创建信封、不改变上一轮 APPLY 的退休状态。

### 3.3 执行 APPLY

```http
POST /api/v1/admin/key-rotation/runs
Content-Type: application/json

{"mode":"APPLY","requestId":"change-20260727-apply"}
```

`requestId` 是幂等键。相同请求可安全重放；用同一键改变 mode 或策略版本会被拒绝。运行期间可以：

- `POST /runs/{runId}/pause`：在下一个发现/领取边界暂停；
- `POST /runs/{runId}/resume`：保留 cursor、候选和尝试次数恢复；
- `POST /runs/{runId}/cancel`：停止后续发现/领取；
- `POST /runs/{runId}/retry`：对终态失败 run 重新排队仍标记为 retryable 的失败项；非重试分类修复后使用新的 request 创建新 run。

## 4. 状态判断

| Run 状态 | 含义 | 操作 |
|---|---|---|
| `PLANNED` | 已创建，尚未开始发现 | 等待调度器 |
| `RUNNING` | 正在分页发现或处理 item | 观察 backlog/failure |
| `PAUSED` | 管理员暂停 | 排除原因后 resume |
| `CANCELLED` | 不再领取新工作 | 如需继续，创建新 request |
| `COMPLETED` | 零失败、零 remaining | 等待 grace 后退休 READY |
| `COMPLETED_WITH_FAILURES` | 存在终态失败 | 修复 provider/数据问题后 retry |

Item 的稳定 outcome 包括 `SUCCEEDED`、`SKIPPED_ALREADY_TARGET`、`SKIPPED_REVOKED`、`SKIPPED_SOURCE_CHANGED`、`DRY_RUN_CANDIDATE` 和 `DRY_RUN_ALREADY_TARGET`。dry-run 的“已是目标”只基于完整持久化目标身份分类，不声称已调用 provider 重新验证。API 不返回 source/candidate envelope ID、recipient ID、wrapped blob、IV、context 或原始 key ID。

## 5. 指标与告警

| 指标 | 建议告警 |
|---|---|
| `app_key_rotation_remaining` | APPLY 启动后持续不下降超过 30 分钟 |
| `app_key_rotation_failed` | 大于 0 持续 5 分钟 |
| `app_key_rotation_retirement_blocked` | 完成窗口外仍为 1 |
| `app_key_rotation_items_total{outcome,failure_category}` | `throttled`/`timeout`/`unavailable` 突增或出现非重试类别 |

指标标签只有稳定 outcome/failure category，不含 tenant、run、file、recipient、key 或 provider 响应。三个 gauge 是当前进程最后处理的持久化 run 快照，用于发现停滞信号，不是跨租户/跨实例的权威总量；告警触发后必须通过管理 API 和数据库持久化计数复核。日志告警只记录 failure category；排查时使用管理 API、`key_rotation_audit_log` 和 KMS 自身审计按时间关联。

## 6. 故障恢复

- `THROTTLED`：降低 `maxItemsPerMinute`/`batchSize`，确认 KMS quota，再等待指数退避或暂停后恢复。
- `TIMEOUT` / `UNAVAILABLE`：确认网络、DNS、TLS、Vault HA 和 provider health；不要切换到 local fallback。
- `PERMISSION_DENIED` / `KEY_NOT_FOUND` / `CONFIGURATION`：修复 token policy、mount/key 或 active provider 配置；这些错误不会自动重试。
- worker 崩溃：不要人工修改 item；lease 到期后其他实例会重领。若候选已激活但完成记录未提交，固定 candidate ID 会在重放时幂等识别。
- share 撤销竞态：`SKIPPED_REVOKED` 是预期安全结果，不应改为成功激活。
- V1.19 迁移报告 duplicate active：停止升级，按 tenant/file/hash/recipient 核对真实授权，只保留一个正确 `ACTIVE`，其余转为 `SUPERSEDED` 或 `REVOKED` 后重新执行 Flyway。不要删除审计或信封历史。

## 7. 旧 key 退休

仅当最新 APPLY run 同时满足以下条件，策略才会进入 `READY`：

1. discovery 完成；
2. `remainingCount = 0`；
3. `failedCount = 0`；
4. run 为 `COMPLETED`；
5. `gracePeriodSeconds` 已经过期。

在外部 KMS 流程完成禁用/退役并验证回滚方案后，调用 `POST /policy/retirement/acknowledge`。该接口只记录 acknowledgement，不执行 provider 操作。完成后仍应保留 KMS 审计、数据库快照、run/item/audit 记录和恢复演练证据。

## 8. 收尾检查

- [ ] dry-run 候选范围与变更单一致。
- [ ] APPLY 为 `COMPLETED`，remaining/failed 均为 0。
- [ ] OWNER、SHARE、FRIEND_SHARE 均有成功或安全跳过证据。
- [ ] 没有原始 key ID、DEK、wrapped blob、token 或 provider error body 出现在响应、日志或指标。
- [ ] grace 结束且策略显示 `READY` 后才执行外部退役。
- [ ] acknowledgement、KMS 审计、数据库/KMS 快照和回滚演练已归档。
