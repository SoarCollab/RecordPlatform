# RecordPlatform 演进路线图

> 最后更新：2026-07-27
>
> 定位：个人维护的开源项目，以自动化门禁和可复核证据替代人工判断

## 1. 使用规则

`ROADMAP.md` 是仓库唯一 canonical 路线图。阶段状态必须同时满足以下规则：

- **事实优先**：实现、测试、CI run、线上页面的实际状态高于计划文字。
- **证据分层**：工作区通过、PR 通过、exact `main` 通过、线上可访问是四种不同结论。
- **不扩大结论**：模块级零漏洞、smoke 通过或单个环境结果不能扩写为仓库级零风险或生产 SLA。
- **自动化优先**：可由 CI/CD、生成器和一致性检查完成的工作，不依赖人工记忆。
- **环境同构**：本地与生产使用同一基础设施类型；FISCO BCOS、MySQL、Redis、RabbitMQ、Nacos 和 S3 兼容存储均属于真实运行依赖。

各阶段的可点击 CI/Pages 证据、适用边界和复验命令见：

- [中文交付证据矩阵](docs/zh/architecture/delivery-evidence.md)
- [English Delivery Evidence](docs/en/architecture/delivery-evidence.md)
- [测试框架与 exact-main 口径](TESTING.md)

## 2. 当前仓库基线

### 2.1 规模与版本

| 指标 | 当前值 | 自动校验来源 |
| --- | ---: | --- |
| REST 控制器 | 33 | `platform-backend/backend-web/src/main/java` |
| 后端服务类 | 212 | `platform-backend/backend-service/src/main/java` |
| 后端测试文件 | 212 | `platform-backend/**/src/test/java` |
| 数据库迁移 | 38（V1.0.0 ~ V1.20.0） | `platform-backend/backend-web/src/main/resources/db/migration` |
| 核心工作流 | 5 | `test.yml`、`perf-smoke.yml`、`docs.yml`、`security-poc.yml`、`docs-consistency.yml` |

| 组件 | 当前基线 | 演进原则 |
| --- | --- | --- |
| Java | 21 LTS | 新 LTS 先验证构建、容器和依赖兼容性 |
| Spring Boot | 3.5.16 | 4.x 迁移单独立项，不在依赖更新中顺带升级 |
| Dubbo | 3.3.6 Triple | 保持 consumer/provider 契约兼容 |
| Svelte | 5.55+ | 使用稳定 Runes API |
| SvelteKit | 2.59+ | 与前端 lockfile 同步验证 |
| Vite | 7.3+ | 应用与文档站分别以各自 `package.json`/lockfile 为准 |
| Tailwind CSS | 4.3+ | 通过 `@tailwindcss/vite` 集成 |
| MySQL | 8.0 | schema 只通过顺序 Flyway migration 演进 |
| Redis | 7 | 锁、lease、receipt、tombstone 必须有过期与 fencing 语义 |
| FISCO BCOS | 3.8.0 | 合约 ABI、bytecode、地址和 registry 指纹共同验证 |

### 2.2 PR 阻断门禁

| 门禁 | 当前边界 | 证据 |
| --- | --- | --- |
| 后端单元/集成测试 | Backend Tests required | `.github/workflows/test.yml` |
| 后端覆盖率 | web ≥40%、service ≥45%、common ≥40% | `platform-backend/pom.xml` |
| 前端质量 | format、lint、type check、coverage | `.github/workflows/test.yml` |
| 前端依赖审计 | `pnpm audit --audit-level high` 阻断 | `.github/workflows/test.yml` |
| 契约一致性 | OpenAPI ↔ `generated.ts` required | contract-consistency job |
| 存储真实故障门禁 | MinIO/Redis/Toxiproxy `clean verify -Pit`，精确校验 Failsafe XML 与零残留 | storage-test job |
| 受限堆直传 | 96 MiB 对象 / 80 MiB heap 独立 fork | `direct-upload-constrained-heap` profile |
| Trivy 子集 | 已修复 High/Critical 使用 `exit-code: 1` | security-scan job |
| 构建验证 | Backend/FISCO/Storage/Frontend 镜像与包 | build-check job |
| 新代码覆盖率 | Codecov patch ≥80% | `.codecov.yml` |

### 2.3 观察级与手工门禁

| 项目 | 当前边界 | 后续责任 |
| --- | --- | --- |
| `security-poc` | Semgrep、全量 Trivy、SBOM 聚合仍为 observation，不是 required | P6 统一阻断、例外和 advisory 策略 |
| Slither | 合约变更触发并上传 SARIF，仍为信息级 | 在误报和基线稳定后评估阻断 |
| k6 外部环境 | 手工触发；默认 `direct-path/smoke`，不属于 PR required | 用相同 fingerprint 建立生产前基线 |
| Pages 发布 | exact `main` 的 Deploy Docs 成功后再检查线上 HTTP 200 | 每个文档 leaf 的发布验收 |

## 3. 阶段总览

| 阶段 | 目标 | 状态 | 完成证据 |
| --- | --- | --- | --- |
| P0 | 建立不可绕过的质量与依赖自动化基线 | 已完成 | required checks、Dependabot、覆盖率和 Trivy 子集 |
| P1 | 建立可追溯证明、发布、故障恢复与观测能力 | 已完成 | signed proof、public verifier、环境/合约工具、OTel/SLO、完整性治理 |
| P2 | 收口大文件直传/下载、历史 manifest、真实故障与在线文档 | 功能已完成；在线状态由发布门禁判定 | P2-1–P2-Q3 已通过 exact-main；P2-5 以合并提交的 Docs/Pages 证据为最终判据 |
| P3 | 企业密钥治理：外部 KMS、自动轮换、运行时密码敏捷与密钥暴露收敛 | 已完成 | P3-1–P3-4 均通过独立 PR、exact-main 全量门禁与 Pages 验收 |
| P4 | 内容寻址、隐私证明、跨链、DID/VC 与后量子探索 | 条件触发 | 仅方向性评估，不承诺日期 |
| P6 | 统一安全策略与剩余 advisory 治理 | 预留 | 不在 P2 以局部零漏洞替代 |

## 4. P0：自动化质量基线（已完成）

### P0-1 Required checks 与分支保护

- `main` 要求 Backend Tests、Frontend Tests、Contract Consistency、Build Verification。
- `strict=true`，要求分支与 base 同步；管理员同样受保护规则约束。

### P0-2 Dependabot 与依赖自动更新

- Maven、frontend/docs npm、GitHub Actions 均由 `.github/dependabot.yml` 维护。
- Dependabot PR 仍必须经过相同构建、测试和契约门禁。

### P0-3 安全阻断子集

- Test Suite 中的 frontend High 审计与已修复 High/Critical Trivy 扫描会阻断 job。
- SARIF 持续上传，便于 issue 生命周期跟踪。
- 完整 `security-poc` 仍为观察级；该边界不能表述为“所有安全扫描均阻断”。

### P0-4 覆盖率与生成合同

- JaCoCo 阈值、Codecov patch 阈值持续防止覆盖率回退。
- OpenAPI 由 backend 导出，frontend 类型由该 artifact 生成；未提交的 `generated.ts` 漂移会阻断。

## 5. P1：可信交付与运行可靠性（已完成）

P1 已交付以下长期能力，详细实现不在本路线图重复展开：

- 故障注入、Saga/Outbox 恢复与数据库集成测试；
- tag 驱动 Release Notes、SBOM、四模块容器镜像发布；
- `env-check.sh`、基础设施 compose 与双语环境搭建文档；
- 六阶段合约 compile/deploy/ABI sync/write-back/verify 工具；
- OTel Java Agent、W3C Trace Context、Prometheus 规则和 Grafana SLO dashboard；
- active chunk manifest、三层存储完整性检查与管理员告警；
- `MANIFEST_HASH` Merkle 批次、确定性 Ed25519 signed ZIP v2；
- `platform-verifier` SDK、CLI、Web 三种公开验证面。

Signed ZIP 的 `VALID` 要求：本地合同全部通过、显式信任的 Ed25519 key、在线状态 `ACTIVE`、实时链 identity/root 一致。离线或 resolver 不可用必须为 `INDETERMINATE`，不能降级成 `VALID`。后端 `ProofBundleVerifierImpl` 仅兼容未签名 `proof-bundle.v1.1`。

## 6. P2：大文件可靠性与在线合同收口

### P2-1 对象存储原生 Direct Multipart（已完成）

主路径为 direct create → 预签名 staging PUT → direct complete。Backend 只处理会话、配额、manifest 与存证元数据；`platform-storage` 负责可信对象身份重建和最终副本晋升。

固定边界：

- 单文件 4 GiB、单分片 100 MiB；
- 跨端点固定缓冲默认 64 KiB，有效范围 8 KiB–1 MiB；
- 晋升超时默认 300 秒、最大 1,800 秒；锁等待默认 5 秒、最大 60 秒；
- staging 最少保留 48 小时；cleanup 默认启用，1 小时间隔、5 分钟初始延迟、批量 200、claim lease 600 秒；
- 同端点使用条件 copy，跨端点使用有界 stream；降级副本进入 repair；
- receipt、operation intent、tombstone 和 fencing 保证 complete/abort/cleanup 在重启与竞争下保持幂等。

后端代理 chunk upload 仍为兼容路径，不是新大文件默认路径。

### P2-Q1 Frontend High 依赖清零（已完成）

- 前端生产与开发依赖按同一 lockfile 进行 High 级审计。
- Test Suite 的 `pnpm audit --audit-level high` 已成为显式阻断步骤。
- “frontend 工作区 High=0”只适用于当次 lockfile 与审计口径，不代表全仓 advisory 为零。

### P2-Q2 Jackson 运行时依赖收口（已完成）

- 将运行时 JSON 合同需要的 Jackson 依赖显式固定在实际消费模块。
- 通过构建、测试和 OpenAPI/序列化合同验证依赖图，不依靠本地 Maven 缓存偶然成功。

### P2-1A Commons Compress 测试依赖隔离（已完成）

- Testcontainers/测试工具所需 Commons Compress 仅保留在测试 classpath。
- 生产 runtime 与镜像不因故障测试引入额外压缩库攻击面。

### P2-2 浏览器有界流式下载（已完成）

- 64 MiB 是内存 sink 硬上限；更大文件必须使用 File System Access API + Streams。
- 500 MiB 与 2 GiB 仅是提示分级；绝对下载上限 100 GiB。
- 显式支持 `NONE`、legacy v1 AEAD、framed AEAD v2。
- legacy 密文 part 上限约 80 MiB + 4 KiB；单文件最多 10,000 parts；网络块最大 1 MiB。
- 单次 fetch 最多三次尝试；401/403 立即刷新 metadata，只有 5xx 有限重试。
- 顺序、长度、hash、key、decrypt、cancel 或 sink 任一失败都会 abort；不支持流式 sink 的浏览器对 >64 MiB 文件失败闭合，不存在无界 backend proxy 兜底。

### P2-3 历史 Manifest 回填与引用治理（已完成）

治理模式为 `SCAN`、`DRY_RUN`、`APPLY`；分类为 `ALREADY_MANIFEST`、`BACKFILLABLE`、`REUPLOAD_REQUIRED`、`UNRECOVERABLE`、`FAILED`、`IGNORED`。

安全默认值：

- backfill worker enabled，apply disabled，run lease 300 秒，worker delay 5 秒；
- worker page 100、claim 20、每条最多三次、claim lease 120 秒；
- sweep mark/delete 均 disabled，保护窗口 30 天，batch 20（硬上限 100），lease 120 秒（最小 30），worker delay 60 秒。

缺少 active manifest 的下载不会静默回退。`FILE_RECORD_ERROR` 的标准 `ErrorPayload` 在 `data.detail` 中携带 `manifestStatus`、`manifestClassification`、`manifestErrorCode`、`legacyDownloadAllowed`。未分类默认是 `REUPLOAD_REQUIRED / UNCLASSIFIED / MISSING_MANIFEST_UNCLASSIFIED / false`。

### P2-4 真实故障矩阵与负载 Smoke（已完成）

- PR required 使用真实 MinIO、Redis/Redisson 和两条 Toxiproxy 数据面。
- `DirectUploadPromotionMinioIT` 8、`DirectUploadRedisLifecycleIT` 7、`DirectUploadFaultMatrixMinioIT` 9、`DirectUploadLoadSmokeIT` 1，Failsafe 的 skipped/failures/errors 必须为零。
- exact-main Test Suite run `30209115456`：Linux amd64、Java 21.0.11、4 processors，环境 fingerprint `5b9cece769dd3a52cd34a0af45d9342573ad855edb98d048547b732d8cdeab6b`；4 并发 8/8 成功；256 KiB 负载 wall 414 ms、p99 291 ms、`5,061,808.93 bytes/s`（约 4.83 MiB/s）；heap peak delta `37,748,736 bytes`（36 MiB）、direct-buffer peak delta `2,686,976 bytes`、thread delta 23；receipt/tombstone 8/8 → 0/0，最终零残留。
- [retained report](docs/public/evidence/direct-upload-load-smoke-30209115456.json) 的 source SHA-256 是 `4ecc8b77b7a39f4ccf6c2809dc52642d252bf8d123845823736a8f67476aab39`。该 smoke 只证明相同 fingerprint/负载与测试预算，不是生产吞吐或 SLA，也不能与不同 fingerprint 直接比较。

### P2-Q3 Docs 工作区依赖清零（已完成）

- PR #315 合并到 exact commit `0e091261233e7430ce5b904ad84d33569de910a1`。
- docs lockfile hash：`a93c069302dbce3a17d4b9d5a3df011a78cd43123d3e58465cf1fd38d73dba98`。
- exact-main Test Suite `30210972743`、Deploy Docs `30210972766` 通过，Pages HTTP 200。
- GitHub advisories #588/#590 以 fixed 收口，不以 dismiss 伪装修复。
- 该 leaf 仅证明当时 docs workspace 的对应审计口径为零；禁止写成 repo-wide zero advisories。动态 backlog 以 [Dependabot live view](https://github.com/SoarCollab/RecordPlatform/security/dependabot) 为准，下面 P6 只保留带 SHA/时间边界的快照。

### P2-5 在线文档与发布证据（本次收口）

交付范围：

- `ROADMAP.md` 唯一 canonical，旧候选 roadmap 删除并由 checker 防回归；
- 中英文系统、存储、blockchain、Manifest、API、K6 和 evidence matrix 同步；
- `API_DOCUMENTATION.md`、`TESTING.md`、README 与贡献规范对齐；
- 内存指南由“并发 × 文件大小”旧模型更新为固定缓冲 + heap/direct-buffer/RSS 实测模型。

发布完成定义：

1. 工作区 `check_consistency.py`、OpenAPI/类型合同与 VitePress build 通过；
2. 合并后的 exact `main` Test Suite 和 Deploy Docs 均成功；
3. 对应 Pages 中英文关键路由返回 HTTP 200；
4. evidence matrix 记录 exact run/commit，不用 PR 分支结果冒充已上线。

在第 2–3 步完成前，本 leaf 只能表述为“文档实现完成/待发布验收”，不能表述为“已在线验证”。

## 7. P3：企业密钥治理（已完成）

P3 已将本地 AES-GCM envelope 演进为可替换、可接外部 KMS、可自动轮换、可按 provider/suite 治理且减少明文 DEK 暴露的企业密钥生命周期。四个 leaf 严格串行完成；每个 leaf 都从上一个已验收的 exact `main` 创建独立分支和 PR，并在合并、精确主线验收与归档后才启动下一个。

### P3-1 Wrapping Provider 与外部 KMS（已完成）

- provider SPI 明确 wrap/unwrap/rewrap、能力、诊断与稳定失败分类；历史读取按持久化 provider/contract 路由，未知合同失败关闭；
- local v1 保留历史字节合同；生产外部 provider 使用 Vault Transit derived `aes256-gcm96`，真实官方容器覆盖 wrap、unwrap、context tamper、native rewrap 和权限失败；
- 生产 profile 禁止 local master key 回退 JWT secret，外部 token/key 配置失败时启动关闭；
- PR #317 已合并，exact `main@cfe9b9e54eefa01246dbddda7ab5a4c27717a3dc` 的 Test Suite、Deploy Docs、CodeQL、依赖和 Pages 验收通过。

### P3-2 自动化 Envelope Rotation（已完成）

- 每租户 policy 冻结目标 provider/key version、批量、限流、schedule、retry/backoff、claim lease、grace 和退休条件；
- manual/scheduled/dry-run 使用不可变 run、上界快照与 keyset cursor；item 由 token、lease、`FOR UPDATE SKIP LOCKED` 和固定 candidate ID 保证多 worker/崩溃恢复幂等；
- 候选先 `PENDING_VERIFICATION`，成功解封并与源 DEK 常量时间相等后才在短事务内切换 active；生成列唯一键阻止双 active，share 撤销不会被轮换重新授权；
- 管理 API 支持 start/pause/resume/cancel/retry、进度分页、审计、低基数指标、终态告警和仅确认外部退休；应用不调用 provider disable/delete；
- PR #318 已合并；exact `main@a4ba5acf3864fd341219a7382d13b2cd30d3afde` 的 Test Suite、Docs、CodeQL、依赖和 Pages 验收通过，真实 MySQL 4-test 零跳过门禁通过，Trellis 已归档。

### P3-3 运行时 Crypto Agility（已完成）

- 闭集 suite registry 冻结 stable ID、type、provider/contract、生命周期、constraints、兼容和 re-encryption 边界；配置只能收紧，不能把未实现能力提升为 active；
- tenant policy 以 optimistic version 管理新写的 wrapping/signature/KEM/proof 选择；历史 envelope/proof 只按持久化身份分派，默认漂移不触发 fallback 或 downgrade；
- local/Vault wrapping 与 local Ed25519 proof provider 提供真实运行时 dispatch；ML-DSA/ML-KEM 仅为 experimental/unimplemented 目录项，生产写入永久拒绝；
- 管理 API、指纹审计、低基数指标、V1.20 backfill/constraint/concurrency 迁移门禁和运维文档已验收；PR #319 已合并，exact `main@c0bd8076994ce0cb3bf98a3ff0f722c60ea84a4c` 全部门禁和 Pages 通过，Trellis 已归档。

### P3-4 密钥暴露收敛（已完成）

- owner、friend-share、认证分享和公开分享的 metadata/decrypt-info 默认只返回 60 秒、会话/actor/tenant/file/version/suite/精确信封绑定的 `grant-v1` 引用，不返回 plaintext `initialKey`；
- Redis 仅保存引用摘要和非秘密绑定，Lua 原子消费只允许首次与一次短窗同会话重试；rotation、分享撤销、信封 supersede/revoke、文件版本变化和跨 actor/session/tenant/client 均失败关闭；
- grant 只经 POST body 消费，相关响应 `no-store`，常规/操作日志跳过请求与响应体；独立用户/可信客户端 IP 限流、稳定审计和低基数指标不记录引用、会话、IP 或密钥；
- 前端不把 grant/DEK 写入下载任务或浏览器持久存储，解密前即时消费；framed v2 把 DEK 导入 non-extractable HKDF `CryptoKey` 并清理可变字节，legacy AES 同样导入 non-extractable key；
- `plaintext-v0` 仅允许客户端显式协商、服务端显式开关且未超过硬截止时间，生产默认关闭；真实 Redis 并发、前端生命周期、OpenAPI 和全量门禁均已验收；
- PR #320 已合并；exact `main@7f9d639f3395269735e9efeb3dbea4e9e025d412` 的 Test Suite `30267453969`、Deploy Documentation `30267454033`、CodeQL、依赖提交与 Pages deployment `5622671802` 均成功，关键线上路由返回 HTTP 200，Trellis 已归档。

P3 validation-only 阶段回归已完成：P3-1～P3-4 分别以 PR #317～#320 串行合入 `cfe9b9e5`、`a4ba5acf`、`c0bd8076`、`7f9d639f`；最终 exact-main backend/frontend/config/docs/security 门禁、OpenAPI 幂等生成、依赖审计、真实浏览器下载 E2E 4/4、Pages 关键路由和最新主线 review 全部通过，未发现已知高置信问题。

## 8. P4：远期探索（条件触发）

P4 不承诺实施时间；只有业务、合规或容量触发条件成立后，才创建独立 PRD、威胁模型和回滚计划。

### P4-1 内容寻址与冷热分层

- 保持 S3 API 为热数据统一接口，评估 IPFS/Filecoin 作为归档层。
- 触发条件：存储成本或可验证存储成为明确指标。
- 必须先解决租户隔离、删除语义、pin/repair、CID 与现有 `contentHash`/`manifestHash` 的非互换合同。

### P4-2 零知识隐私存证

- 目标是证明文件/属性存在而不公开内容。
- 触发条件：出现明确隐私合规或客户合同要求。
- 需要单独评估 trusted setup、证明大小、链上 verifier 成本和密钥生命周期。

### P4-3 跨链互操作

- 评估双链锚定、链间证明或受信 gateway，不预设桥接方案。
- 触发条件：出现多机构互认或司法链对接需求。
- 任何跨链结果都必须区分“本链事实”“远端链事实”“gateway 声明”。

### P4-4 DID 与 Verifiable Credentials

- 评估 W3C VC 2.0 与 FISCO 生态 DID 实现，将 signed proof 作为可组合证据输入。
- 触发条件：验证方需要脱离平台账户体系进行标准化验证。
- 公共 verifier 已交付，但不能因此声称 DID/VC 签发、撤销和选择性披露已经实现。

### P4-5 后量子迁移准备

- AES-256 与 SHA-256 当前继续使用；跟踪 ML-KEM、ML-DSA、SLH-DSA 及国内标准进展。
- 触发条件：监管时间表、互操作方要求或 Java provider 达到生产可用。
- PQC 调研不能顺带改变当前 Ed25519 signed-proof 合同；迁移必须版本化并支持双轨验证窗口。

## 9. P6：统一安全治理（预留）

P6 接管 P2 不应扩大的剩余安全工作：

- 快照 `main@85a57ae847423308cf60683c6fd299d51a1650f1`（2026-08-10 14:51 CST）有 3 open、均为 Maven / Medium 的 `org.apache.commons:commons-lang3`：`platform-verifier/sdk`、`platform-verifier/cli-verifier`、`platform-verifier/web-verifier`。当前 Spring Boot BOM 将依赖解析为 3.17.0，首个 patched version 为 3.18.0；owner 为 P6 supply-chain maintenance，必须在 2026-09-30 前或下一次 release 前（取更早者）完成独立 Maven 兼容升级/复验，不在文档 PR 中混入依赖变更。实时状态以 [Dependabot live view](https://github.com/SoarCollab/RecordPlatform/security/dependabot) 为准；
- Semgrep、全量 Trivy、SBOM、Slither 与 GitHub advisory 的统一 severity/exception/expiry 模型；
- required-check 策略和 release 阻断的分阶段升级；
- 每个例外的 owner、理由、到期时间和替代控制。

在 P6 完成前，可以准确声称指定 workspace 或指定阻断子集通过，不能声称“仓库无漏洞”或“所有安全扫描均已阻断”。

## 10. 依赖与存储演进原则

| 领域 | 原则 |
| --- | --- |
| 依赖 | Dependabot 提 PR；锁文件、审计、测试、生成合同共同验证 |
| Major 升级 | 单独任务、兼容性矩阵、回滚方案，不与业务 leaf 混合 |
| 数据库 | 只新增 Flyway migration；不得修改已发布 migration |
| 对象存储 | staging 与 final namespace 分离；删除前必须有引用 census 和保护窗口 |
| 哈希语义 | `contentHash`、`chainRecordId`、`manifestHash`、`cipherHash`、`merkleRoot` 不互换 |
| 证明 | signed ZIP v2 是 canonical；`proof-bundle.v1.1` 仅兼容读取 |
| 性能 | 只比较相同 environment fingerprint；缺失资源快照标记 unavailable，不填零 |
| 文档 | 代码、OpenAPI、双语页面、Roadmap 与在线 Pages 证据同步交付 |
