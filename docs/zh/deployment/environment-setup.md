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
# 启动基础设施前必须修改的配置
JWT_KEY=<生成32字符以上随机字符串>
DB_PASSWORD=<数据库密码>
REDIS_PASSWORD=<Redis密码>
RABBITMQ_USERNAME=<RabbitMQ用户名>
RABBITMQ_PASSWORD=<RabbitMQ密码>
NACOS_USERNAME=<Nacos用户名>
NACOS_PASSWORD=<Nacos密码>
NACOS_AUTH_TOKEN=<至少32字节随机值的Base64令牌>
NACOS_AUTH_IDENTITY_KEY=<Nacos身份键名>
NACOS_AUTH_IDENTITY_VALUE=<Nacos身份密钥>
S3_ACCESS_KEY=<MinIO访问密钥>
S3_SECRET_KEY=<MinIO密钥>

# 可选：根据部署环境调整
SPRING_PROFILES_ACTIVE=local   # local / dev / prod
```

::: warning
不要在共享主机或服务器上使用 `.env.example` 的占位值。基础设施 Compose 会在缺少必填密钥时快速失败，上述密码和令牌都必须替换为当前环境专用的强随机值。
:::

`docker-compose.infra.yml` 默认通过 `INFRA_BIND_ADDRESS` 和 `OBSERVABILITY_BIND_ADDRESS` 将发布端口绑定到 `127.0.0.1`。服务器上应保留该默认值，只通过防火墙、VPN、SSH 隧道或带认证的反向代理暴露应用入口。除非主机防火墙已限制所有基础设施端口，否则不要把这些绑定地址改成 `0.0.0.0`。

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
| Nacos | 127.0.0.1:8848 | http://localhost:8848/nacos |
| MySQL | 127.0.0.1:3306 | — |
| Redis | 127.0.0.1:6379 | — |
| RabbitMQ | 127.0.0.1:5672 | http://localhost:15672 |
| MinIO-A | 127.0.0.1:9000 | http://localhost:9001 |
| MinIO-B | 127.0.0.1:9010 | http://localhost:9011 |
| OTel Collector | 127.0.0.1:4317/4318/8889 | — |
| Jaeger | 127.0.0.1:16686 | http://localhost:16686 |

验证启动状态：

```bash
docker compose -f docker-compose.infra.yml ps
```

## 步骤 3：配置 Nacos

Nacos 作为配置中心，需要导入应用配置。

1. 登录 Nacos 控制台：http://localhost:8848/nacos，使用当前环境配置的凭据。如果 Nacos 镜像初始化了内置默认账号，请在主机暴露到工作站之外前完成轮换。
2. 创建配置：

| Data ID | Group | 说明 |
|---------|-------|------|
| `backend-web.yaml` | DEFAULT_GROUP | 后端主配置（数据库、Redis、RabbitMQ 连接信息） |
| `platform-storage.yaml` | DEFAULT_GROUP | 存储服务配置（S3 节点列表、加密参数） |

::: warning 重要
数据库密码、Redis 密码等敏感信息存储在 Nacos 配置中，而非 `.env` 文件。`.env` 中的基础设施凭据仅供 docker-compose 使用。`platform-fisco` 的区块链节点、合约、签名账户和 nonce 状态配置来自部署环境变量（`FISCO_*` 或 `BSN_*`），而不是 Nacos Data ID。`BSN_BESU_PRIVATE_KEY` 应由部署密钥管理器注入，不得提交到 `.env`。
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

必须使用带门禁的部署脚本，禁止手工激活地址。脚本通过 FISCO BCOS 控制台执行：先校验版本控制中的 artifact catalog，将 `getGroupInfo` 的 chain/group/crypto/VM 与显式 EVM 部署目标对账，用固定的 FISCO solc `0.8.11+commit.6b4cc280` keccak256/sm3 编译器重建两个合约的 ECC/SM creation 与 deployed runtime，并在每笔部署前比较 canonical ABI、decoded bytecode 和 chain/group。部署后在同一个 Console 会话中读取 `getGroupInfo` 和结构化交易回执，要求显式成功状态 `0`，交叉核对交易哈希和合约地址后才采用该回执的区块号；随后先校验 `getCode` 的完整 runtime bytes，再校验 `contractIdentity()`。全部通过后先发布结构化审计回执，再原子更新 `.env`。

该脚本有意仅支持 `BLOCKCHAIN_ACTIVE=local-fisco`。BSN FISCO/Besu 激活配置会在任何 Console 查询前被拒绝；这些网络必须使用各自经审查的 provider 部署流程。

先在 `.env` 中显式配置目标链身份和持久化审计目录：

```dotenv
FISCO_CHAIN_ID=chain0
FISCO_GROUP_ID=group0
CONTRACT_DEPLOYMENT_RECEIPT_DIR=/var/lib/record-platform/contract-deployments
```

```bash
# 预览全部阶段，不修改控制台、链或 .env
./scripts/contract-deploy.sh --dry-run --console-dir /opt/fisco/console

# 部署并原子激活 Storage 与 Sharing
./scripts/contract-deploy.sh \
  --console-dir /opt/fisco/console \
  --env-file .env \
  --receipt-dir /var/lib/record-platform/contract-deployments

# 独立复核签入 catalog
python3 tools/contracts/contract_fingerprint.py verify \
  --project-root . \
  --catalog platform-fisco/src/main/resources/contract-registry/artifacts.json
```

激活成功会同时写入 `FISCO_STORAGE_CONTRACT`、`FISCO_SHARING_CONTRACT`，以及两个合约各自完整的 `FISCO_*_DEPLOYMENT_TX`、`FISCO_*_DEPLOYMENT_BLOCK`、`FISCO_*_DEPLOYMENT_EFFECTIVE_AT` 三元组；两者共用同一个 UTC 生效时间。激活前会原子发布 `record-platform-contract-deployment-receipt.v2` JSON 回执，记录 catalog SHA-256、`LOCAL_FISCO` chain/group 身份、每个合约的 `receiptStatus=SUCCESS`，以及公开的名称/版本/地址/交易/区块证据。每组 tx/address/block 都来自同一成功回执，绝不记录 RPC URL、证书、私钥或令牌；历史 `v1` 回执只继续作为旧审计记录。

禁止只复制一个地址、整组留空或只配置证据三元组的一部分。chain/group 错误、WASM/crypto 组合不受支持、回执缺失/失败/歧义、交易/地址/区块不一致、catalog 身份不一致、solc 产物漂移、runtime code 缺失或与实际 ECC/SM 变体的签入 runtime 不一致、身份调用非零/revert、响应解析错误或回执写入失败时，原 `.env` 保持不变。Dry-run 不调用 Console、不生成生效时间或回执，也不修改文件或链状态。Console 的 `contract2java.sh` 必须支持 `-v 0.8.11`，并能提供 `$HOME/.fisco/solc/0.8.11/{keccak256,sm3}/solc`；任何 ABI、creation 或 runtime 不一致都会在第一笔部署交易前被阻断。

`./scripts/env-check.sh --service contracts` 只检查必填字段格式。最终必须重启 `platform-fisco`：服务启动时通过所选活动链客户端查询每份回执，要求 FISCO 状态 `0` 或 Besu 状态 `1`，逐项核对 tx/address/block；RPC 或任一字段失败时不会发布任何 `ACTIVE` registry。旧环境若证据为空，必须取得真实回执或重新部署，禁止填写占位值。回滚时应整体恢复上一版已审查的 catalog、两个地址和两组真实三元组后重启；任一回执无法在配置 chain/group 上重新证明时，应保持服务停止并重新部署，不得绕过校验。

Artifact 升级属于需审查的代码变更：两份 Solidity 源码、签入 ABI、ECC/SM creation/runtime bytecode、语义版本、生命周期状态和 catalog 指纹必须一起更新。历史 proof 与审计需要的 deprecated/revoked 制品及部署回执必须保留。升级与回滚规则见[区块链集成](../architecture/blockchain-integration.md#合约-registry-与制品指纹)，脚本全部参数见 `scripts/README.md`。

::: info
节点和控制台搭建流程请参考 [FISCO BCOS 官方文档](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/)。RecordPlatform 合约激活仍必须使用上方仓库门禁脚本。
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

# 2. 安装 backend-service 依赖的共享证明/verifier SDK
mvn -f platform-verifier/pom.xml -pl sdk -am clean install -DskipTests

# 3. 构建后端
mvn -f platform-backend/pom.xml clean package -DskipTests

# 4. 构建 FISCO 服务
mvn -f platform-fisco/pom.xml clean package -DskipTests

# 5. 构建存储服务
mvn -f platform-storage/pom.xml clean package -DskipTests

# 6. 前端
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
