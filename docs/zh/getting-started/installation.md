# 安装部署

本指南介绍如何从源码构建和运行 RecordPlatform。

## 克隆仓库

```bash
git clone https://github.com/SoarCollab/RecordPlatform.git
cd RecordPlatform
```

## 构建

### 1. 安装共享接口

`platform-api` 模块必须首先安装，因为其他模块依赖它：

```bash
mvn -f platform-api/pom.xml clean install
```

### 2. 构建后端模块

```bash
# 构建后端（多模块）
mvn -f platform-backend/pom.xml clean package -DskipTests

# 构建 FISCO 服务
mvn -f platform-fisco/pom.xml clean package -DskipTests

# 构建存储服务
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 3. 构建前端

```bash
cd platform-frontend
pnpm install
pnpm run build
```

## 运行服务

### 开发模式

**方式一：手动启动**

按顺序启动服务（Provider 先于 Consumer）：

```bash
# 终端 1: 存储服务
java -jar "$(ls platform-storage/target/platform-storage-*.jar | head -n 1)" \
  --spring.profiles.active=local

# 终端 2: FISCO 服务
java -jar "$(ls platform-fisco/target/platform-fisco-*.jar | head -n 1)" \
  --spring.profiles.active=local

# 终端 3: 后端 Web
java -jar "$(ls platform-backend/backend-web/target/backend-web-*.jar | head -n 1)" \
  --spring.profiles.active=local

> 说明：`$(...)` 写法适用于 macOS/Linux Shell。Windows PowerShell 建议显式选取单个 JAR（fisco/backend 同理）：
> `java -jar (Get-ChildItem platform-storage/target/platform-storage-*.jar | Select-Object -First 1).FullName --spring.profiles.active=local`

# 终端 4: 前端（开发服务器）
cd platform-frontend && pnpm run dev
```

**方式二：使用脚本**

```bash
# 启动所有服务
./scripts/start.sh start all

# 或单独启动
./scripts/start.sh start storage
./scripts/start.sh start fisco
./scripts/start.sh start backend
```

### 生产模式

生产环境部署（带 SkyWalking 链路追踪）：

```bash
# 启动所有服务（带 SkyWalking）
./scripts/start.sh start all --skywalking --profile=prod

# 或单独启动
./scripts/start.sh start storage --skywalking --profile=prod
./scripts/start.sh start fisco --skywalking --profile=prod
./scripts/start.sh start backend --skywalking --profile=prod
```

## 验证安装

所有服务运行后，验证：

| 端点 | URL | 预期结果 |
|------|-----|----------|
| Swagger UI | http://localhost:8000/record-platform/swagger-ui.html | API 文档 |
| 健康检查 | http://localhost:8000/record-platform/actuator/health | `{"status":"UP"}` |
| 前端 | http://localhost:5173 | 登录页面 |

### 默认凭据

| 用途 | 用户名 | 密码 |
|------|--------|------|
| Swagger 认证 | admin | 123456 |
| 应用登录 | （注册新用户） | - |

## 数据库初始化

数据库 Schema 在首次启动时通过 Flyway 自动创建。

手动初始化：

```sql
CREATE DATABASE RecordPlatform
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
```

## 故障排除

**Nacos 连接失败**
- 验证 Nacos 运行状态: `curl http://localhost:8848/nacos`
- 检查环境变量 `NACOS_HOST`

**Dubbo 服务未找到**
- 确保 Provider 服务（storage, fisco）在 backend 之前启动
- 在 Nacos 服务列表中检查已注册的服务

**构建失败**
- 确保 `platform-api` 已首先安装
- 检查 Maven 和 JDK 版本

