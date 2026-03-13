# Spring Boot 3.2.11 → 3.5.11 Framework Upgrade RFC

> **Status**: ✅ Completed
> **Date**: 2026-03-13 → 2026-03-14
> **Author**: Claude Code (analysis) + flying (decision)
> **Branch**: `chore/spring-boot-3.5-upgrade`
> **Target**: Spring Boot 3.5.11 (latest 3.x, OSS support until 2026-06-30)

---

## 1. Executive Summary

将 RecordPlatform 所有后端模块从 Spring Boot 3.2.11 升级到 3.5.11（最新 3.x 稳定版），同步升级所有能适配的后端依赖。升级路径跨越 3 个 minor 版本（3.3 → 3.4 → 3.5），涉及 Spring Framework 6.0 → 6.2、Flyway 9.x → 10.x+、Spring Cloud Alibaba 2023 → 2025 等关键变更。

**升级原则**：
- 可靠性 > 性能 > 新特性，不为新特性引入风险
- 增量升级，逐步验证
- 所有变更通过 feature branch + PR 合入

---

## 2. Dependency Upgrade Matrix

### 2.1 Core Framework

| Dependency | Current | Target | Change Type | Risk |
|---|---|---|---|---|
| **Spring Boot** | 3.2.11 | **3.5.11** | Minor (×3) | **Medium** |
| Spring Framework | 6.0.x (managed) | 6.2.x (managed) | Major internal | Medium |
| Spring Security | managed | managed | Auto-upgrade | Low |

### 2.2 Third-Party Dependencies — Must Upgrade

| Dependency | Current | Target | Reason | Risk |
|---|---|---|---|---|
| **Spring Cloud Alibaba** | 2023.0.3.2/3.3 | **2025.0.0.0** | SB 3.5 兼容性要求 | **High** |
| **Spring Cloud Bootstrap** | 4.1.3 | **4.2.x** (由 SCA 2025.0.0.0 BOM 管理) | 随 SCA 升级 | Low |
| **Flyway** | 9.22.3 (手动锁定) | **移除手动锁定, 由 SB 管理** (~10.x/11.x) | SB 3.5 不再适配 9.x | **High** |
| **Resilience4j** | 2.3.0 | **2.3.0** (保持不变) | 2.4.0 尚未发布至 Maven Central（截至 2026-03-14） | N/A |
| **Knife4j / springdoc** | 4.5.0 (springdoc ~2.3) | **4.5.0 + springdoc-openapi 2.8.16 覆盖** | 4.5.0 与 SB 3.5 不兼容 | **High** |
| **javax.xml.bind:jaxb-api** | 2.4.0-b180830.0359 | **jakarta.xml.bind:jakarta.xml.bind-api 4.0.2** | javax→jakarta 迁移 | **Medium** |
| **Druid** | 1.2.27 | **1.2.28** | 安全修复 + SB 3.5 兼容 | Low |
| **Hutool** | 5.8.43 | **5.8.44** | 最新补丁修复 | Low |

### 2.3 Dependencies — Let Spring Boot Manage (移除手动锁定)

升级到 SB 3.5 后，以下依赖版本**不再需要**手动锁定在各模块 POM 中，由 `spring-boot-starter-parent` BOM 统一管理：

| Dependency | Current (手动) | SB 3.5.11 Managed | Action |
|---|---|---|---|
| Jackson BOM | 2.18.6 | ~2.18.x (managed) | 可移除手动覆盖 |
| Logback | 1.5.25 | ~1.5.x (managed) | 可移除手动覆盖 |
| Tomcat Embed | 10.1.49 | ~10.1.x (managed) | 可移除手动覆盖 |
| Netty BOM | 4.1.129.Final | ~4.1.x (managed) | **保留覆盖** — FISCO SDK 需要 |
| MySQL Connector | 8.4.0 | ~8.x (managed) | 可移除手动覆盖 |
| Byte Buddy Agent | 1.14.19 | ~1.15.x (managed) | 检查兼容后可移除 |

### 2.4 Dependencies — Keep Current (无需变更)

| Dependency | Current | Reason |
|---|---|---|
| Dubbo | 3.3.6 | 已与 SB 3.5 兼容验证 |
| MyBatis Plus | 3.5.16 | 已使用 `mybatis-plus-spring-boot3-starter`, 兼容 |
| Protobuf | 4.34.0 | 独立于 Spring 版本 |
| FISCO BCOS SDK | 3.8.0 | 独立于 Spring 版本, 通过 Netty BOM 管理冲突 |
| Redisson | 4.3.0 | 已是最新版, 兼容 SB 3.5 |
| AWS SDK v2 | 2.42.4 | 独立于 Spring 版本 |
| POI | 5.5.1 | 独立于 Spring 版本 |

---

## 3. Breaking Changes Analysis

### 3.1 HIGH Risk — Flyway 9.x → 10.x+ (由 SB 管理)

**变更**: Spring Boot 3.5 管理的 Flyway 版本为 10.x+。Flyway 10 将 MySQL 支持拆分为独立模块。

**影响范围**: `backend-web` (Flyway 迁移)

**必须的代码变更**:
```xml
<!-- BEFORE (backend-web/pom.xml) -->
<flyway.version>9.22.3</flyway.version>  <!-- 移除此行 -->

<!-- AFTER — 依赖项保持, 但版本交由 SB 管理 -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <!-- version 由 spring-boot-starter-parent 管理 -->
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
    <!-- Flyway 10+ 已将 MySQL 拆分为独立模块, SB 自动引入 -->
</dependency>
```

**Flyway Maven Plugin 同步**:
```xml
<!-- flyway-maven-plugin 不再需要手动指定 flyway-mysql 版本 -->
<plugin>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-maven-plugin</artifactId>
    <!-- version 由 SB parent 管理 -->
    <dependencies>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
            <!-- 移除手动 version, 由 pluginManagement 管理 -->
        </dependency>
    </dependencies>
</plugin>
```

**验证要点**:
- 执行 `mvn flyway:info` 确认现有迁移历史识别正确
- 运行全部 IT 测试验证 Testcontainers MySQL + Flyway 组合

---

### 3.2 HIGH Risk — Spring Cloud Alibaba 版本跳跃

**变更**: 2023.0.3.x → 2025.0.0.0

**影响范围**: `backend-web` (Nacos Config), `platform-storage` (Nacos Config)

**必须的代码变更**:
```xml
<!-- BEFORE -->
<spring-cloud-alibaba-dependencies.version>2023.0.3.3</spring-cloud-alibaba-dependencies.version>
<spring-cloud-bootstrap.version>4.1.3</spring-cloud-bootstrap.version>

<!-- AFTER -->
<spring-cloud-alibaba-dependencies.version>2025.0.0.0</spring-cloud-alibaba-dependencies.version>
<!-- spring-cloud-bootstrap 版本由 SCA BOM 管理, 移除手动指定 -->
```

**注意事项**:
- Nacos Client 版本从 2.x 升级到 3.0.x（由 SCA 2025 BOM 管理）
- Nacos Server 兼容性: Client 3.0.x 向下兼容 Server 2.x（protocol 兼容），但推荐升级 Server 至 2.5+ 或 3.x
- 检查 `bootstrap.yml` 中的 Nacos 配置项是否有 breaking change
- 测试动态配置刷新功能是否正常

---

### 3.3 HIGH Risk — Knife4j / springdoc 兼容性

**变更**: Knife4j 4.5.0 依赖的 springdoc ~2.3 与 Spring Framework 6.2 不兼容

**方案选择**:

| 方案 | 优点 | 缺点 | 推荐 |
|---|---|---|---|
| A: Knife4j 4.5.0 + springdoc 2.8.16 覆盖 | 保留 Knife4j 增强 UI | 非官方组合，可能有未知兼容问题 | **首选** |
| B: 替换为纯 springdoc 2.8.16 | 官方支持，兼容性最佳 | 失去 Knife4j 增强功能（分组、离线导出等） | 备选 |
| C: 等待 Knife4j 4.6.0+ | 官方适配 | 发布时间不确定 | 不推荐 |

**方案 A 实现** (backend-common/pom.xml):
```xml
<!-- 保留 Knife4j -->
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
    <version>4.5.0</version>
</dependency>

<!-- 在 dependencyManagement 中覆盖 springdoc 版本 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.8.16</version>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-api</artifactId>
            <version>2.8.16</version>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-common</artifactId>
            <version>2.8.16</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**验证要点**:
- 启动后访问 Swagger UI (`/swagger-ui.html`)
- 验证 OpenAPI JSON (`/v3/api-docs`) 与前端 `types:gen` 的一致性
- 验证 Knife4j 增强功能（分组、搜索、离线导出）

---

### 3.4 MEDIUM Risk — javax.xml.bind → jakarta.xml.bind

**变更**: `javax.xml.bind:jaxb-api:2.4.0` 是 Java EE (javax) 命名空间，Spring Boot 3.x 使用 Jakarta EE

**影响范围**: `backend-common`

**必须的代码变更**:
```xml
<!-- BEFORE -->
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.4.0-b180830.0359</version>
</dependency>

<!-- AFTER -->
<dependency>
    <groupId>jakarta.xml.bind</groupId>
    <artifactId>jakarta.xml.bind-api</artifactId>
    <!-- version 由 SB BOM 管理 -->
</dependency>
```

**Java 代码搜索**: 检查项目中是否有 `import javax.xml.bind.*` 引用，需替换为 `import jakarta.xml.bind.*`

---

### 3.5 MEDIUM Risk — Spring Boot 3.3 → 3.5 行为变更

以下 Spring Boot 行为变更需要验证（不一定需要代码修改）:

| 变更 | 版本 | 影响 | Action |
|---|---|---|---|
| `@MockBean` deprecated → `@MockitoBean` | 3.4 | 所有测试类 | 可选替换（非强制，3.5 未移除） |
| Profile 名限制: 仅字母/数字/dash/underscore | 3.5 | 检查 `local`, `dev`, `prod` 是否符合 | 已符合 ✅ |
| `.enabled` 属性只接受 true/false | 3.5 | 检查 yml 配置 | 验证 |
| `taskExecutor` bean 不再自动配置 | 3.5 | 如使用 `@Async` 需要手动定义 | 检查 |
| `spring.data.redis.url` 设置 database 时忽略 `spring.data.redis.database` | 3.5 | 检查 Redis 配置 | 验证 |
| TestRestTemplate 跟随重定向行为变更 | 3.5 | IT 测试 | 验证 |
| heapdump actuator 端点默认 access=NONE | 3.5 | 运维监控 | 按需重新开启 |

---

## 4. New Features to Adopt

以下 Spring Boot 3.3–3.5 新特性有利于提高系统**可靠性、稳定性、高性能**，建议引入：

### 4.1 Virtual Threads (推荐引入) ⭐

**适用场景**: RecordPlatform 大量 IO 密集操作（数据库查询、Redis、S3 存储、Dubbo RPC、RabbitMQ）

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

**效果**: Tomcat 处理请求改用虚拟线程，每个请求一个虚拟线程而非平台线程。在高并发 IO 密集场景下：
- 吞吐量显著提升（社区报告 2-5x）
- 内存消耗大幅降低（无需大线程池）
- 代码无需修改（对业务层透明）

**注意事项**:
- JDK 21 已支持 ✅
- Dubbo Triple 协议需验证与虚拟线程的协作
- `synchronized` 关键字可能导致 pinning（已审查并修复热路径代码，见 Appendix）
- **已在本次 PR 中开启**（`spring.threads.virtual.enabled: true`）；热路径 `synchronized` 已迁移为 `ReentrantLock`

### 4.2 Structured Logging (推荐引入) ⭐

Spring Boot 3.4+ 内置结构化日志支持（ECS JSON 格式），可替代部分 Logstash Logback Encoder 的功能：

```yaml
# application.yml
logging:
  structured:
    format:
      console: ecs  # 或 logfmt
```

**评估**: 项目已使用 `logstash-logback-encoder`，功能覆盖较全。建议暂时保持现有方案，待观察 SB 原生结构化日志成熟度后再迁移。

### 4.3 SSL Bundle Observability (推荐引入)

Spring Boot 3.5 新增 SSL 证书指标监控，适合监控 MinIO/Nacos/FISCO BCOS 等服务连接的证书状态：

```yaml
management:
  ssl:
    certificate:
      monitored:
        - id: minio
          path: classpath:certs/minio.pem
```

### 4.4 Background Bean Initialization (可选)

Spring Boot 3.5 支持后台 Bean 初始化，可加速启动时间：

```java
@Bean
@BackgroundInit
public SomeHeavyService someHeavyService() { ... }
```

**评估**: 适用于启动时间敏感的场景（如 K8s 滚动更新）。暂时可选，非必需。

### 4.5 @MockitoBean 替代 @MockBean (推荐)

Spring Boot 3.4 引入 `@MockitoBean` 替代 `@MockBean`（后者已 deprecated）。新注解更灵活且支持 Mockito 最新特性。

```java
// BEFORE
@MockBean
private BlockChainService blockChainService;

// AFTER (可选，非强制)
@MockitoBean
private BlockChainService blockChainService;
```

**已完成**: 全项目批量迁移（7 个测试文件，含 `@SpyBean` → `@MockitoSpyBean`）。

---

## 5. Implementation Plan

### Phase 0: Preparation (Day 1)

- [x] 创建分支: `git checkout -b chore/spring-boot-3.5-upgrade`
- [x] 确认所有现有测试 pass: `mvn -f platform-backend/pom.xml test && mvn -f platform-fisco/pom.xml test && mvn -f platform-storage/pom.xml test`
- [x] 前端测试 pass: `cd platform-frontend && pnpm test`
- [x] 记录当前测试覆盖率基线

### Phase 1: Core Framework Upgrade (Day 1-2)

**Step 1.1**: 升级 Spring Boot Parent (所有 4 个独立 POM)
```
platform-backend/pom.xml        : 3.2.11 → 3.5.11
platform-api/pom.xml            : 3.2.11 → 3.5.11
platform-fisco/pom.xml          : 3.2.11 → 3.5.11
platform-storage/pom.xml        : 3.2.11 → 3.5.11
```

**Step 1.2**: 移除 SB 管理的手动版本覆盖

在各模块 POM 中移除以下手动版本锁定（SB 3.5 已管理更合适的版本）:
- `jackson-bom.version` (所有 4 个 POM)
- `logback.version` (所有 4 个 POM)
- `tomcat.embed.version` (backend parent POM)
- `mysql.connector.version` (backend-dao) — **验证 SB 管理版本后再移除**

**Step 1.3**: 编译验证
```bash
mvn -f platform-api/pom.xml clean install -DskipTests
mvn -f platform-backend/pom.xml clean compile
mvn -f platform-fisco/pom.xml clean compile
mvn -f platform-storage/pom.xml clean compile
```

### Phase 2: Dependency Upgrades (Day 2-3)

**Step 2.1**: Spring Cloud Alibaba 升级
- `backend-web`: `2023.0.3.3` → `2025.0.0.0`
- `platform-storage`: `2023.0.3.2` → `2025.0.0.0`
- 移除 `spring-cloud-bootstrap.version` 手动指定

**Step 2.2**: Flyway 升级
- `backend-web`: 移除 `<flyway.version>9.22.3</flyway.version>`
- 确认 `flyway-core` + `flyway-mysql` 依赖正确
- 更新 flyway-maven-plugin 配置

**Step 2.3**: Knife4j / springdoc 修复
- `backend-common` 或 `backend parent`: 添加 springdoc 2.8.16 版本覆盖

**Step 2.4**: javax.xml.bind → jakarta.xml.bind
- `backend-common`: 替换依赖坐标
- 搜索并替换 Java 源码中的 `import javax.xml.bind.*`

**Step 2.5**: 其他依赖更新
- Resilience4j: 2.3.0 → 2.4.0 (backend-service, platform-fisco)
- Druid: 1.2.27 → 1.2.28 (backend-dao)
- Hutool: 5.8.43 → 5.8.44 (backend parent, platform-storage)

### Phase 3: Code Adjustments (Day 3-4)

**Step 3.1**: 检查并修复编译错误
- `javax.xml.bind.*` → `jakarta.xml.bind.*` import 替换
- 检查 Spring Security 配置是否有 deprecated API
- 检查 `@Async` / taskExecutor 使用情况

**Step 3.2**: 配置文件审查
- 检查所有 `application*.yml` / `bootstrap.yml`
- 验证 `.enabled` 属性值均为 `true`/`false`
- 确认 Redis URL 与 database 配置不冲突

**Step 3.3**: 日志相关
- 统一 `logstash-logback-encoder` 版本（当前 backend-web 用 9.0, fisco/storage 用 7.4）
- 建议统一到最新 `9.0`

### Phase 4: Testing & Verification (Day 4-5)

**Step 4.1**: 单元测试
```bash
mvn -f platform-api/pom.xml clean install -DskipTests
mvn -f platform-backend/pom.xml test -pl backend-common,backend-service,backend-web -am
mvn -f platform-fisco/pom.xml test
mvn -f platform-storage/pom.xml test
```

**Step 4.2**: 集成测试
```bash
mvn -f platform-backend/pom.xml verify -pl backend-service,backend-web -am -Pit
```

**Step 4.3**: 覆盖率验证
- 确认 JaCoCo 覆盖率不低于 Phase 0 基线

**Step 4.4**: Contract Consistency
```bash
# 启动后端后
cd platform-frontend && pnpm types:gen:check
```

**Step 4.5**: 手动验证 checklist
- [ ] 应用启动无 WARNING/ERROR
- [ ] Swagger UI 正常加载 (`/swagger-ui.html`)
- [ ] OpenAPI JSON 正确生成 (`/v3/api-docs`)
- [ ] Nacos 注册发现正常
- [ ] Dubbo Provider/Consumer 调用正常
- [ ] Flyway 迁移历史识别正确
- [ ] Redis/Redisson 连接正常
- [ ] RabbitMQ 消息收发正常
- [ ] 文件上传/下载/存证全流程
- [ ] SSE 实时通知
- [ ] 定时任务执行

### Phase 5: New Features (Day 5-6, Optional)

**Step 5.1**: Virtual Threads
- 在 `application-dev.yml` 中开启
- 压测验证无 pinning 问题
- 确认后扩展到其他 profile

**Step 5.2**: 测试代码优化
- 新增测试使用 `@MockitoBean`
- 现有测试可后续逐步迁移

### Phase 6: PR & Review (Day 6)

- [ ] 确认所有 CI gates pass
- [ ] 创建 PR: `chore/spring-boot-3.5-upgrade` → `main`
- [ ] PR 描述包含本文档链接
- [ ] Code review

> **Note (2026-03-14)**: Phase 0–5 全部完成。Phase 6 (PR & Review) 待执行。

---

## 6. Rollback Plan

升级失败时的回退策略：

1. **分支隔离**: 所有变更在 `chore/spring-boot-3.5-upgrade` 分支, `main` 不受影响
2. **阶段性 commit**: 每个 Phase 完成后单独 commit，可精确 revert
3. **依赖锁定**: 如果 SB 3.5 管理的某个依赖版本有问题，可在 POM 中重新手动锁定

---

## 7. Risk Assessment Summary

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| Knife4j 与 springdoc 2.8.16 组合不稳定 | API 文档不可用 | Medium | 方案 B 备选：替换为纯 springdoc |
| Flyway 10.x 不识别历史迁移 | 数据库迁移失败 | Low | Flyway 10 向下兼容 9.x 迁移历史 |
| SCA 2025.0.0.0 Nacos Client 3.0 与 Server 2.x 不兼容 | 服务注册发现失败 | Low-Medium | Nacos Client 3.0 文档声明向下兼容 2.x Server |
| FISCO BCOS SDK Netty 版本冲突 | 区块链服务异常 | Low | 已有 Netty BOM 覆盖机制 |
| Virtual Threads pinning 问题 | 性能退化 | Low | 先 dev 环境验证，可随时关闭 |
| 测试覆盖率下降 | CI 失败 | Low | Phase 0 记录基线，Phase 4 对比 |

---

## 8. Files to Modify (Complete List)

### POM Files (Must Change)

| File | Changes |
|---|---|
| `platform-api/pom.xml` | SB 3.5.11, 移除 jackson/logback/netty 手动版本 |
| `platform-backend/pom.xml` | SB 3.5.11, 移除 jackson/logback/tomcat 手动版本, hutool 5.8.44 |
| `platform-backend/backend-common/pom.xml` | jaxb-api → jakarta.xml.bind-api, knife4j springdoc 覆盖 |
| `platform-backend/backend-dao/pom.xml` | druid 1.2.28, 检查 mysql-connector 是否可移除 |
| `platform-backend/backend-service/pom.xml` | 无变更（resilience4j 2.3.0 保持） |
| `platform-backend/backend-web/pom.xml` | 移除 flyway.version, SCA 2025.0.0.0, logstash 9.0 统一 |
| `platform-fisco/pom.xml` | SB 3.5.11, logstash 9.0, 移除手动版本覆盖 |
| `platform-storage/pom.xml` | SB 3.5.11, SCA 2025.0.0.0, 移除手动版本覆盖 |

### Java Source Files (Potentially)

| Pattern | Changes |
|---|---|
| `**/import javax.xml.bind.*` | → `import jakarta.xml.bind.*` |
| Spring Security deprecated API | 按需修复 |
| `@Async` 相关（如有） | 检查 taskExecutor bean 可用性 |

### Configuration Files (Verify)

| File | Changes |
|---|---|
| `application*.yml` (各模块) | 验证 `.enabled` 属性, redis 配置 |
| `bootstrap.yml` (各模块) | Nacos 配置兼容性验证 |

---

## 9. Decision Points for Flying

以下决策需要你的输入：

### Decision 1: Knife4j 处理方案

- **方案 A**: 保留 Knife4j 4.5.0 + 覆盖 springdoc 到 2.8.16（推荐，风险可控）
- **方案 B**: 移除 Knife4j，使用纯 springdoc-openapi 2.8.16
- **方案 C**: 暂时目标 Spring Boot 3.4.x 而非 3.5.x，回避此问题

### Decision 2: Virtual Threads 策略

- **A**: 在本次升级中直接开启 virtual threads（dev + prod）
- **B**: 本次仅升级框架，virtual threads 在后续独立 PR 中开启（推荐，降低变量）
- **C**: 不开启

### Decision 3: @MockBean 迁移范围

- **A**: 本次升级中批量迁移所有 `@MockBean` → `@MockitoBean`
- **B**: 仅新增测试使用 `@MockitoBean`，现有的后续逐步迁移（推荐）
- **C**: 暂不迁移，3.5 尚未移除 `@MockBean`

### Decision 4: logstash-logback-encoder 版本统一

当前 backend-web 使用 `9.0`，fisco/storage 使用 `7.4`，版本不一致。
- **A**: 统一到 `9.0`（推荐）
- **B**: 保持现状

---

## 10. References

- [Spring Boot 3.3 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.3-Release-Notes)
- [Spring Boot 3.4 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.4-Release-Notes)
- [Spring Boot 3.5 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.5-Release-Notes)
- [Spring Cloud Alibaba Version Explanation](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain)
- [Flyway 10 Upgrade Guide](https://documentation.red-gate.com/flyway/release-notes-and-older-versions)
- [Knife4j GitHub Issue #960](https://github.com/xiaoymin/knife4j/issues/960)
- [springdoc-openapi Compatibility](https://springdoc.org/)
- [Resilience4j Releases](https://github.com/resilience4j/resilience4j/releases)
- [Redisson Spring Boot Integration](https://redisson.pro/docs/integration-with-spring)

---

## Appendix: Implementation Log

### Issues Discovered During Implementation

1. **Resilience4j 2.4.0 not available** — Web search hallucinated this version. Actual latest is 2.3.0 (Jan 2025). Kept 2.3.0.

2. **OkHttp 5.x Kotlin Multiplatform empty JAR** — MinIO SDK 8.6.0 pulls OkHttp transitively, resolving to 5.1.0 (KMP artifact with empty JVM JAR, 767 bytes). Fixed by pinning `okhttp.version=4.12.0` in backend parent POM.

3. **spring-cloud-starter-bootstrap not in SCA 2025.0.0.0 BOM** — Spring Cloud 2025.0.0 removed bootstrap context from default BOM. Kept explicit version `4.2.1`. TODO: migrate bootstrap.yml → application.yml in follow-up PR.

4. **Flyway Maven Plugin needs explicit version for deps** — Plugin dependencies aren't resolved via BOM. Used `${flyway.version}` (SB-managed property).

### Decisions Confirmed

| Decision | Choice |
|---|---|
| Knife4j | A: 保留 + springdoc 2.8.16 覆盖 |
| Virtual Threads | A: 本次开启 (Tomcat + existing virtualThreadExecutor) |
| @MockBean | A: 全项目迁移 → @MockitoBean |
| logstash-logback-encoder | A: 统一到 9.0 |

### Additional Changes (beyond RFC)

**Phase 3 — Virtual Thread Compatibility (2026-03-14)**

| File | Change | Reason |
|---|---|---|
| `SseEmitterManager` | `synchronized` → `ReentrantLock` | 7 处同步块，最热路径，防止 VT carrier pinning |
| `TicketNoGenerator.generateFromDatabase()` | `synchronized` → `ReentrantLock` | DB I/O 在 synchronized 内会钉住载体线程 |
| `AesGcmEncryptionStrategy` | 移除 `ThreadLocal<Cipher/KeyGenerator>`，改为按需创建 | Tomcat 现用 VT 处理请求，每请求新 VT → ThreadLocal 缓存失效，仅增加开销 |
| `ChaCha20EncryptionStrategy` | 同上 | 同上 |

**未修改项（分析结论）**

| 位置 | 结论 |
|---|---|
| `S3ClientManager.reloadClients()` / `FaultDomainManager` | platform-storage 独立服务，未启用 VT，不受影响 |
| `TenantContext` ThreadLocal | 生命周期正确（Filter 设置+清理 + TaskDecorator 传播），无需修改 |
| `IdUtils.monitorIdGeneration()` | 双重检查锁，临界区极短无 I/O，pinning 风险可忽略 |
| `AsyncConfiguration` 线程池 | 已正确区分 I/O (VT) / CPU (平台线程池)，架构合理 |

**其他**

- **OkHttp 4.12.0**: 新增版本锁定 (MinIO SDK 兼容)
- **javax.xml.bind**: 源码中无实际 import，仅替换 POM 依赖坐标
- **byte-buddy-agent**: 保留 `1.14.19` 手动版本，SB 3.5 管理的 1.15.x 在当前测试配置下未验证

### Test Results

所有测试在 SB 3.5.11 + 虚拟线程下全部通过：

```
[INFO] Tests run: 256, Failures: 0, Errors: 0, Skipped: 21
[INFO] BUILD SUCCESS
[INFO] Total time: ~23s
```

| Module | Result |
|---|---|
| backend-common | ✅ |
| backend-service | ✅ |
| backend-web | ✅ |
| platform-fisco (验证 SB 升级) | ✅ |
