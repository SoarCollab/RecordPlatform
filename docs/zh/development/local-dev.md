# 本地开发环境

## 环境要求

| 工具 | 版本 | 备注 |
|------|------|------|
| JDK | 21（enforced `[21,22)`） | [Adoptium OpenJDK 21](https://adoptium.net/) |
| Maven | 3.8+ | 构建后端模块 |
| Node.js | 20+ | 前端开发 |
| pnpm | 10+ | 前端包管理器 |
| Docker | 20+ | 运行 Testcontainers 集成测试 |

## 启动基础设施

```bash
# 启动 Nacos、MySQL、Redis、RabbitMQ、MinIO
docker compose -f docker-compose.infra.yml up -d

# 验证所有前置条件
./scripts/env-check.sh

# 单项检查
./scripts/env-check.sh --service nacos
./scripts/env-check.sh --service mysql
./scripts/env-check.sh --service fisco
```

## 配置文件

配置分为两层：

1. **Profile 配置**（`application-local.yml`）：数据库地址、端口等本地配置
2. **Nacos 配置**：敏感配置（密钥、S3 凭据等）动态下发

```bash
# 复制并填写本地配置
cp .env.example .env
```

`.env` 必填项：

| 变量 | 说明 |
|------|------|
| `JWT_KEY` | JWT 签名密钥（建议 64 字符以上随机字符串） |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | S3/MinIO 访问凭据 |
| `FISCO_CONTRACT_STORAGE_ADDRESS` | Storage.sol 合约地址 |
| `FISCO_CONTRACT_SHARING_ADDRESS` | Sharing.sol 合约地址 |

## IntelliJ IDEA 配置

1. 以 Maven 项目导入（选择仓库根目录）
2. 设置 Project SDK 为 JDK 21
3. 为各服务创建运行配置：

| 服务 | Main Class | Active Profiles |
|------|------------|-----------------|
| 存储服务 | `cn.flying.StorageApplication` | `local` |
| FISCO 服务 | `cn.flying.FiscoApplication` | `local` |
| 后端 Web | `cn.flying.WebApplication` | `local` |

4. VM Options（运行测试时需要）：
```
-javaagent:<path>/byte-buddy-agent-1.14.19.jar -Djdk.attach.allowAttachSelf=true
```

## 前端开发

```bash
cd platform-frontend
pnpm install

# 启动开发服务器
pnpm dev

# 后端接口变更后重新生成 API 类型
pnpm types:gen

# 代码质量检查
pnpm lint        # ESLint
pnpm check       # svelte-check 类型检查
pnpm format      # Prettier 格式化
```

## 多模块构建顺序

```bash
# 1. 共享接口（其他所有模块的依赖，必须最先执行）
mvn -f platform-api/pom.xml clean install

# 2. 后端服务（三者顺序无关）
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests

# 3. 前端构建
cd platform-frontend && pnpm build
```

## 服务访问地址

| 服务 | URL | 说明 |
|------|-----|------|
| Swagger UI | http://localhost:8000/record-platform/swagger-ui.html | API 文档，认证：admin/123456 |
| OpenAPI JSON | http://localhost:8000/record-platform/v3/api-docs | OpenAPI 规范 |
| Druid 监控 | http://localhost:8000/record-platform/druid/ | SQL 监控 |
| 前端 | http://localhost:5173 | 开发服务器 |
| Nacos 控制台 | http://localhost:8848/nacos | 服务注册与配置 |
| MinIO 控制台 | http://localhost:9001 | 对象存储管理 |
| RabbitMQ 管理 | http://localhost:15672 | 消息队列监控 |
