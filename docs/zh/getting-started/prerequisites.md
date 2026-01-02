# 前置依赖

在运行 RecordPlatform 之前，请确保以下基础设施服务已就绪。

## 必需服务

| 服务 | 版本 | 默认端口 | 用途 |
|------|------|----------|------|
| **Nacos** | 2.x | 8848 | 服务发现与配置中心 |
| **MySQL** | 8.0+ | 3306 | 关系型数据库 |
| **Redis** | 6.0+ | 6379 | 缓存与分布式锁 |
| **RabbitMQ** | 3.8+ | 5672, 15672 | 异步消息队列 |
| **S3 兼容存储** | - | 9000 | 对象存储（推荐 MinIO） |
| **FISCO BCOS** | 3.x | 20200 | 区块链节点 |

## 开发环境

### JDK

- **要求**: JDK 21 或更高版本
- **推荐**: Eclipse Temurin 或 Amazon Corretto

```bash
# 验证 Java 版本
java -version
# 应显示: openjdk version "21.x.x"
```

### Maven

- **要求**: Maven 3.8+

```bash
# 验证 Maven 版本
mvn -version
```

### Node.js（前端开发）

- **要求**: Node.js 18+ 和 pnpm

```bash
# 验证 Node.js
node -v

# 如未安装 pnpm
npm install -g pnpm
```

## 使用 Docker Compose 快速搭建

开发环境下，可使用 Docker Compose 启动所有基础设施服务：

```yaml
# docker-compose.yml（仅基础设施）
version: '3.8'
services:
  nacos:
    image: nacos/nacos-server:v2.3.0
    ports:
      - "8848:8848"
    environment:
      - MODE=standalone

  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=root
      - MYSQL_DATABASE=RecordPlatform

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"

  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    command: server /data --console-address ":9001"
    environment:
      - MINIO_ROOT_USER=minioadmin
      - MINIO_ROOT_PASSWORD=minioadmin
```

```bash
# 启动基础设施
docker-compose up -d
```

## FISCO BCOS 配置

FISCO BCOS 需要单独配置，请参考官方文档：

- [FISCO BCOS 快速入门](https://fisco-bcos-doc.readthedocs.io/zh_CN/latest/docs/quick_start/air_installation.html)

### 最小配置

1. 单节点开发链
2. 默认节点地址: `127.0.0.1:20200`
3. SDK 证书放置于 `platform-fisco/src/main/resources/conf/`

## 验证配置

启动所有服务后，验证连通性：

| 服务 | 健康检查 |
|------|----------|
| Nacos | http://localhost:8848/nacos |
| MySQL | `mysql -h localhost -u root -p` |
| Redis | `redis-cli ping` |
| RabbitMQ | http://localhost:15672 (guest/guest) |
| MinIO | http://localhost:9001 |

