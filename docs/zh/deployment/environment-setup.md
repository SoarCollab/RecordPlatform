# 环境搭建

从零搭建 RecordPlatform 完整运行环境。

## 前置条件

### 主机要求

| 资源 | 最低配置 | 推荐配置 |
|------|----------|----------|
| CPU | 4 核 | 8 核 |
| 内存 | 8 GB | 16 GB |
| 磁盘 | 40 GB | 100 GB SSD |
| 操作系统 | Ubuntu 20.04+ / CentOS 8+ / macOS 12+ | Ubuntu 22.04 LTS |

### 软件要求

| 软件 | 版本 | 用途 |
|------|------|------|
| Docker | 20.10+ | 基础设施容器化 |
| Docker Compose | 2.0+ | 容器编排 |
| Java | 21 (LTS) | 后端服务 |
| Maven | 3.8+ | Java 构建 |
| Node.js | 20+ | 前端构建 |
| pnpm | 10+ | 前端包管理 |
| Git | 2.30+ | 版本控制 |

## 步骤 1：配置环境变量

```bash
# 克隆仓库
git clone https://github.com/SoarCollab/RecordPlatform.git
cd RecordPlatform

# 复制环境变量模板
cp .env.example .env
```

编辑 `.env`，修改关键配置：

```bash
# 必须修改的配置
JWT_KEY=<生成32字符以上随机字符串>
DB_PASSWORD=<数据库密码>
REDIS_PASSWORD=<Redis密码>
S3_ACCESS_KEY=<MinIO访问密钥>
S3_SECRET_KEY=<MinIO密钥>

# 可选：根据部署环境调整
SPRING_PROFILES_ACTIVE=local   # local / dev / prod
```

::: tip
开发环境可直接使用 `.env.example` 中的默认值快速启动。
:::

## 步骤 2：启动基础设施

使用 `docker-compose.infra.yml` 一键启动可容器化的基础服务：

```bash
# 启动所有基础设施
docker compose -f docker-compose.infra.yml up -d

# 等待所有服务健康就绪（需要 healthcheck 支持）
docker compose -f docker-compose.infra.yml up -d --wait
```

包含的服务：

| 服务 | 端口 | 管理界面 |
|------|------|----------|
| Nacos | 8848 | http://localhost:8848/nacos |
| MySQL | 3306 | — |
| Redis | 6379 | — |
| RabbitMQ | 5672 | http://localhost:15672 |
| MinIO-A | 9000 | http://localhost:9001 |
| MinIO-B | 9010 | http://localhost:9011 |
| OTel Collector | 4317/4318/8889 | — |
| Jaeger | 16686 | http://localhost:16686 |

验证启动状态：

```bash
docker compose -f docker-compose.infra.yml ps
```

## 步骤 3：配置 Nacos

Nacos 作为配置中心，需要导入应用配置。

1. 登录 Nacos 控制台：http://localhost:8848/nacos（默认 nacos/nacos）
2. 创建配置：

| Data ID | Group | 说明 |
|---------|-------|------|
| `backend-web.yaml` | DEFAULT_GROUP | 后端主配置（数据库、Redis、RabbitMQ 连接信息） |
| `platform-storage.yaml` | DEFAULT_GROUP | 存储服务配置（S3 节点列表、加密参数） |

::: warning 重要
数据库密码、Redis 密码等敏感信息存储在 Nacos 配置中，而非 `.env` 文件。`.env` 中的基础设施凭据仅供 docker-compose 使用。`platform-fisco` 的区块链节点与合约地址配置来自 `.env` 中的 `FISCO_*` 变量，而不是 Nacos Data ID。
:::

## 步骤 4：FISCO BCOS 节点

FISCO BCOS 区块链节点**无法通过 docker-compose 启动**，需要在服务器上手动部署。

### 快速搭建（单群组 4 节点）

```bash
# 下载 build_chain 脚本
curl -#LO https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v3.8.0/build_chain.sh
chmod +x build_chain.sh

# 生成 4 节点链（Air 版）
bash build_chain.sh -l 127.0.0.1:4 -p 30300,20200

# 启动所有节点
bash nodes/127.0.0.1/start_all.sh
```

### 复制 SDK 证书

```bash
# 将证书复制到 FISCO 服务的资源目录
cp nodes/127.0.0.1/sdk/* platform-fisco/src/main/resources/conf/
```

### 部署智能合约

使用 FISCO BCOS 控制台部署 `Storage.sol` 和 `Sharing.sol`：

```bash
# 下载并启动控制台
# 参考：https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/quick_start/air_installation.html

# 部署合约后，将合约地址更新到 .env
FISCO_STORAGE_CONTRACT=0x<部署后的地址>
FISCO_SHARING_CONTRACT=0x<部署后的地址>
```

::: info
详细的节点搭建和合约部署流程请参考 [FISCO BCOS 官方文档](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/)。
:::

## 步骤 5：环境验证

运行环境预检脚本，一次性验证所有基础设施：

```bash
./scripts/env-check.sh
```

脚本检查 8 项内容：

| # | 检查项 | 验证内容 |
|---|--------|----------|
| 1 | Nacos | 连通性 + 配置存在性 |
| 2 | MySQL | 连接 + 数据库存在 |
| 3 | Redis | 认证 + PING |
| 4 | RabbitMQ | AMQP 端口 + 管理 API |
| 5 | FISCO BCOS | 节点端口连通 |
| 6 | S3/MinIO | 健康检查 + Bucket 存在 |
| 7 | TLS 证书 | 文件存在 + 有效期 |
| 8 | 合约地址 | 格式校验 |

自动修复模式（创建数据库、Bucket 等）：

```bash
./scripts/env-check.sh --fix
```

检查单个服务：

```bash
./scripts/env-check.sh --service mysql
```

## 步骤 6：构建与启动

### 构建

```bash
# 1. 安装共享接口（首次或依赖变更时）
mvn -f platform-api/pom.xml clean install

# 2. 构建后端
mvn -f platform-backend/pom.xml clean package -DskipTests

# 3. 构建 FISCO 服务
mvn -f platform-fisco/pom.xml clean package -DskipTests

# 4. 构建存储服务
mvn -f platform-storage/pom.xml clean package -DskipTests

# 5. 前端
cd platform-frontend && pnpm install && pnpm build
```

### 启动

```bash
# 使用管理脚本一键启动所有服务
./scripts/start.sh start all

# 查看服务状态
./scripts/start.sh status
```

启动顺序：`platform-storage` → `platform-fisco` → `platform-backend` → 前端

### 验证

```bash
# 后端健康检查
curl http://localhost:8000/record-platform/actuator/health

# 前端开发服务器
cd platform-frontend && pnpm dev
```

## 故障排查

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| Nacos 启动失败 | 内存不足 | 确保 Docker 分配 ≥ 4GB 内存 |
| MySQL 连接拒绝 | 容器未就绪 | `docker compose -f docker-compose.infra.yml up -d --wait` |
| Redis AUTH 失败 | 密码不匹配 | 检查 `.env` 中 `REDIS_PASSWORD` 与 Nacos 配置一致 |
| FISCO 服务启动卡住 | 节点不可达 | `SdkBeanConfig` 初始化时连接节点，确保节点已启动 |
| MinIO 无法访问 | 端口冲突 | 检查 9000/9001 端口是否被占用 |
| Dubbo 服务发现失败 | DUBBO_HOST 配置错误 | Docker 环境需设置 `DUBBO_HOST` 为宿主机 IP |
| env-check.sh 深度检查跳过 | CLI 工具未安装 | 安装 `mysql-client`、`redis-cli`、`aws` 等工具 |
