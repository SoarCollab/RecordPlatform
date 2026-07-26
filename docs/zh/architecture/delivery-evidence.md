# P0/P1/P2 交付证据矩阵

本页的 P0–P2 实现证据冻结自 2026-07-27 的最后一个文档刷新前 exact-main 基线 `main@0e091261233e7430ce5b904ad84d33569de910a1`。P2-5 只有在后续合并提交通过 exact-main 门禁并完成 Pages 部署后才独立验收。这样既不会把静态检查通过写成生产性能结论，也不会把尚未发布的页面描述为已经在线。

## 证据规则

- **实现证据**来自生产代码、配置、Flyway 迁移、OpenAPI 合同或生成类型。
- **自动化证据**来自可重复的单元、集成、浏览器、真实 provider 或 CI 门禁。
- **运行证据**必须绑定精确提交、workflow run、环境指纹和保留制品；不能把前一个叶子任务的结果直接套用到后续提交。
- `ROADMAP.md` 是唯一规范路线图。历史 Trellis task 与 PR 是执行证据，不是第二份路线图。

## P0 稳定性底座

| 已交付能力 | 实现证据 | 自动化证据 | 边界 |
| --- | --- | --- | --- |
| 租户感知缓存隔离 | `TenantKeyUtils` 与文件查询/写入缓存注解把条目和驱逐绑定到 `tenantId:userId` | cache proxy 与跨租户服务测试 | 授权事实始终来自认证后的租户上下文，不接受调用者提供缓存 key |
| 管理端外部 ID 安全 | 文件管理控制器在持久化调用前使用 `IdUtils` 解码外部 ID | 非法、空值和授权控制器测试 | 混淆外部 ID 不能作为数据库主键直接使用 |
| 可恢复上传清理 | 上传状态持久化 tenant、user、SUID、路径、重试和最终化 claim | cleanup/restart/finalization 集成测试 | 清理失败保留有界人工恢复入口，不伪报成功 |
| Manifest 驱动完整性校验 | `IntegrityCheckService`、active chunk manifest、存储 HEAD 与 `V1.17.0__manifest_backfill_governance.sql` | 三级完整性与告警控制器测试 | `file.fileHash` 是链记录 ID，不能替代 `contentHash`、`plainHash` 或 `cipherHash` |

## P1 可信证明产品化

| 已交付能力 | 实现证据 | 自动化证据 | 边界 |
| --- | --- | --- | --- |
| 可恢复批次上链 | candidate/batch 持久状态机、写前/写后查询恢复、有界生产准入 | 链故障、重试耗尽、并发和 exact-main CI | 未知链上存在状态不能降级为 `false`；写入耗尽后只允许 query-only 恢复或人工终态 |
| 合约身份治理 | 不可变 registry 绑定 chain、address、语义版本、ABI 指纹、runtime bytecode 与部署回执 | FISCO/Besu registry、runtime code、receipt、nonce 与回滚测试 | 合约自报名称/版本不能单独证明身份 |
| 签名证明 ZIP v2 | 确定性八条目 STORED ZIP、canonical JSON、compact JWS `EdDSA`、不可变签发快照、公开 key/status 端点 | ZIP 边界、签名、生命周期、限流、审计隔离和并发测试 | `proof-bundle.v1.1` JSON 已弃用且未签名；其后端 verifier 仅用于兼容读取 |
| 独立验证器 | `platform-verifier` SDK、CLI、独立 Web 服务共享 `DefaultProofVerifier` | 合法、篡改、撤销、离线、SSRF、ZIP 边界 fixture | 只有本地检查全过、Ed25519 key 可信、在线状态为 `ACTIVE` 且实时链根匹配才是 `VALID`；resolver 不可用返回 `INDETERMINATE` |
| 公开边界加固 | 可信客户端 IP、无租户公共限流桶、权威 SSE 短 token、按 shareCode 恢复 owner tenant | tenant/header/proxy/rate-limit 负向和审计隔离测试 | 调用者租户头或转发头不能成为 proof/share/SSE 权威事实源 |

## P2 大文件与高并发闭环

| 叶子任务 | 已交付合同 | 主要证据 | 验收证据 |
| --- | --- | --- | --- |
| P2-1 原生提升 | direct create -> staging 预签名 PUT -> complete；同 endpoint 条件式服务端 copy，跨 endpoint 有界流；receipt 先于 repair/cleanup | `StorageProperties.DirectUploadConfig`、promotion/receipt/intent/tombstone 服务、后端最终化 claim | 真实 MinIO/Redis、重启/竞态测试与 96 MiB 受限堆门禁 |
| P2-Q1 前端依赖修复 | 完整前端开发依赖图阻断新 High/Critical，且不削弱测试 | frontend workspace/lockfile 与 CI audit | 指定 Dependabot 告警真实 fixed，未 dismissal |
| P2-Q2 Jackson 运行时修复 | API/backend/FISCO/storage/verifier 的 Jackson 2.x/3.x 安全补丁保持一致 | 五个 Maven 依赖树与 JSON/日志兼容测试 | 运行时告警 fixed，刷新后的依赖证据不含旧版本 |
| P2-1A 测试依赖修复 | storage test scope 固定安全 Commons Compress/Lang3 并设置最低版本门禁 | storage POM/enforcer 与 test/runtime 依赖树 | 真实容器测试通过；test-only 依赖未进入生产 runtime |
| P2-2 有界下载 | 明确区分 `NONE`、legacy v1、framed AEAD v2；支持的浏览器通过事务型 File System Access sink 写盘 | `boundedDownloader.ts`、`downloadSink.ts`、`fileSize.ts`、framed v2 迁移/fixture | Chromium 64/256/512 MiB 门禁证明应用缓冲不随文件增长，篡改/截断/取消/sink 失败均在提交前 abort |
| P2-3 Manifest 治理 | 租户级 `SCAN`/`DRY_RUN`/`APPLY`、insert-only 发布、确定性分类、引用 census 与独立开关 sweep | 管理控制器/服务与 `V1.17.0__manifest_backfill_governance.sql` | 事务/并发/崩溃恢复/安全测试；不根据 map 顺序、ETag、文件名或链记录 ID 猜测 |
| P2-4 负载与故障证据 | direct-path k6 与强制 MinIO/Redis/Toxiproxy 矩阵覆盖上传、下载、quorum、降级修复、超时、竞态和清理 | `tools/k6/direct-path.js`、`perf-smoke.yml`、storage fault/load IT | exact-main Test Suite `30209115456`；Linux amd64/Java 21.0.11 受限 smoke 完成 8/8 文件且生命周期残留为零 |
| P2-Q3 docs 依赖公告修复 | docs workspace 固定 DOMPurify 3.4.12 与 PostCSS 8.5.18 | `docs/pnpm-workspace.yaml` 与可复现 lockfile | PR #315、exact-main docs deploy、docs low audit 零公告；#588/#590 fixed 且未 dismissal |
| P2-5 在线文档 | 本双语证据矩阵与同步后的架构/API/运维页面 | VitePress 路由与导航、规范路线图、OpenAPI、一致性脚本 | 只有 docs build、一致性检查、exact-main 部署与在线 Pages 验收都成功后才算完成 |

## 当前传输限制与默认值

| 合同 | 当前值 |
| --- | --- |
| 直传文件 / part 上限 | 4 GiB / 100 MiB |
| 提升流缓冲 | 默认 64 KiB；有效范围 8 KiB～1 MiB |
| 提升传输 deadline | 默认 300 秒；最大 1,800 秒 |
| staging 保留 | 至少 48 小时 |
| 浏览器内存 fallback | 硬上限 64 MiB |
| 下载分级 | 500 MiB 建议流式；2 GiB 为超大文件；绝对上限 100 GiB |
| 有界 reader | 最多 10,000 parts、网络块 1 MiB、最多 3 次 fetch；401/403 立即刷新 metadata，只有 5xx 有限重试 |
| legacy v1 密文 part | 约 80 MiB 加 4 KiB 兼容开销 |

以上是配置和产品限制，不是吞吐承诺。

## 依赖公告边界

P2-Q3 后，docs workspace 自身的 `pnpm audit --audit-level low` 为零公告；但默认分支仍有 **26 个 open Dependabot alerts：8 Medium + 18 Low**，明确保留给 P6 供应链治理。本页不声称全仓零公告，也不把只检查 High/Critical 的扫描成功解释为 Medium/Low 不存在。

## 发布验收

后续每次变更都必须在候选提交上重新执行适用测试，正常合并后获取 merge SHA，再等待真正适用于该精确 `origin/main` 的工作流。只有同一 SHA 的 Pages 部署成功且关键中英文路由能读取到新内容，在线文档才算发布完成。
