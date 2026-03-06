# Security Residual Findings (2026-03-05)

## 1) 告警快照（基线 vs 当前远端）

- 基线（开始修复前）：
  - Dependabot open: 16
  - Code scanning open: 151
  - Secret scanning open: 0
  - 证据文件：`plan/security-remediation-20260305/baseline/*.json`
- 当前远端（本地修复完成但尚未 push）：
  - Dependabot open: 16
  - Code scanning open: 151
  - Secret scanning open: 0
  - 证据文件：`plan/security-remediation-20260305/current-remote/*.json`

说明：当前分支尚未推送并触发仓库安全扫描，因此远端开口数量仍与基线一致。

## 2) 无补丁项（按要求跳过）

- 包：`dompurify`
- GitHub Dependabot 告警：`#39`
- 证据（来自 GitHub API 导出）：
  - `vulnerable_version_range`: `>= 3.1.3, <= 3.3.1`
  - `first_patched_version`: `null`
  - 见：`plan/security-remediation-20260305/current-remote/dependabot-open-full.json`

结论：上游未给出可用修复版本，按要求标记“无补丁跳过”，不执行 dismiss/ignore。  
置信度：High（直接来自 GitHub API 字段）。

## 3) 不可利用项（代码证据）

### 3.1 `EndpointRequest.to()` 未使用

- 检索命令：`rg -n "EndpointRequest|EndpointRequest\\.to\\(" platform-backend -S`
- 结果：无匹配（exit code 1）

结论：当前代码基线不存在 `EndpointRequest.to()` 调用链，相关触发条件不成立。  
边界：后续若引入 Spring Security Endpoint DSL 或相关导入，本结论失效需复核。  
置信度：High（全仓检索结果）。

### 3.2 `"/null"` 路径未出现

- 检索命令：`rg -n "/null" platform-backend -S`
- 结果：无匹配（exit code 1）

结论：当前后端路由与安全配置中未出现 `"/null"` 触发路径。  
边界：若新增路由或反向代理改写到该路径，需要重新评估。  
置信度：High（全仓检索结果）。

## 4) 方法级安全边界说明

- `@EnableMethodSecurity` 已启用：
  - `platform-backend/backend-web/src/main/java/cn/flying/config/SecurityConfiguration.java` 第 55 行
- `@PreAuthorize` 在多控制器上存在（例如 `SysAuditController` 第 52 行及其他控制器）

结论：与方法级授权相关的规则仍在生效，不能把所有 Spring Security 相关告警一概归类为“不可利用”；需以具体触发条件逐条判定。  
置信度：High（代码静态证据）。

## 5) 处置原则落实

- 本次仅做依赖与锁文件修复，不新增/删除对外 API、DTO、协议。
- 未使用 `dismiss`、`ignore`、`.trivyignore` 进行隐藏处理。

## 6) 受控升级试探与回退记录

- 已执行一次 Spring Boot 小范围试探升级（`3.2.11 -> 3.4.5`）以尝试收敛剩余高危项。
- 在 `platform-backend` 测试阶段出现错误：
  - 用例：`cn.flying.service.impl.ImageServiceImplTest$FetchImageTests.fetchImage_success`
  - 关键根因：`NoClassDefFoundError: okhttp3/Headers`
  - 伴随报错：Mockito 无法 mock `io.minio.GetObjectResponse`
- 按既定策略回退该试探，仅保留已验证稳定的依赖修复集（四个 parent 均回退到 `3.2.11`）。

结论：该升级试探在当前代码与依赖组合下不可直接落地，需后续专门兼容性改造后再推进。  
置信度：High（来自本次测试实测输出）。
