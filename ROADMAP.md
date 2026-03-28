# RecordPlatform 演进路线图

> 最后更新：2026-03-28
> 定位：个人维护的开源项目，以自动化流程替代人工管理

## 1. 文档目标

本路线图记录项目的自动化基线与演进优先级。每个条目必须回答：**增加了什么自动化** 或 **消除了什么手动步骤**。

原则：

- **自动化优先**：能由 CI/CD 和工具完成的，不靠人工判断。
- **证据驱动**：所有门禁条目必须可在仓库中找到实现证据。
- **最小人工干预**：减少流程开销，专注于代码和自动化建设。
- **环境同构**：本地开发与生产使用相同基础设施栈（含区块链节点和智能合约），不存在仅本地运行的轻量模式。

---

## 1.5 市场与技术背景

> **声明**：本节提供决策参考，非承诺路线图。数据来源于公开市场报告和技术标准文档。

| 领域 | 趋势 | 与本项目的关联 |
|------|------|----------------|
| 区块链存证市场 | 全球市场 CAGR 36.7%，FISCO BCOS 生态扩展至 5000+ 机构、10 万开发者、600+ 标杆案例，最新体验版 v3.16.4；WeBASE 中间件已成熟 | 验证技术选型方向正确；WeBASE 可辅助合约生命周期管理（→ P1-5） |
| 分布式存储 | 趋向混合架构（热数据 S3 + 冷数据 IPFS/Filecoin）；新增具体方案：s3x（MinIO fork，S3 API → IPFS 透明转换）、go-ds-s3（IPFS 官方 S3 后端）、ipfs_kit_py（分层混合：本地→S3→IPFS） | 当前 S3 架构可作为热存储层；冷数据层为远期演进方向（→ P3-1） |
| 隐私增强技术 | ZKP 市场 $1.5B → $7.6B，zk-STARKs 具备后量子安全特性，无需可信设置；BBS+ 签名支持 W3C VC 2.0 选择性披露；Verkle 树（Merkle 变体，更小证明体积） | "证明文件存在但不泄露内容"场景价值高（→ P3-2）；选择性披露与 VC 2.0 结合（→ P3-4） |
| 后量子密码学 | NIST 已发布 FIPS 203/204/205；HQC 已于 2025-03 正式入选第五算法（基于码的 KEM，作为 ML-KEM 备选），迁移时间线 2030-2035 | 当前对称加密量子安全，密钥交换需评估（→ P2-3） |
| 可观测性 | OTel Java Agent 已原生支持 Dubbo 自动注入（consumer/provider span + RPC metrics），W3C Trace Context 自动传播，无需代码改动 | 三个 Dubbo 服务可零代码改动全链路接入（→ P2-2）；Dubbo 自动注入降低实施成本 |
| 智能合约安全 | 业界采用 Slither 静态分析 + Echidna/Foundry 模糊测试作为 CI 标配 | 合约逻辑复杂度增加时应引入（→ P2-4） |
| 国内存证行业生态 | 至信链对接 31 省 300+ 法院、蚁链 TEE+MPC 隐私计算、长安链 10 万+ TPS 高性能共识 [置信度: Medium] | 验证联盟链选型方向；司法互认通道为远期跨链参考（→ P3-3） |
| 开源存证参考项目 | OpenTimestamps（比特币锚定）；OpenAttestation 已于 2025-10 归档，迁移至 TrustVC.io；EAS（9.1M attestations，12+ 链，ZK attestation 研究中）；ProofPack（JSON 可验证数据交换）；Blockcerts v3（W3C VC 兼容 Merkle 批量）；TradeTrust（新加坡政府文档存证，OpenAttestation 后继）；evidenceSample（FISCO 存证工厂）；WeIdentity（DID 全栈） [置信度: High] | P2-5 Merkle 聚合、P2-8 多方签名、P3-4 DID/VC 参考实现 |
| 可验证存储 | Filecoin PDP 周期性存储证明、Storacha IPFS 热层 + UCAN 授权网关 [置信度: Medium] | 存储完整性校验参考（→ P2-7）；冷热混合架构演进（→ P3-1） |
| 数字身份标准 | W3C VC 2.0 已于 2025-05-15 正式发布为 Recommendation（7 项规范），WeIdentity 提供 FISCO 上 DID 全栈实现 [置信度: High] | 存证结果封装为可验证凭证（→ P3-4） |

---

## 2. 自动化基线

### 表 1：PR 合并阻断门禁（已就位）

| 门禁 | 证据路径 |
|------|----------|
| 后端单元 + 集成测试 | `.github/workflows/test.yml` |
| 后端覆盖率 (web ≥ 40%, service ≥ 45%, common ≥ 40%) | `platform-backend/pom.xml` JaCoCo |
| 前端 lint + type check + 测试 | `.github/workflows/test.yml` |
| 前端覆盖率 (utils ≥ 70%, endpoints/stores/services ≥ 90%) | `platform-frontend/vitest.config.ts` |
| 契约一致性 (OpenAPI ↔ generated.ts) | `.github/workflows/test.yml` contract-consistency job |
| 构建验证 | `.github/workflows/test.yml` build-check job |
| 安全扫描 (Trivy HIGH/CRITICAL 阻断) | `.github/workflows/test.yml` security-scan job |
| Dependabot 自动依赖更新 | `.github/dependabot.yml` |
| Codecov patch 覆盖率 ≥ 80% | `.codecov.yml` |

### 表 2：信息级 / 手动（尚未阻断）

| 项目 | 现状 | 证据 |
|------|------|------|
| SAST (Semgrep) | 每周定时，信息级；自动观测汇总链路已就位，待首轮数据校准阈值 | `.github/workflows/security-poc.yml`, `tools/security/scripts/render-security-poc-observation.mjs` |
| SCA (Trivy) 全量扫描 | 每周深度扫描（信息级）；PR 级 HIGH/CRITICAL 已升级为阻断 | `.github/workflows/security-poc.yml`, `.github/workflows/test.yml` |
| SBOM (CycloneDX) | 每周定时，信息级 | `.github/workflows/security-poc.yml` |
| 性能 smoke (k6) | 手动触发；k6 脚本已现代化，渲染管线就绪，待在线实测回填 | `.github/workflows/perf-smoke.yml`, `tools/k6/file-query.js`, `tools/k6/scripts/render-query-baseline.mjs` |
| Slither (Solidity SAST) | PR 触发（`.sol` 变更或 workflow 自身变更）+ 手动，信息级 | `.github/workflows/contract-security.yml` |

### 表 3：尚未实现

| 缺失项 | 影响 |
|--------|------|
| ~~容器镜像构建~~ | ~~已完成（P1-3）：4 个多阶段 Dockerfile（`platform-backend/Dockerfile`、`platform-fisco/Dockerfile`、`platform-storage/Dockerfile`、`platform-frontend/Dockerfile`）~~ |
| ~~自动化发布 / Changelog~~ | ~~已完成（P1-3）：`.github/workflows/release.yml`，tag push 触发，自动生成 Release Notes + SBOM 附件 + 四镜像推送 ghcr.io~~ |
| ~~GitHub 分支保护 required checks~~ | ~~已完成：required checks 已启用（Backend Tests, Frontend Tests, Contract Consistency, Build Verification）~~ |
| 智能合约部署自动化 | 合约编译和部署完全手动；ABI 文件作为静态资源签入，无工具链保障 ABI 与链上合约一致 |

> 门禁策略详见 `plan/gate-policy.md`

---

## 3. 优先级清单

### P0：自动化质量门禁（防止回退）

**P0-1：配置 GitHub 分支保护 required checks** ✅

- **What**：将 `backend-test`、`frontend-test`、`contract-consistency`、`build-check` 设为 `main` 的 required status checks
- **自动化收益**：从平台层阻断未通过 CI 的 PR 合并，消除人工判断
- **完成标准**：`gh api` 确认 required checks 已启用；故意破坏测试的 PR 无法合并
- **涉及文件**：GitHub 仓库设置（非仓库内文件）
- **当前状态**：已完成。`main` 分支保护已启用，required checks: `Backend Tests`, `Frontend Tests`, `Contract Consistency`, `Build Verification`；`strict: true`（要求分支与 base 同步）；`enforce_admins: true`

**P0-2：启用 Dependabot 自动依赖更新** ✅

- **What**：新建 `.github/dependabot.yml`，覆盖 maven（4 模块）、npm（frontend + docs）、github-actions
- **自动化收益**：消除手动依赖监控；安全补丁自动提 PR，CI 自动验证
- **完成标准**：`.github/dependabot.yml` 存在；一周内出现 Dependabot PR
- **涉及文件**：新建 `.github/dependabot.yml`

**P0-3：安全扫描升级为 PR 阻断** ✅

- **What**：在 `test.yml` 新增 `security-scan` job（PR 触发），Trivy `exit-code: 1` 仅对 HIGH/CRITICAL 阻断；保留 `security-poc.yml` 作为每周全量深度扫描
- **当前状态**：已完成（PR #108）。`security-scan` job 已就位于 `test.yml`，PR 触发时 Trivy HIGH/CRITICAL 自动阻断；`security-poc.yml` 保留每周全量深度扫描（信息级）
- **自动化收益**：高危漏洞在 PR 阶段自动拦截，无需人工定期检查
- **完成标准**：引入已知 CVE 依赖的 PR 被 CI 阻断
- **涉及文件**：`.github/workflows/test.yml`、`.github/workflows/security-poc.yml`

**P0-4：覆盖率阈值逐步提升** ✅

- **What**：后端 JaCoCo 从 35%/35%/30% → 40%/45%/40%（第一阶段），后续按模块覆盖率基线逐步上调；配置 `.codecov.yml` 对新代码要求 patch coverage ≥ 80%
- **自动化收益**：自动防止覆盖率下降；新代码自动被更高标准约束
- **完成标准**：`pom.xml` 阈值更新后 CI 通过；Codecov patch check 出现在 PR 上
- **涉及文件**：`platform-backend/pom.xml`、新建 `.codecov.yml`

### P1：可靠性增强（自动检测问题）

**P1-1：SARIF 上传至 GitHub Security 面板** ✅

- **What**：在 `security-poc.yml` 和 `test.yml` 中用 `github/codeql-action/upload-sarif` 上传 Semgrep/Trivy SARIF
- **自动化收益**：漏洞具有生命周期跟踪（open/dismissed/fixed），不再是一次性 CI 输出
- **涉及文件**：`.github/workflows/test.yml`、`.github/workflows/security-poc.yml`
- **当前状态**：已完成（PR #73, commit `3afe670`）。三个 category 上传：`semgrep`（Semgrep SAST）、`trivy-sca`（Trivy 全量扫描）、`trivy-pr`（Trivy PR 级扫描），结果可在 GitHub Security → Code scanning alerts 面板查看

**P1-2：故障注入集成测试** ✅

- **What**：新增 `*IT.java` 测试：(a) S3 节点故障（Testcontainers MinIO 中断）、(b) 区块链节点不可达（mock）、(c) RabbitMQ 断连期间的 Outbox 处理
- **自动化收益**：Resilience4j 断路器、重试、Saga 补偿的行为在 CI 中持续验证
- **涉及文件**：`platform-backend/backend-web/src/test/java/cn/flying/test/fault/` 新增 4 个测试类
- **当前状态**：已完成。新增 `FaultInjectionBaseIT`（抽象基类）、`SagaCompensationIT`（5 个场景：S3/链上失败补偿、重试持久化、死信事件、REQUIRES_NEW 独立提交语义）、`OutboxPublisherIT`（5 个场景：发布成功、RabbitMQ 不可达退避、指数退避上限、多租户隔离）、`ResilienceConfigIT`（5 个配置校验）。共 15 个集成测试，无 Docker 时优雅跳过

**P1-3：自动化发布流程** ✅

- **What**：新建 `.github/workflows/release.yml`，tag push 触发：自动生成 Release Notes + 构建 SBOM 附件 + 构建容器镜像推送 ghcr.io
- **自动化收益**：消除手动编写 Changelog、手动构建部署包、手动生成 SBOM
- **涉及文件**：新建 `.github/workflows/release.yml`、新建各模块 `Dockerfile`
- **当前状态**：已完成。交付物：
  - `.dockerignore`：排除 git、docs、target 等非必要文件，缩小构建上下文
  - `platform-backend/Dockerfile`：maven:3.9-eclipse-temurin-21 多阶段构建，runtime `eclipse-temurin:21-jre-noble`，非 root 用户，EXPOSE 8000
  - `platform-fisco/Dockerfile`：同上，额外创建 `/app/conf/` 挂载点（TLS 证书），用户 home 可写（FISCO SDK 解压 native libs），EXPOSE 8091
  - `platform-storage/Dockerfile`：同上，EXPOSE 8092
  - `platform-frontend/Dockerfile`：node:20-alpine + pnpm 10 构建，nginx:1.27-alpine runtime，EXPOSE 80
  - `platform-frontend/nginx.conf`：gzip_static、SPA 路由回退、`/_app/` 不可变长缓存、`index.html` no-cache、安全头
  - `.github/workflows/release.yml`：tag push 触发，单 job（共享 Maven 缓存），Trivy CycloneDX SBOM，四镜像 ghcr.io 推送（semver + major.minor + sha tags），`softprops/action-gh-release@v2` 自动 Release Notes

**P1-4：服务器环境引导与验证** ✅

- **What**：创建环境验证脚本和完善部署文档，覆盖完整的服务器端基础设施栈
- **背景**：FISCO BCOS 节点初始化需要创世块生成、CA 证书签发、节点组网等多步骤服务器操作，
  无法简单 docker-compose 解决。`SdkBeanConfig` 在 Bean 创建时调用 `getBlockNumber()`，
  节点不可达则服务无法启动
- **交付物**：
  1. `scripts/env-check.sh` — 环境预检脚本，验证所有前置条件：
     - Nacos (8848) 可达且命名空间存在
     - MySQL (3306) 可访问，数据库已创建
     - Redis (6379) ping 成功
     - RabbitMQ (5672) 连接 + 管理 API (15672) 可达
     - FISCO BCOS 节点 (20200) 可达
     - S3/MinIO 端点可访问，bucket 已创建
     - TLS 证书存在于 `platform-fisco/src/main/resources/conf/`
     - 合约地址已配置于 `.env` 或 Nacos
  2. `docker-compose.infra.yml` — 仓库根目录实体文件（从文档中提取），
     包含**可容器化的服务**：Nacos、MySQL、Redis、RabbitMQ、MinIO
  3. 更新 `docs/` 部署文档，新增「服务器环境搭建」页面：
     - FISCO BCOS 节点安装、创世块创建、证书生成（链接官方文档）
     - 合约编译与部署步骤
     - Nacos 命名空间创建与配置导入
- **不包含**：不尝试容器化 FISCO BCOS 节点或自动化智能合约部署（归入 P1-5）
- **自动化收益**：消除试错式环境搭建；`env-check.sh` 即时反馈缺失项
- **完成标准**：`env-check.sh` 全项通过后，按文档操作可在全新服务器上启动全部服务
- **涉及文件**：新建 `scripts/env-check.sh`、新建 `docker-compose.infra.yml`、
  更新 `docs/zh/deployment/`、更新 `docs/en/deployment/`
- **当前状态**：已完成。交付物：
  - `docker-compose.infra.yml`：根目录可执行 compose 文件，含 8 个服务（Nacos/MySQL/Redis/RabbitMQ/MinIO x2/OTel Collector/Jaeger），全部配置 healthcheck + env 默认值
  - `scripts/env-check.sh`：8 项环境预检脚本，支持 `--fix` 自动修复和 `--service` 单项检查
  - `docs/zh/deployment/environment-setup.md` + `docs/en/deployment/environment-setup.md`：中英文环境搭建指南
  - `.env.example`：补充基础设施凭据变量（DB_PASSWORD, REDIS_*, RABBITMQ_*, NACOS_AUTH_TOKEN）

**P1-5：智能合约生命周期工具** ✅

- **What**：创建 `scripts/contract-deploy.sh`，封装 FISCO BCOS 控制台命令：
  1. 检查 FISCO BCOS 控制台已安装且节点可达
  2. 编译 Storage.sol 和 Sharing.sol
  3. 部署合约并捕获部署地址
  4. 自动更新 `.env` 中的合约地址
  5. 验证部署（调用合约只读方法）
- **背景**：当前合约部署完全手动且仓库内无文档。`platform-fisco/src/main/resources/abi/` 中的
  ABI 文件为静态签入，合约变更后无自动化路径从 `.sol` 到链上部署地址
- **自动化收益**：将多步手动操作收敛为单脚本调用；消除 ABI/合约不匹配风险
- **完成标准**：在已初始化的 FISCO BCOS 节点上运行脚本可完成双合约部署，输出地址可直接使用
- **当前状态**：已完成（2026-03-15）。交付物：
  - `scripts/contract-deploy.sh`：6 阶段自动化脚本（Pre-flight → Compile → Deploy → ABI Sync → Write-back → Verify）
  - 支持 `--dry-run`、`--skip-verify`、`--console-dir`、`--env-file` 选项
  - `scripts/README.md`：新增第 6 节合约部署使用说明
- **涉及文件**：新建 `scripts/contract-deploy.sh`、`platform-fisco/contract/Storage.sol`、
  `platform-fisco/contract/Sharing.sol`
- **可选增强**：
  - **WeBASE 集成**：评估 WeBASE-Front 可视化合约 IDE，用于合约调试和管理界面
  - **安全增强**：部署前可选运行 Slither 静态分析（需 Python 环境）；部署日志包含字节码哈希便于审计追溯
  - **ABI 校验**：部署前对比 `.sol` 编译产物与 `abi/` 签入文件的 SHA-256，不一致时中断并提示

### P2：未来能力（条件触发）

**P2-1：文件版本治理 MVP** ✅

- **What**：实现文件版本链 schema，支持版本历史查询与版本派生
- **前置条件**：P0 门禁全部就位后再实施
- **涉及文件**：DB 迁移 `V1.4.0__file_version_chain.sql`（RFC 已归档删除）
- **当前状态**：已完成（PR #68, commit `9fa95a8`）。实现证据：
  - DB 迁移：`V1.4.0__file_version_chain.sql`（正式 Flyway 迁移，取代草案脚本）
  - 并发控制：Redisson `RLock` 分布式锁防止同一版本链并发写入
  - REST API：`GET /api/v1/files/{id}/versions`（版本历史）、`POST /api/v1/files/{id}/versions`（派生新版本）
  - 前端：上传流支持 `targetFileId` 参数触发版本续写
  - 测试覆盖：`FileVersionServiceTest`（单元）、`FileVersionIntegrationTest`（集成）

**P2-2：OpenTelemetry 统一追踪** ✅

- **What**：三个 Java 服务接入 OTel Java Agent，替换可选的 SkyWalking
- **实施方案**：
  - **接入方式**：`-javaagent:opentelemetry-javaagent.jar` 自动注入，无需代码改动即可采集 HTTP/gRPC/JDBC/Redis span；**OTel Java Agent 已原生支持 Dubbo consumer/provider span + RPC metrics 自动注入**，无需额外插件 [置信度: High]
  - **传播协议**：W3C Trace Context（`traceparent`, `tracestate` 头），Dubbo Triple 协议自动传播
  - **采样策略**：`ParentBased` + `TraceIdRatio`（初始 10%），错误请求始终采样（`alwaysOn` for error spans）
  - **语义约定**：Span 命名遵循 `{verb} {object}` 格式（如 `POST /files`, `SELECT file_record`）
  - **业务上下文**：自定义属性 `tenant.id`, `user.id`, `file.hash` 注入 span，便于业务关联查询
  - **信号关联**：trace/span ID 注入 log MDC（Logback `%X{trace_id}`），metrics 和 logs 可通过 trace ID 关联
  - **导出链路**：OTLP gRPC → Jaeger / Grafana Tempo（可视化） + Prometheus（metrics）
- **触发条件**：跨服务问题排查成本明显增加时
- **自动化收益**：零代码改动获得全链路追踪；故障定位从"翻日志"变为"看 trace"
- **涉及文件**：各服务 `Dockerfile` 或启动脚本（添加 `-javaagent`）、Nacos 配置（采样率）
- **当前状态**：已完成。实现证据：
  - OTel Java Agent v2.26.1 集成至三个服务 Dockerfile
  - `scripts/start.sh` 新增 `--otel` 启动选项（与 `--skywalking` 互斥）
  - 三个 `logback-spring.xml` 新增 OTel trace context MDC 字段
  - `docker-compose.infra.yml` 新增 OTel Collector + Jaeger 服务
  - `backend-web` 补齐 `micrometer-registry-prometheus` 依赖
  - OTel Collector 配置：`config/otel-collector-config.yaml`

**P2-3：后量子密码学准备**

- **What**：评估 PQC 算法对文件哈希和加密层的影响
- **NIST 标准现状**（截至 2025）：
  - **FIPS 203 (ML-KEM)**：基于格的密钥封装机制，已发布最终标准
  - **FIPS 204 (ML-DSA)**：基于格的数字签名，已发布最终标准
  - **FIPS 205 (SLH-DSA)**：基于哈希的数字签名（无状态），已发布最终标准
  - **HQC**：已于 2025-03 正式入选第五算法（基于码的 KEM，作为 ML-KEM 的备选方案）
- **影响评估**：
  - **对称加密（AES-256）**：量子安全，无需迁移
  - **文件哈希（SHA-256）**：Grover 算法降低安全性至 128-bit，仍在安全范围内
  - **密钥交换（RSA/ECDH）**：需评估迁移至 ML-KEM 的路径
  - **国密 SM2/SM3**：SM3 量子安全性同 SHA-256；SM2（椭圆曲线）需跟踪国家 PQC 标准进展
- **迁移时间线**：参考 NIST 建议 2030-2035 完成迁移；当前阶段为调研和算法评估
- **触发条件**：监管要求出现，或 Java 生态 PQC 库（如 Bouncy Castle PQC）达到生产就绪时

**P2-4：智能合约安全测试增强** ✅

- **What**：在 CI 中集成 Slither 静态分析，覆盖 `.sol` 合约变更（Echidna/Foundry 模糊测试待 FISCO BCOS 编译器兼容性验证后引入）
- **实施方案**：
  - `.sol` 文件变更时触发安全扫描 workflow
  - Slither 静态分析：检测重入攻击、整数溢出、未检查返回值等常见漏洞模式
  - 初期信息级不阻断 PR，积累基线数据后评估升级为阻断
- **触发条件**：合约逻辑复杂度增加时（如新增合约或修改核心存证/分享逻辑）
- **自动化收益**：合约安全检查从人工审计变为 CI 自动执行
- **涉及文件**：新建 `.github/workflows/contract-security.yml`、`platform-fisco/contract/`
- **当前状态**：已完成。交付物：
  - `.github/workflows/contract-security.yml`：PR 触发（`.sol` 变更或 workflow 自身变更）+ 手动触发
  - Slither 静态分析：`solc-select` 指定 0.8.11 版本，排除 informational/optimization 级别
  - SARIF 上传至 GitHub Security 面板（category: `slither`）
  - CI Step Summary 生成 Markdown 报告
  - 信息级不阻断 PR（`continue-on-error: true`）

**P2-5：文件完整性批量验证（Merkle Tree）**

- **What**：批量文件哈希聚合为 Merkle Root 上链，减少链上交易数；审计时提供 Merkle Proof 验证单文件归属
- **实施方案**：
  - 批量上传场景：收集 N 个文件哈希 → 构建 Merkle Tree → 仅 Root 上链（1 笔交易替代 N 笔）
  - 单文件验证：提供 Merkle Proof（O(log N) 个中间哈希），链下可独立验证文件归属
  - 参考方案：
    - OpenTimestamps Calendar Server 聚合模式：将多个时间戳请求批量锚定到比特币区块，日均处理数万请求仅消耗 1 笔链上交易 [置信度: High]
    - Blockcerts v3：W3C VC 兼容批量 Merkle 存证，支持批量发行与独立验证 [置信度: High]
    - EAS（Ethereum Attestation Service）：9.1M attestations，12+ 链，batch attestation 模式，ZK attestation 研究中 [置信度: High]
    - OpenAttestation（已于 2025-10 归档，迁移至 TrustVC.io）：原新加坡政府存证 SDK，TradeTrust 为后继 [置信度: High]
    - Merkle Mountain Ranges（MMR）：支持流式追加场景（无需预知叶节点总数），适用于持续写入的存证流水 [置信度: Medium]
    - Verkle 树：Merkle 变体，证明体积比标准 Merkle Proof 更小（接近固定大小），适用于高频批量验证场景 [置信度: Medium]
    - BBS+ 签名：W3C VC 2.0 选择性披露方案，可在 Merkle 聚合存证基础上实现隐私保护验证 [置信度: Medium]
- **触发条件**：批量上传场景增多，或链上交易成本/频率成为瓶颈时
- **自动化收益**：链上交易数从 O(N) 降至 O(1)；审计验证保持 O(log N) 效率
- **涉及文件**：`platform-fisco/` 新增 Merkle Tree 工具类、`platform-backend/backend-service/` 批量存证逻辑

**P2-6：可观测性驱动 SLO/SLI 体系** ✅

- **What**：基于 OTel 指标定义 SLI/SLO，建立燃尽率告警机制
- **实施方案**：
  - **SLI 定义**：上传成功率、存证 P99 延迟、存储可用性、API 错误率
  - **SLO 目标**：上传成功率 ≥ 99.5%、存证 P99 ≤ 5s、存储可用性 ≥ 99.9%
  - **告警策略**：燃尽率告警（Burn Rate Alert），基于滑动窗口检测 SLO 预算消耗速率
  - **仪表盘**：Grafana 展示 SLO 达成率、错误预算剩余、趋势预测
- **触发条件**：生产环境稳定运行且 P2-2 OTel 追踪就位后
- **前置依赖**：P2-2（OpenTelemetry 统一追踪）
- **自动化收益**：从"感觉系统不稳"变为"SLO 预算还剩 X%"的量化决策
- **涉及文件**：告警规则配置、Grafana 仪表盘 JSON、OTel Collector 配置、`platform-storage` 指标导出清理逻辑
- **当前状态**：已完成。交付物：
  - `config/prometheus/recording-rules.yml`：SLI 预计算规则（上传成功率、存证延迟、存储可用性、API 错误率），多窗口（5m/30m/1h/6h/1d/30d）
  - `config/prometheus/alerting-rules.yml`：燃尽率告警（critical/warning/info 三级）+ 错误预算耗尽告警 + 断路器告警
  - `config/grafana/slo-dashboard.json`：Grafana SLO Overview 仪表盘（7 行面板：总览、错误预算、上传成功率、存证延迟、存储可用性、API 错误率、断路器）
  - `platform-storage/src/main/java/cn/flying/storage/core/S3Monitor.java`：退役节点指标标签清理，避免旧节点污染存储可用性与负载告警口径

**P2-7：存储完整性周期校验** ✅

- **What**：后台定时任务对 S3 存储文件进行 re-hash 校验，比对链上哈希，检测静默数据损坏（bit rot）或篡改
- **实施方案**：
  - 参考 Filecoin Provable Data Possession（PDP）模式，首期采样校验 1%/天，覆盖全量约需 100 天 [置信度: Medium]
  - 校验流程：`DistributedStorageService.download()` → 计算 SHA-256 → `BlockChainService.getFile()` 比对链上哈希
  - 不一致记录写入告警表，触发管理员通知（复用现有 SSE 通知通道）
  - 支持手动触发全量校验（合规审计场景）
- **触发条件**：存储节点故障恢复后需验证数据完整性，或合规审计要求提供存储证明时
- **自动化收益**：从"出问题才发现数据损坏"变为"持续自动检测"；合规审计可一键出报告
- **涉及文件**：`platform-backend/backend-service/` 新增定时任务与校验服务、`platform-backend/backend-dao/` 告警记录表
- **当前状态**：已完成。实现证据：
  - DB 迁移：`V1.5.0__integrity_alert.sql`（告警记录表）
  - 核心服务：`IntegrityCheckService.java`（S3 存在性检查 + DB-链上哈希比对）
  - 定时调度：`IntegrityCheckScheduler.java`（默认每天凌晨 2 点，采样率 1%）
  - REST API：`IntegrityAlertController.java`（告警查询/确认/解决/手动触发）
  - SSE 通知：新增 `INTEGRITY_ALERT` 事件类型，实时推送管理员
  - 分布式锁：Redisson 防止多实例并发执行
  - 测试覆盖：`IntegrityCheckServiceTest`（11 个单元测试）、`IntegrityAlertControllerIT`（集成测试）
  - 注意：因 S3 存储加密内容，采用"存在性验证 + DB-链哈希比对"策略，而非内容 re-hash

**P2-8：多方签名存证**

- **What**：支持多方协作存证，采用 M/N 阈值签署确认模式（如 2/3），适用于机构间协作场景
- **实施方案**：
  - 参考 FISCO BCOS evidenceSample 工厂合约模式：发起方创建存证提案 → 参与方逐一签署 → 达到阈值后上链确认 [置信度: High]
  - 新增 `MultiPartyStorage.sol` 合约，包含提案创建、签署、阈值判定、最终确认逻辑
  - 后端新增提案管理服务：提案生命周期（草稿→待签→已确认/已过期）、签署通知
  - 前端新增协作签署工作流 UI
- **触发条件**：机构间协作存证需求出现，或需要多方见证的高价值文件存证场景
- **自动化收益**：从线下多方签字确认变为链上自动阈值判定；签署过程全程可审计
- **涉及文件**：`platform-fisco/contract/` 新增 `MultiPartyStorage.sol`、`platform-backend/backend-service/` 提案管理服务、`platform-frontend/` 协作 UI

### P3：远期探索（方向性评估）

> P3 条目为远期技术方向探索，不承诺实施时间。每个条目标注触发条件，满足时升级为 P2 并细化方案。

**P3-1：内容寻址存储层（IPFS/Filecoin）**

- **What**：引入混合存储架构，热数据保持 S3，冷数据/归档迁移至 IPFS pinning + Filecoin 存储证明
- **技术要点**：
  - 热数据（近期访问）：保持当前 S3 兼容存储，低延迟读写
  - 冷数据（归档/合规保留）：IPFS pinning 服务 + Filecoin 存储交易，成本显著降低
  - 内容寻址（CID）：基于文件内容生成唯一标识，天然支持跨租户去重和完整性验证
  - 可验证存储：Filecoin 的 Proof-of-Spacetime 提供链上存储证明
  - **Storacha**（原 web3.storage）：基于 UCAN 授权的 IPFS 热存储网关，提供 S3 兼容 API + IPFS CID 双寻址，可作为 S3→IPFS 过渡方案 [置信度: Medium]
  - **s3x**：MinIO fork，实现 S3 API → IPFS 透明转换，现有 S3 客户端无需改动即可写入 IPFS [置信度: Medium]
  - **go-ds-s3**：IPFS 官方 S3 数据存储后端，将 S3 作为 IPFS 节点的持久化层 [置信度: High]
  - **Filecoin PDP**（Provable Data Possession）：周期性存储证明，验证存储提供者确实持有数据 [置信度: High]
  - **Filecoin F3**（Fast Finality）：将最终确认时间从 ~30 分钟降至 ~5 秒，大幅改善存储交易体验 [置信度: Medium]
- **触发条件**：存储成本优化需求明确，或可验证存储成为合规要求时
- **涉及文件**：`platform-storage/` 存储抽象层、新增 IPFS/Filecoin 适配器

**P3-2：零知识证明隐私存证**

- **What**：实现"证明文件存在但不泄露内容"的隐私保护存证能力
- **技术要点**：
  - **zk-SNARKs**：证明体积小（~200B）、验证快（~毫秒级），但需可信设置（trusted setup）
  - **zk-STARKs**：无需可信设置、具备后量子安全特性，但证明体积较大（~100KB）
  - 应用场景：文件存在性证明、选择性属性披露（证明文件属于某类别但不暴露内容）
  - 链上验证：在 FISCO BCOS 上部署 ZKP 验证合约
  - **学术参考**：ZKBAR-V（基于 ZKP 的区块链审计报告验证框架）、VeriNet（可验证神经网络推理）提供了 ZKP 在数据验证场景的可行性验证 [置信度: Low]
  - **FISCO BCOS 3.x 隐私计算**：内置隐私计算模块（同态加密、群环签名），可作为 ZKP 全方案的阶段性替代 [置信度: Medium]
- **触发条件**：隐私合规需求（如 GDPR 数据最小化原则）或高价值客户明确需求时
- **涉及文件**：新增 ZKP 电路、`platform-fisco/` ZKP 验证合约、`platform-backend/` 证明生成服务

**P3-3：跨链互操作性**

- **What**：扩展当前双链适配架构，探索跨链存证互认能力
- **现状**：已有 FISCO BCOS + Besu 双链适配（互斥配置，通过 `blockchain.type` 切换）
- **潜在方向**：
  - 双链同步写入：同一存证同时锚定到两条链，提升可信度
  - 跨链存证验证：A 链存证可在 B 链验证，实现跨组织互认
  - 中继/桥接方案：评估轻节点验证或第三方跨链桥
  - **至信链司法互认通道**：已对接 31 省 300+ 法院，存证数据可直接作为电子证据采信；评估通过跨链桥接实现存证结果向司法链的锚定 [置信度: Medium]
  - **长安链 SPV 轻节点跨链**：基于简单支付验证的轻量跨链方案，无需完整节点同步即可验证对方链上存证 [置信度: Low]
  - **标准化方向**：IEEE P3205（区块链互操作性）、信通院跨链互操作规范，为跨链方案选型提供合规框架 [置信度: Medium]
- **触发条件**：多链部署需求明确，或跨组织存证互认成为业务需求时
- **涉及文件**：`platform-fisco/` 多链适配层、新增跨链桥接模块

**P3-4：分布式身份与可验证凭证（DID/VC）**

- **What**：基于 W3C DID + Verifiable Credentials 2.0 标准，将存证结果封装为可验证凭证，支持跨平台独立验证
- **技术要点**：
  - **W3C VC 2.0**：已于 2025-05-15 正式发布为 W3C Recommendation（7 项规范），定义凭证数据模型、验证协议及 BBS+ 选择性披露 [置信度: High]
  - **WeIdentity**：微众银行开源的 FISCO BCOS DID 全栈实现，可复用现有 FISCO 基础设施 [置信度: High]
  - 存证结果封装为 VC：包含文件哈希、时间戳、签发者 DID、链上交易 ID
  - 验证方无需接入本平台即可独立验证 VC 签名和链上锚定
  - 用户身份从平台内部 ID 演进为 DID，支持自主身份管理
- **触发条件**：跨组织验证需求出现，或凭证标准化成为行业要求时
- **涉及文件**：`platform-fisco/` DID 注册合约、`platform-backend/` VC 签发与验证服务、`platform-frontend/` 凭证展示 UI

---

## 4. 技术演进策略

### 4.1 依赖管理策略

| 策略 | 说明 |
|------|------|
| 安全补丁 | Dependabot PR 自动创建，及时合并 |
| LTS 版本 | 评估并在发布后一个月内采纳；当前已跟踪 Spring Boot 3.5.11、Java 21 |
| EOL 依赖 | 在 EOL 前规划迁移 |
| 中间版本 | 不追非 LTS 中间版本 |

### 4.2 区块链演进策略

| 策略 | 说明 |
|------|------|
| FISCO BCOS 版本 | 跟踪 FISCO BCOS 3.x 发布；升级前必须验证合约 ABI 向后兼容 |
| 智能合约变更 | 任何 `.sol` 修改需重新生成 ABI、重新部署合约并更新地址配置 |
| WeBASE 评估 | 跟踪 WeBASE 中间件发布，评估合约管理可视化的集成价值（→ P1-5） |
| 合约升级模式 | 当前为全量重部署；合约逻辑稳定后评估代理合约（Proxy Pattern）升级模式 |

### 4.3 密码学演进策略

| 策略 | 说明 |
|------|------|
| 对称加密（AES-256） | 量子安全，当前无需迁移 |
| 哈希算法（SHA-256） | 量子安全性充足（128-bit），持续监控 |
| 密钥交换（RSA/ECDH） | 跟踪 NIST PQC 标准（FIPS 203 ML-KEM），2030 前完成评估 |
| 国密算法（SM2/SM3/SM4） | SM3/SM4 量子安全；SM2 需跟踪国家 PQC 标准进展 |

### 4.4 存储演进策略

| 策略 | 说明 |
|------|------|
| S3 兼容层 | 保持 S3 API 作为统一存储接口，后端实现可替换 |
| 内容寻址 | 评估 IPFS CID 作为文件标识的可行性（→ P3-1） |
| 冷热分层 | 评估热数据 S3 + 冷数据 IPFS/Filecoin 的混合架构成本模型（→ P3-1） |

### 4.5 当前技术栈

| 组件 | 当前版本 | 下一目标版本 | 备注 |
|------|----------|-------------|------|
| Java | 21 (LTS) | 25 (LTS, 2025-09-16 已发布) | 评估 Gatherer/Structured Concurrency 等新 API |
| Spring Boot | 3.5.11 | 4.0.x (2025-11 已发布) | 跟踪 4.0；Virtual Threads 已启用 |
| Dubbo | 3.3.6 (Triple) | 3.3.x | 保持 Triple 协议 |
| Svelte | 5.53+ | 5.x | Runes API 已稳定 |
| SvelteKit | 2.53+ | 2.x | — |
| MySQL | 8.0 | 8.0 / 9.x | 评估 9.x 新特性 |
| Redis | 7 | 7.x | — |
| RabbitMQ | 3 | 3.x / 4.x | 跟踪 4.0 发布 |
| MinIO (S3) | — | — | 保持 S3 兼容 API |
| FISCO BCOS | 3.8.0 | 3.x | 跟踪社区发布 |

---

## 附录

### 附录 A：RFC 索引

| RFC 文件 | 状态 | 关联优先级 | 说明 |
|----------|------|-----------|------|
| `plan/gate-policy.md` | 活跃 | P0 全量 | 门禁分层策略，定义 PR 阻断 / 发布阻断 / 人工检查分级 |

### 附录 B：术语表

| 术语 | 全称 | 说明 |
|------|------|------|
| PQC | Post-Quantum Cryptography | 后量子密码学，抵御量子计算机攻击的密码算法 |
| ZKP | Zero-Knowledge Proof | 零知识证明，证明者向验证者证明某个陈述为真而不泄露任何额外信息 |
| zk-SNARKs | Zero-Knowledge Succinct Non-Interactive Arguments of Knowledge | 简洁非交互式零知识证明，证明小、验证快 |
| zk-STARKs | Zero-Knowledge Scalable Transparent Arguments of Knowledge | 透明零知识证明，无需可信设置，后量子安全 |
| OTel | OpenTelemetry | 开源可观测性框架，统一 traces/metrics/logs 采集 |
| SLO | Service Level Objective | 服务级别目标，如"可用性 ≥ 99.9%" |
| SLI | Service Level Indicator | 服务级别指标，SLO 的度量数据源 |
| WeBASE | Web3 App & Blockchain Engine | FISCO BCOS 中间件平台，提供合约管理、交易审计等功能 |
| Merkle Tree | — | 哈希树，叶节点为数据哈希，父节点为子节点哈希的聚合 |
| CID | Content Identifier | IPFS 内容标识符，基于文件内容哈希生成的唯一地址 |
| ML-KEM | Module-Lattice-Based Key-Encapsulation Mechanism | NIST FIPS 203，基于格的密钥封装机制 |
| ML-DSA | Module-Lattice-Based Digital Signature Algorithm | NIST FIPS 204，基于格的数字签名算法 |
| OTLP | OpenTelemetry Protocol | OTel 数据传输协议，支持 gRPC 和 HTTP |
| PDP | Provable Data Possession | 可证数据持有，存储提供者定期证明仍持有完整数据 |
| MMR | Merkle Mountain Range | 默克尔山脉，支持流式追加的 Merkle 变体结构 |
| DID | Decentralized Identifier | 分布式标识符，W3C 标准，用户自主控制的去中心化身份 |
| VC | Verifiable Credential | 可验证凭证，W3C 标准，可独立验证的数字凭证格式 |
| UCAN | User Controlled Authorization Networks | 用户控制授权网络，基于 JWT 的去中心化授权协议 |
| F3 | Fast Finality | Filecoin 快速最终性，将确认时间从 ~30 分钟降至 ~5 秒 |

### 附录 C：外部参考项目

| 项目 | 简介 | 关联方向 | 参考价值 |
|------|------|---------|---------|
| [OpenTimestamps](https://opentimestamps.org/) | 比特币区块链时间戳锚定，Calendar Server 批量聚合 | P2-5 | Merkle 聚合模式、日均处理量参考 |
| [OpenAttestation](https://www.openattestation.com/) | ⚠️ 已于 2025-10 归档，推荐迁移至 TrustVC.io；原新加坡政府文档存证框架，Merkle Root 批量存证 | P2-5 | 批量存证 SDK 设计历史参考；后继项目为 TradeTrust |
| [TradeTrust](https://www.tradetrust.io/) | 新加坡政府文档存证框架（OpenAttestation 后继），政府级部署 | P2-5 | 批量存证 + 可验证文档 + TrustVC 凭证 |
| [EAS](https://attest.org/) | Ethereum Attestation Service，9.1M attestations，12+ 链，ZK attestation 研究中 | P2-5 | Batch attestation 模式、链上/链下 attestation 数据模型 |
| [ProofPack](https://proofpack.dev/) | JSON 可验证数据交换格式，轻量化存证封装 | P2-5, P3-4 | 存证结果的轻量化封装与跨平台交换 |
| [Blockcerts](https://www.blockcerts.org/) | W3C VC 兼容批量 Merkle 存证，OpenBadges 兼容 | P2-5 | Merkle 批量发行、W3C VC 2.0 集成参考 |
| [evidenceSample](https://github.com/FISCO-BCOS/evidenceSample) | FISCO BCOS 官方存证合约示例，工厂模式创建存证 | P2-8 | 多方签名合约模式、FISCO 合约最佳实践 |
| [WeBASE](https://github.com/WeBankBlockchain/WeBASE) | 微众银行区块链中间件平台，合约管理 + 交易审计 | P1-5 | 合约生命周期管理、可视化运维 |
| [WeIdentity](https://github.com/WeBankBlockchain/WeIdentity) | FISCO BCOS 上的 DID + VC 全栈实现 | P3-4 | DID 注册、VC 签发验证、可复用 FISCO 基础设施 |
| [Storacha](https://storacha.network/) | 原 web3.storage，UCAN 授权 + IPFS 热存储网关 | P3-1 | S3 兼容 API → IPFS CID 过渡方案 |
| [s3x](https://github.com/minio/s3x) | MinIO fork，S3 API → IPFS 透明转换，现有 S3 客户端无需改动 | P3-1 | S3 到 IPFS 无缝迁移路径 |
| [go-ds-s3](https://github.com/ipfs/go-ds-s3) | IPFS 官方 S3 数据存储后端 | P3-1 | 将 S3 作为 IPFS 持久化层的参考实现 |
| 至信链 | 腾讯联合多家法院建设的司法联盟链，31 省 300+ 法院互认 | P3-3 | 司法互认通道、电子证据采信标准 |
| 长安链 | 国家级区块链基础设施，10 万+ TPS，SPV 轻节点跨链 | P3-3 | 高性能共识参考、跨链验证方案 |
