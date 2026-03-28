<div align="center">

# RecordPlatform

**基于区块链和分布式存储的企业级文件存证平台**

[![Build](https://github.com/SoarCollab/RecordPlatform/actions/workflows/test.yml/badge.svg)](https://github.com/SoarCollab/RecordPlatform/actions/workflows/test.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Svelte](https://img.shields.io/badge/Svelte-5-FF3E00?logo=svelte&logoColor=white)](https://svelte.dev)

[English](README.md) · [在线文档](https://soarcollab.github.io/RecordPlatform/) · [API 参考](docs/zh/api/index.md)

</div>

---

## 什么是 RecordPlatform？

RecordPlatform 是一个开源的企业级文件存证平台，将**区块链不可篡改性**与**故障域感知的分布式存储**相结合。上传文件，将元数据通过 FISCO BCOS 链上存证，并以安全方式分享 — 具备来源可验证、完整性可证明、访问全程可审计的能力。

专为以下需求而构建：
- 📜 **可审计的溯源链** — 每次上传、分享、下载均可追踪并链上可验证
- 🏢 **多租户隔离** — 按租户隔离存储、缓存和数据路径
- 🔒 **端到端加密** — AES-GCM/ChaCha20-Poly1305，每个分片独立密钥链

---

## ✨ 核心特性

<table>
<tr>
<td width="50%" valign="top">

### 🔐 存证与安全
- **区块链存证** — 文件元数据上链至 FISCO BCOS，不可篡改可追溯
- **文件加密** — AES-GCM / ChaCha20-Poly1305，分片独立密钥链
- **RBAC + 归属校验** — 细粒度资源级权限控制
- **ID 混淆** — AES-256-CTR 外部↔内部 ID 映射

</td>
<td width="50%" valign="top">

### 📦 存储与传输
- **分布式存储** — 1~N 活跃故障域，仲裁写入，N-1 容错，备用域自动提升
- **分片上传** — 断点续传、并发上传、动态分片大小
- **流式下载** — 大文件使用 StreamSaver.js，自动策略选择
- **文件版本链** — 追踪文件历史，从已有文件派生新版本

</td>
</tr>
<tr>
<td width="50%" valign="top">

### 👥 协作与分享
- **文件分享** — 生成带访问次数、有效期、密码保护的分享码
- **分享审计与溯源** — 多级溯源链（A→B→C），完整访问日志
- **好友系统** — 好友间直接分享，SSE 实时通知
- **工单系统** — 内置工单系统，支持分类、优先级、管理员处理

</td>
<td width="50%" valign="top">

### 📊 治理与可观测性
- **配额治理** — 用户/租户级存储限额，SHADOW/ENFORCE 两种模式，灰度发布
- **实时通知** — SSE 推送存证结果、工单动态、好友事件、系统公告
- **存储容量 API** — 集群/域/节点容量聚合，含 `degraded`+`source` 语义
- **多租户隔离** — 数据库、缓存、存储路径按租户独立隔离
- **存储完整性校验** — 定时验证 S3 文件与区块链记录的一致性
- **分布式追踪** — OpenTelemetry 自动注入，Jaeger 可视化
- **SLO/SLI 可观测性** — Prometheus 预计算规则、燃尽率告警、Grafana SLO 仪表盘

</td>
</tr>
</table>

---

## 🏗 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                          基础设施层                               │
│  Nacos  MySQL  RabbitMQ  Redis  S3 存储集群  OTel  Jaeger        │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │         platform-api          │
              │      (共享 Dubbo 接口)         │
              └───────────────┬───────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
         ▼                    │                    ▼
┌─────────────────┐           │           ┌─────────────────┐
│ platform-fisco  │           │           │ platform-storage│
│  区块链服务      │           │           │   存储服务       │
│ (端口 8091)      │           │           │ (端口 8092)     │
└────────┬────────┘           │           └────────┬────────┘
         │         Dubbo RPC  │  Dubbo RPC         │
         │                    ▼                    │
         │          ┌─────────────────┐            │
         │          │ platform-backend│            │
         │          │ REST API :8000  │            │
         │          └─────────────────┘            │
         │                                         │
         ▼                                         ▼
  ┌─────────────┐                        ┌────────────────┐
  │ FISCO BCOS  │                        │   S3 集群      │
  │    节点      │                        │ (MinIO / S3)  │
  └─────────────┘                        └────────────────┘
```

> 含 Mermaid 流程图和数据流时序图的完整架构文档请参阅 [架构设计文档](docs/zh/architecture/index.md)

---

## ⚡ 快速开始

### 1. 前置依赖

启动前确保以下服务已运行：

| 服务          | 端口  | 用途               |
| ------------- | ----- | ------------------ |
| Nacos         | 8848  | 服务发现与配置中心 |
| MySQL         | 3306  | 数据库             |
| Redis         | 6379  | 缓存与分布式锁     |
| RabbitMQ      | 5672  | 消息队列           |
| S3 兼容存储   | 9000  | 对象存储           |
| FISCO BCOS    | 20200 | 区块链节点         |
| OTel Collector        | 4317  | 追踪与指标管道             |
| Jaeger                | 16686 | 追踪可视化 UI              |

通过 Docker Compose 启动基础设施：

```bash
docker compose -f docker-compose.infra.yml up -d
```

复制 `.env.example` 为 `.env`，配置 `JWT_KEY`、`S3_*` 和 `FISCO_*` 后再启动服务。

### 2. 构建

```bash
# 安装共享接口（必须首先执行）
mvn -f platform-api/pom.xml clean install

# 构建所有后端模块
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 3. 启动

```bash
# 按顺序启动（Provider 先于 Consumer）
java -jar "$(ls platform-storage/target/platform-storage-*.jar)" --spring.profiles.active=local
java -jar "$(ls platform-fisco/target/platform-fisco-*.jar)" --spring.profiles.active=local
java -jar "$(ls platform-backend/backend-web/target/backend-web-*.jar)" --spring.profiles.active=local

# 启动前端开发服务
cd platform-frontend && pnpm install && pnpm dev
```

或使用统一启动脚本：

```bash
./scripts/start.sh start all
```

验证安装：
- **Swagger UI**：http://localhost:8000/record-platform/swagger-ui.html
- **健康检查**：http://localhost:8000/record-platform/actuator/health
- **前端**：http://localhost:5173

> **注意：** `/record-platform` 上下文路径仅在 `prod` profile 下生效。使用 `--spring.profiles.active=local`（如上所示）时，URL 为 `http://localhost:8080/swagger-ui.html` 和 `http://localhost:8080/actuator/health`（端口和上下文路径取决于 profile 和 `SERVER_PORT` 配置）。

> 详细配置请参阅 [快速开始指南](docs/zh/getting-started/index.md)

---

## 🧱 技术栈

| 分类     | 技术                                                    | 版本               |
| -------- | ------------------------------------------------------- | ------------------ |
| 后端     | Java + Spring Boot + Virtual Threads                    | 21, 3.5.11         |
| 微服务   | Apache Dubbo (Triple 协议), Nacos                       | 3.3.6              |
| 区块链   | FISCO BCOS, Solidity                                    | 3.8.0, ^0.8.11     |
| 存储     | S3 兼容存储 (AWS SDK v2), MySQL, Redis (Redisson)        | 2.x, 8.0+, 7.0+    |
| 前端     | Svelte 5 (Runes), SvelteKit, Tailwind CSS 4, bits-ui   | 5.53+, 2.53+, 4.2+ |
| 弹性设计 | Resilience4j（熔断、重试）                               | 2.4.0              |
| 监控     | Micrometer, Prometheus, OpenTelemetry, Jaeger           | —                  |
| 测试     | JUnit 5, Mockito, Testcontainers, Vitest                | —                  |

---

## 📚 文档导航

| 指南 | 说明 |
| ---- | ---- |
| [快速开始](docs/zh/getting-started/index.md) | 前置依赖、安装部署、配置说明 |
| [架构设计](docs/zh/architecture/index.md) | 系统架构、分布式存储、区块链、安全机制 |
| [部署运维](docs/zh/deployment/index.md) | Docker Compose、生产环境、监控告警 |
| [API 参考](docs/zh/api/index.md) | REST 端点、认证规则、错误码 |
| [开发指南](docs/zh/development/index.md) | 贡献指南、本地开发、测试策略 |
| [故障排查](docs/zh/troubleshooting/index.md) | 常见问题与解决方案 |

---

## 🗂 项目结构

```
RecordPlatform/
├── platform-api/          # 共享 Dubbo 接口定义
├── platform-backend/      # REST API 服务（Dubbo Consumer, :8000）
│   ├── backend-web/       # 控制器、JWT 过滤器、限流
│   ├── backend-service/   # 业务逻辑、Saga 编排、Outbox
│   ├── backend-dao/       # MyBatis Plus 映射与实体
│   ├── backend-api/       # 内部接口定义
│   └── backend-common/    # 共享工具类与常量
├── platform-fisco/        # 区块链服务（Dubbo Provider, :8091）
├── platform-storage/      # 存储服务（Dubbo Provider, :8092）
├── platform-frontend/     # Svelte 5 + SvelteKit 前端
├── scripts/               # 启停脚本、环境预检
├── tools/                 # k6 压测、安全 PoC、文档一致性校验
├── docs/                  # VitePress 文档站（en/zh）
└── docker-compose.infra.yml  # 基础设施服务（Nacos/MySQL/Redis/RabbitMQ/MinIO）
```

---

## 🛠 参与贡献

欢迎贡献！请在开始前阅读 [贡献指南](docs/zh/development/contributing.md)。

```bash
# 1. Fork 并克隆仓库
git clone https://github.com/<your-fork>/RecordPlatform.git

# 2. 创建功能分支
git checkout -b feat/your-feature

# 3. 修改代码，运行测试
mvn -f platform-backend/pom.xml test

# 4. 向 main 分支发起 Pull Request
```

**分支命名规范：** `feat/`、`fix/`、`refactor/`、`docs/`、`chore/`

所有 PR 必须通过 CI 门禁：后端测试、前端测试、契约一致性检查、构建验证。详见 [CI 门禁](docs/zh/development/contributing.md#ci-门禁)。

---

## 📄 许可证

本项目基于 Apache License 2.0 开源 — 详见 [LICENSE](LICENSE) 文件。
