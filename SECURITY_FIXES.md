# 安全告警修复报告

## 修复日期
2026年6月8日

## 告警概览
- **Critical**: 5 个
- **High**: 17 个
- **Medium**: 15 个
- **Low**: 11 个

## 已修复的 Critical 告警

### 1. Apache Tomcat 安全漏洞 (3个 CVE)
**漏洞详情**:
- CVE-2026-43515: 安全约束未正确应用
- CVE-2026-43512: Digest 认证器会认证任何未知用户
- CVE-2026-41293: HTTP/2 请求头未验证

**修复方案**:
- 升级 Tomcat 从 10.1.54 → **10.1.55**
- 通过覆盖 `tomcat.version` 属性实现

**受影响模块**: 
- platform-backend (Spring Boot 内嵌 Tomcat)

### 2. Jackson Core 安全漏洞 (2个 CVE)
**漏洞详情**:
- CVE-2026-xxxxx: 文档长度约束绕过
- CVE-2026-xxxxx: 嵌套深度约束绕过

**修复方案**:
- 升级 tools.jackson.core 从 3.0.1 → **3.1.4**
- 通过 dependencyManagement 覆盖版本

**受影响模块**:
- platform-backend/backend-web (logstash-logback-encoder 依赖)

### 3. Spring Boot 临时目录漏洞
**漏洞详情**:
- Spring Boot 接受可预测的临时目录而不验证所有权

**修复方案**:
- 升级 Spring Boot 从 3.5.13 → **3.5.14**

**受影响模块**:
- platform-backend
- platform-storage
- platform-fisco

## 待处理的 Critical 告警

### Vitest UI Server 任意文件读取 (CVE-2026-47429)
**影响范围**: 仅影响以下场景
- 明确将 Vitest UI 暴露到网络 (--api.host)
- 在 Windows 上运行 Vitest UI 或浏览器模式

**当前状态**:
- 当前版本: vitest 2.1.9
- 修复版本: vitest 4.1.0+
- 最新版本: vitest 4.1.8

**风险评估**:
- 本项目在 macOS 开发环境使用
- 未暴露 Vitest UI 到网络
- **风险等级: 低**

**处理建议**:
- Vitest 4.x 需要 Vite >= 6.0.0 (当前 7.3.2 满足)
- 需要测试 Vitest 4.x 与 Svelte 5 的兼容性
- 建议在单独的 PR 中处理前端升级

## 修改详情

### 文件变更
```
platform-backend/pom.xml:
  - Spring Boot: 3.5.13 → 3.5.14
  - Tomcat: 10.1.54 → 10.1.55
  - 新增 tools.jackson.core 版本覆盖: 3.1.4

platform-storage/pom.xml:
  - Spring Boot: 3.5.13 → 3.5.14

platform-fisco/pom.xml:
  - Spring Boot: 3.5.13 → 3.5.14
```

## High 级别告警

剩余的 High 级别告警主要包括：
- Spring MVC/WebFlux DoS 漏洞 (多个版本)
- PostCSS XSS 漏洞 (前端)
- Spring Security 密码编码器相关 (较低优先级)

这些将在后续 PR 中逐步处理。

## 验证步骤

### 后端验证
```bash
# 验证 Tomcat 版本
cd platform-backend
mvn dependency:tree -Dincludes=org.apache.tomcat.embed:tomcat-embed-core

# 验证 Jackson 版本
mvn dependency:tree -Dincludes=tools.jackson.core:jackson-core

# 运行测试
mvn clean test
```

### 前端验证
```bash
cd platform-frontend
pnpm install
pnpm test
```

## CI 状态
待提交后由 GitHub Actions 自动验证。

## 下一步计划
1. ✅ 提交后端 Critical 安全修复
2. 🔄 处理 High 级别告警
3. 🔄 评估前端 Vitest 升级影响
4. 🔄 处理 Medium/Low 级别告警
5. 🔄 关闭或忽略不适用的告警

## 忽略建议

某些告警可以忽略：
- **开发依赖漏洞**: 如果仅在开发环境使用且未暴露到网络
- **已缓解的漏洞**: 如果项目配置已避免触发条件
- **误报**: 通过 Dependabot 忽略功能标记

## 参考链接
- Apache Tomcat 10.1.55 Release: https://tomcat.apache.org/
- Spring Boot 3.5.14 Release: https://spring.io/blog/2026/04/23/spring-boot-3-5-14-available-now
- Jackson 3.x Security Advisories: https://github.com/FasterXML/jackson-core
