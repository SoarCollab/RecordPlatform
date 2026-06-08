# 安全告警分析和处理计划

## 当前状态（PR #244 提交后）

### 已修复（待 Dependabot 确认关闭）
- ✅ Tomcat 10.1.55 (Critical × 3 + High × 3 + Low × 1)
- ✅ Spring Boot 3.5.14 (High × 6+)
- ✅ Jackson Core 3.1.4 (High × 2 + Medium × 1)

**预计关闭告警数**: 15+ 个

### 剩余待处理告警（~33个）

## 按优先级分类

### 🔴 Critical (2个) - Vitest
**告警**: CVE-2026-47429 - Vitest UI 任意文件读取
- 当前: vitest 2.1.9
- 修复: vitest 4.1.0+
- **影响评估**: 
  - ✅ 仅开发环境
  - ✅ 未暴露到网络
  - ✅ macOS 环境（主要影响 Windows）
  - **建议**: 单独 PR 处理，需测试 Vitest 4.x 兼容性

### 🟠 High (10个)

#### 1. Bouncy Castle (2个)
- **告警**: Covert Timing Channel
- **包**: org.bouncycastle:bcprov-jdk18on
- **影响**: 加密库时序攻击
- **建议**: 升级到最新版本

#### 2. fast-uri (2个)
- **告警**: Path traversal + Host confusion
- **包**: fast-uri (npm)
- **影响**: 前端依赖
- **建议**: 检查是否可升级或使用 pnpm overrides

#### 3. Spring Boot/Spring MVC (6个)
- **告警**: 已在 PR #244 修复
- **状态**: 等待 Dependabot 确认

### 🟡 Medium (15个)

#### 1. Spring Security Core (5个)
- **告警**: TOCTOU race condition (JdbcOneTimeTokenService)
- **影响**: 仅当使用 One-Time Token 登录时
- **评估**: 项目使用 JWT，不使用 OTT
- **建议**: 可忽略或升级到最新 Spring Security

#### 2. PostCSS (2个)
- **告警**: XSS via unescaped `</style>`
- **影响**: 前端构建工具
- **建议**: 升级到最新版本

#### 3. Bouncy Castle LDAP (2个)
- **告警**: LDAP injection
- **影响**: 仅当使用 LDAP 时
- **评估**: 项目不使用 LDAP
- **建议**: 可忽略或升级

#### 4. Spring WebMVC (6个)
- **告警**: 已在 PR #244 修复
- **状态**: 等待确认

### 🟢 Low (11个)

#### 1. Spring Security Core (5个)
- **告警**: User attribute enumeration
- **影响**: 可推断用户是否存在
- **评估**: 低风险，常见问题
- **建议**: 升级到最新 Spring Security 或接受风险

#### 2. Spring WebMVC (6个)
- **告警**: Cache poisoning
- **影响**: 静态资源缓存投毒
- **评估**: 低风险
- **建议**: 已在 PR #244 修复

## 处理建议

### 立即处理 (PR #244 合并后)
1. ✅ 等待 CI 通过
2. ✅ 合并 PR #244
3. 🔄 验证 Dependabot 关闭对应告警

### 下一步 (新 PR)
4. 🔄 升级 Bouncy Castle (High × 2 + Medium × 2)
5. 🔄 升级前端依赖 (fast-uri, postcss)
6. 🔄 评估 Vitest 4.x 升级影响

### 可选/忽略
7. ⚪ Spring Security 告警（不使用受影响功能）
8. ⚪ Bouncy Castle LDAP（不使用 LDAP）

## 忽略告警的标准

可以安全忽略以下类型的告警：
1. **功能未使用**: 漏洞在项目未使用的功能中
2. **开发环境**: 仅影响开发环境且未暴露
3. **已缓解**: 项目配置已避免触发条件
4. **误报**: 工具误判

## 后续监控

### 自动化
- Dependabot 每周自动扫描
- 使用 `security-scan` workflow 定期检查

### 手动审查
- 每月审查 Medium/Low 告警
- 每季度审查忽略的告警

## 参考资源

### Dependabot 操作
```bash
# 忽略告警
gh api -X PATCH repos/SoarCollab/RecordPlatform/dependabot/alerts/{alert_number} \
  -f state=dismissed -f dismissed_reason=<reason>

# 查看告警
gh api repos/SoarCollab/RecordPlatform/dependabot/alerts
```

### 常用 dismissed_reason
- `no_bandwidth` - 暂无带宽处理
- `tolerable_risk` - 可接受风险
- `inaccurate` - 不准确的告警
- `not_used` - 功能未使用
