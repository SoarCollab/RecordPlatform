# RecordPlatform

基于 FISCO BCOS 区块链和 MinIO 分布式存储的企业级文件存证平台。通过区块链技术保证文件元数据不可篡改，结合分布式存储实现高可用文件管理。

## 核心特性

- **区块链存证**：文件元数据上链（FISCO BCOS），保证不可篡改和可追溯
- **分布式存储**：MinIO 双副本冗余存储，任一副本可读
- **分布式事务**：Saga + Outbox 模式保证跨服务数据一致性
- **弹性容错**：Resilience4j 熔断、重试、限流
- **安全机制**：JWT 认证、ID 混淆（UUID+Redis 映射）、CORS 白名单、登录限流
- **分片上传**：支持大文件断点续传，AES-GCM 加密存储
- **文件分享**：生成带访问次数限制的分享码
- **审计日志**：完整的操作日志和审计追踪

## 系统架构

```
                           基础设施
    ┌────────┐  ┌───────┐  ┌──────────┐  ┌───────┐  ┌────────────┐
    │ Nacos  │  │ MySQL │  │ RabbitMQ │  │ Redis │  │ MinIO 集群  │
    │ :8848  │  │ :3306 │  │  :5672   │  │ :6379 │  │   :9000    │
    └────┬───┘  └───┬───┘  └────┬─────┘  └───┬───┘  └─────┬──────┘
         │          │           │            │            │
         └──────────┴───────────┴────────────┴────────────┘
                                │
    ┌───────────────────────────┴───────────────────────────┐
    │                    platform-api                       │
    │            (共享 Dubbo 接口定义 JAR)                    │
    │   BlockChainService, DistributedStorageService        │
    └───────────────────────────┬───────────────────────────┘
                                │ implements
         ┌──────────────────────┼──────────────────────┐
         │                      │                      │
         ▼                      │                      ▼
┌─────────────────┐             │             ┌─────────────────┐
│ platform-fisco  │             │             │  platform-minio │
│ (Dubbo Provider)│             │             │ (Dubbo Provider)│
│ 区块链存证服务    │             │             │ 分布式存储服务     │
│ Port 8091       │             │             │ Port 8092       │
└────────┬────────┘             │             └────────┬────────┘
         │                      │                      │
         │       Dubbo RPC      ▼       Dubbo RPC      │
         │             ┌─────────────────┐             │
         └────────────►│ platform-backend│◄────────────┘
                       │ (Dubbo Consumer)│
                       │ REST API :8000  │
                       │ Saga 事务编排    │
                       └────────┬────────┘
                                │
                       ┌────────▼────────┐
                       │ FISCO BCOS Node │
                       │ Peer :20200     │
                       └─────────────────┘
```

## 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言/框架 | Java, Spring Boot | 21, 3.2.11 |
| 微服务 | Apache Dubbo (Triple 协议), Nacos | 3.3.3 |
| 区块链 | FISCO BCOS, Solidity | 3.8.0, ^0.8.11 |
| 存储 | MinIO, MySQL, Redis | 8.5.9, 8.0+, 6.0+ |
| ORM | MyBatis Plus, Druid | 3.5.9, 1.2.23 |
| 消息队列 | RabbitMQ | 3.8+ |
| 弹性设计 | Resilience4j | 2.2.0 |
| 分布式锁 | Redisson | 3.35.0 |
| 数据库迁移 | Flyway | 9.22.3 |
| 监控 | Micrometer, Prometheus, SkyWalking | - |
| API 文档 | Knife4j/OpenAPI 3 | 4.4.0 |

## 快速开始

### 前置条件

启动以下基础设施服务：

| 服务 | 端口 | 用途 |
|------|------|------|
| Nacos | 8848 | 服务发现和配置中心 |
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存和分布式锁 |
| RabbitMQ | 5672 | 消息队列 |
| MinIO | 9000 | 对象存储（建议多节点） |
| FISCO BCOS | 20200 | 区块链节点 |

### 环境配置

```bash
# 复制环境变量模板
cp .env.example .env

# 编辑 .env 文件，填入实际配置值
vim .env
```

### 构建项目

项目无根聚合器 POM，需分模块构建：

```bash
# 1. 安装共享接口（其他模块依赖）
mvn -f platform-api/pom.xml clean install

# 2. 构建后端模块
mvn -f platform-backend/pom.xml clean package -DskipTests

# 3. 构建 FISCO 服务
mvn -f platform-fisco/pom.xml clean package -DskipTests

# 4. 构建 MinIO 服务
mvn -f platform-minio/pom.xml clean package -DskipTests
```

### 启动服务

#### 方式一：使用启动脚本（推荐）

```bash
# 启动所有服务（自动加载 .env 环境变量）
./scripts/start.sh all

# 单独启动某个服务
./scripts/start.sh backend
./scripts/start.sh fisco
./scripts/start.sh minio
```

#### 方式二：手动启动

按依赖顺序启动（Provider 先于 Consumer）：

```bash
# 加载环境变量
set -a && source .env && set +a

# 1. MinIO 服务 (Dubbo Provider, 端口 8092)
java -jar platform-minio/target/platform-minio-0.0.1-SNAPSHOT.jar \
     --spring.profiles.active=local

# 2. FISCO 服务 (Dubbo Provider, 端口 8091)
java -jar platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar \
     --spring.profiles.active=local

# 3. 后端 Web 服务 (Dubbo Consumer, REST API 端口 8000)
java -jar platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar \
     --spring.profiles.active=local
```

配置文件 Profile：`local`、`dev`、`prod`

### 验证安装

| 端点 | 地址 |
|------|------|
| Swagger UI | http://localhost:8000/record-platform/swagger-ui.html |
| Knife4j | http://localhost:8000/record-platform/doc.html |
| 健康检查 | http://localhost:8000/record-platform/actuator/health |
| Prometheus 指标 | http://localhost:8000/record-platform/actuator/prometheus |
| Druid 监控 | http://localhost:8000/record-platform/druid/ (admin/123456) |

> 注意：生产环境下 Swagger 和 Druid 监控页面已禁用

## 项目结构

```
RecordPlatform/
├── platform-api/                 # 共享 Dubbo 接口定义
│   └── src/.../external/         # BlockChainService, DistributedStorageService
│
├── platform-backend/             # 后端主服务 (Dubbo Consumer)
│   ├── backend-web/              # REST 控制器、过滤器、Spring Boot 入口
│   │   └── src/.../resources/
│   │       └── db/migration/     # Flyway 数据库迁移脚本
│   ├── backend-service/          # 业务逻辑、Saga 编排、Dubbo 调用
│   ├── backend-dao/              # MyBatis Plus Mapper、实体、VO
│   ├── backend-common/           # 工具类、常量、JWT、分布式锁
│   └── db/schema/                # 数据库 Schema 参考文档
│
├── platform-fisco/               # 区块链服务 (Dubbo Provider)
│   ├── contract/                 # Solidity 智能合约
│   └── src/.../service/          # 区块链交互实现
│
├── platform-minio/               # 存储服务 (Dubbo Provider)
│   └── src/.../service/          # MinIO 客户端管理、双副本写入
│
└── scripts/                      # 启动脚本
    ├── start.sh                  # 通用启动脚本（自动加载 .env）
    └── start-with-skywalking.sh  # SkyWalking APM 启动脚本
```

## 数据库迁移

项目使用 Flyway 管理数据库迁移，脚本位于 `platform-backend/backend-web/src/main/resources/db/migration/`：

| 脚本 | 说明 |
|------|------|
| V1.0.0__core_entity.sql | 核心实体表 (account, file, image_store) |
| V1.0.1__distributed_transaction.sql | Saga 事务 + Outbox 消息表 |
| V1.0.2__operation_log.sql | 操作日志表 |
| V1.0.3__audit.sql | 审计日志表 |
| V1.0.4__multi_tenant.sql | 多租户支持 |

启动时 Flyway 会自动执行迁移，也可手动创建数据库：

```sql
CREATE DATABASE RecordPlatform CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

## 环境变量

完整环境变量列表参见 `.env.example`，主要配置项：

| 分类 | 变量 | 说明 |
|------|------|------|
| **数据库** | `DB_HOST`, `DB_PORT`, `DB_NAME` | MySQL 连接 |
| | `DB_USERNAME`, `DB_PASSWORD` | MySQL 凭证 |
| **Redis** | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis 连接 |
| **Nacos** | `NACOS_HOST`, `NACOS_USERNAME`, `NACOS_PASSWORD` | 配置中心 |
| **MinIO** | `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | 对象存储 |
| **RabbitMQ** | `RABBITMQ_ADDRESSES`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | 消息队列 |
| **安全** | `JWT_KEY` | JWT 签名密钥 (≥32字符) |
| | `ID_SECURITY_KEY` | ID 混淆密钥 |
| **邮件** | `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP 配置 |
| **CORS** | `CORS_ALLOWED_ORIGINS` | 允许的前端域名 |

## 监控端点

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 服务健康状态 (含 DB/Redis/RabbitMQ/MinIO/Saga/Outbox) |
| `/actuator/prometheus` | Prometheus 指标 |
| `/actuator/metrics` | Micrometer 指标 |
| `/actuator/circuitbreakers` | Resilience4j 熔断器状态 |
| `/actuator/retries` | Resilience4j 重试状态 |

## API 概览

### 认证 `/api/v1/auth`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ask-code` | 获取验证码 |
| POST | `/register` | 用户注册 |
| POST | `/reset-confirm` | 重置密码确认 |
| POST | `/reset-password` | 重置密码 |

### 用户 `/api/v1/users`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/info` | 获取用户信息 |
| POST | `/modify-email` | 修改邮箱 |
| POST | `/change-password` | 修改密码 |

### 文件管理 `/api/v1/files`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 获取文件列表 |
| GET | `/page` | 分页获取文件 |
| GET | `/address` | 获取文件区块链地址 |
| GET | `/getTransaction` | 获取交易信息 |
| GET | `/download` | 下载文件 |
| DELETE | `/deleteByHash` | 按哈希删除 |
| DELETE | `/deleteById` | 按 ID 删除 |
| POST | `/share` | 分享文件 |
| GET | `/getSharingFiles` | 获取分享文件 |
| POST | `/saveShareFile` | 保存分享文件 |

### 文件上传 `/api/v1/files/upload`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload/start` | 开始上传会话 |
| POST | `/upload/chunk` | 上传文件分片 |
| POST | `/upload/complete` | 完成上传 |
| POST | `/upload/pause` | 暂停上传 |
| POST | `/upload/resume` | 恢复上传 |
| POST | `/upload/cancel` | 取消上传 |

### 图片 `/api/v1/images`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload/avatar` | 上传头像 |
| POST | `/upload/image` | 上传图片 |
| GET | `/download/images/**` | 下载图片 |

### 审计 `/api/v1/system/audit`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/overview` | 审计概览 |
| GET | `/logs/page` | 分页查询审计日志 |
| GET | `/logs/{id}` | 获取审计日志详情 |
| POST | `/logs/export` | 导出审计日志 |
| GET | `/high-frequency` | 高频操作统计 |
| POST | `/sensitive/page` | 敏感操作查询 |
| GET | `/error-stats` | 错误统计 |
| GET | `/time-distribution` | 时间分布 |
| GET | `/configs` | 审计配置 |
| GET | `/check-anomalies` | 异常检测 |
| POST | `/backup-logs` | 备份日志 |

### 操作日志 `/api/v1/system/logs`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/page` | 分页查询操作日志 |
| GET | `/{id}` | 获取日志详情 |
| GET | `/export` | 导出日志 |

详细 API 文档请访问 Swagger UI。

## 智能合约

| 合约 | 路径 | 功能 |
|------|------|------|
| Storage.sol | `platform-fisco/contract/` | 文件元数据存储、查询、删除 |
| Sharing.sol | `platform-fisco/contract/` | 文件分享、访问控制 |

## 配置说明

### Resilience4j 弹性配置

```yaml
resilience4j:
  circuitbreaker:
    instances:
      blockChainService:
        sliding-window-size: 50
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      storageService:
        slow-call-duration-threshold: 8s
```

### Saga 补偿配置

```yaml
saga:
  compensation:
    max-retries: 5
    batch-size: 50
    poll-interval-ms: 30000
```

### Outbox 发布配置

```yaml
outbox:
  publisher:
    batch-size: 100
    max-retries: 5
    poll-interval-ms: 2000
```

## 许可证

Apache License 2.0
