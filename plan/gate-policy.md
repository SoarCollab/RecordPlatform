# W2 门禁分层策略（Sprint-02 执行版）

## 1. PR 合并阻断

| 项目 | 当前状态 | 目标状态 | 证据路径 |
|---|---|---|---|
| 后端/前端测试通过 | 已启用 | 持续阻断 | `.github/workflows/test.yml` |
| 构建通过 | 已启用 | 持续阻断 | `.github/workflows/test.yml` |
| backend-web 覆盖率阈值 | 已启用 | 持续阻断 | `platform-backend/backend-web/pom.xml` |
| backend-service/backend-common 覆盖率阈值 | 已启用 | 持续阻断 | `platform-backend/backend-service/pom.xml`, `platform-backend/backend-common/pom.xml` |
| 前端 lint/check | 已启用 | 持续阻断 | `.github/workflows/test.yml`, `platform-frontend/package.json` |
| 契约一致性检查 | 已启用 | 持续阻断 | `.github/workflows/test.yml`, `platform-backend/backend-web/src/test/java/cn/flying/contract/OpenApiContractExportTest.java`, `platform-frontend/package.json` |

## 2. 发布阻断

| 项目 | 当前状态 | 目标状态 | 证据路径 |
|---|---|---|---|
| 性能 smoke/load | 手动执行 | 发布阻断（保持） | `.github/workflows/perf-smoke.yml` |
| SAST（Semgrep） | 每周定时，信息级，自动观测汇总链路就绪 | 待首轮数据校准后升级为阻断 | `.github/workflows/security-poc.yml`, `tools/security/scripts/render-security-poc-observation.mjs` |
| SCA（Trivy）全量 | 每周深度扫描，信息级；PR 级 HIGH/CRITICAL **已升级为阻断** ✅ | PR 级已阻断；全量扫描保持信息级 | `.github/workflows/security-poc.yml`, `.github/workflows/test.yml` |
| SBOM（CycloneDX） | 每周定时，信息级 | 发布阻断（待评估） | `.github/workflows/security-poc.yml` |
| 可恢复性验证 | 部分覆盖 | 发布阻断 | 待补充 |
| 智能合约安全扫描 | 未实现 | P2-4 条件触发后评估 | 待创建 |

## 3. 人工检查

| 项目 | 频率 | 输出物 |
|---|---|---|
| API inventory 对齐 | 双月 | 对齐报告 |
| 模块边界审计 | 双月 | 边界分析报告 |
| 合规时间窗复核 | 双月 | 风险与演练记录 |

## 4. 规则

- 不得将“未自动化/待外部操作”门禁标注为“已启用阻断”。
- 性能门禁执行口径固定为“仅发布阻断”。
- 业务错误语义保持 `HTTP 200 + 业务错误码`。
- 仓库外配置项必须附审计证据（链接或截图）。

## 5. W2 Sprint-02 执行状态（已归档，截至 2026-02-15）

所有 W2 Sprint-02 任务均已完成。以下为关键里程碑摘要：

- **分支保护**：全部 4 项 required checks（Backend Tests, Frontend Tests, Contract Consistency, Build Verification）已启用；strict=true, enforce_admins=true
- **契约一致性**：OpenAPI 导出 + types 生成链路已稳定接入 CI
- **批量下载回归**：后端 + 前端关键用例已纳入测试套件
- **配额灰度治理**：审计能力闭环，含白名单/回滚/告警/审计写入
- **检索性能归档**：代码闭环（keywordMode + 索引补强 + k6 回填链路），实测数据待回填
- **安全门禁 PoC**：自动观测汇总链路就绪，待首轮数据校准阈值

## 6. 开放事项

| 方向 | 优先级 | 动作 | 验收口径 |
|---|---|---|---|
| 检索性能实测归档 | P0 | 执行 k6 smoke/load 并回填报告 | 阈值结果与 RUN_ID 可追溯 |
| 安全门禁阈值校准 | P1 | 回填首轮 security-poc 运行数据，校准 SAST 阻断阈值 | 输出阻断升级建议（P0/P1 漏洞阈值） |

## 7. 已完成门禁摘要（截至 2026-03-14）

| 门禁子项 | 完成时间 | 说明 |
|---|---|---|
| PR 测试/构建/覆盖率/契约一致性 | 2026-02 | 仓库内 CI 稳定阻断，含 JaCoCo + Vitest 覆盖率阈值 |
| GitHub 分支保护 required checks | 2026-02 | 4 项 check（Backend/Frontend Tests, Contract Consistency, Build Verification），strict=true, enforce_admins=true |
| SARIF 安全面板接入 | 2026-02 (PR #73) | Semgrep + Trivy 三个 category 上传至 GitHub Security 面板 |
| PR 级 Trivy HIGH/CRITICAL 阻断 | 2026-03 (PR #108) | security-scan job 已纳入 test.yml，exit-code: 1 |
| Spring Boot 升级 3.2.11 → 3.5.11 | 2026-03 (PR #109) | Virtual Threads 已启用，所有 CI 通过 |
| 文件版本链 MVP | 2026-02 (PR #68) | V1.4.0__file_version_chain.sql 正式迁移，分布式锁防并发 |
