# Docker Compose 部署

使用 Docker Compose 部署 RecordPlatform。

## 前置条件

- Docker 20.10+
- Docker Compose 2.0+
- 最低 8GB 内存

## 基础设施服务

创建 `docker-compose.infra.yml`：

```yaml
version: '3.8'

services:
  nacos:
    image: nacos/nacos-server:v2.3.0
    container_name: nacos
    ports:
      - "8848:8848"
      - "9848:9848"
    environment:
      - MODE=standalone
      - NACOS_AUTH_ENABLE=true
      - NACOS_AUTH_TOKEN=your-secret-token
    volumes:
      - nacos_data:/home/nacos/data
    restart: unless-stopped

  mysql:
    image: mysql:8.0
    container_name: mysql
    ports:
      - "3306:3306"
    environment:
      - MYSQL_ROOT_PASSWORD=${DB_PASSWORD}
      - MYSQL_DATABASE=RecordPlatform
    volumes:
      - mysql_data:/var/lib/mysql
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis_data:/data
    restart: unless-stopped

  rabbitmq:
    image: rabbitmq:3-management
    container_name: rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      - RABBITMQ_DEFAULT_USER=${RABBITMQ_USERNAME}
      - RABBITMQ_DEFAULT_PASS=${RABBITMQ_PASSWORD}
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    restart: unless-stopped

  minio-a:
    image: minio/minio:latest
    container_name: minio-a
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      - MINIO_ROOT_USER=${S3_ACCESS_KEY}
      - MINIO_ROOT_PASSWORD=${S3_SECRET_KEY}
    command: server /data --console-address ":9001"
    volumes:
      - minio_a_data:/data
    restart: unless-stopped

  minio-b:
    image: minio/minio:latest
    container_name: minio-b
    ports:
      - "9010:9000"
      - "9011:9001"
    environment:
      - MINIO_ROOT_USER=${S3_ACCESS_KEY}
      - MINIO_ROOT_PASSWORD=${S3_SECRET_KEY}
    command: server /data --console-address ":9001"
    volumes:
      - minio_b_data:/data
    restart: unless-stopped

volumes:
  nacos_data:
  mysql_data:
  redis_data:
  rabbitmq_data:
  minio_a_data:
  minio_b_data:
```

## 应用服务

创建 `docker-compose.app.yml`：

::: warning 重要：DUBBO_HOST 配置
在 Docker 环境中，Dubbo 提供者服务（storage、fisco）必须使用宿主机可访问的 IP 进行注册，而非 Docker 内部桥接网络 IP。如果未正确配置 `DUBBO_HOST`，后端服务将无法发现和调用提供者服务，导致 "No provider available" 错误。
:::

```yaml
version: '3.8'

services:
  storage:
    build:
      context: .
      dockerfile: platform-storage/Dockerfile
    container_name: platform-storage
    ports:
      - "8092:8092"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - NACOS_HOST=nacos
      - DUBBO_HOST=${DUBBO_HOST:-host.docker.internal}  # Docker 环境必须配置
    depends_on:
      - nacos
      - redis
    restart: unless-stopped

  fisco:
    build:
      context: .
      dockerfile: platform-fisco/Dockerfile
    container_name: platform-fisco
    ports:
      - "8091:8091"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - NACOS_HOST=nacos
      - DUBBO_HOST=${DUBBO_HOST:-host.docker.internal}  # Docker 环境必须配置
    volumes:
      - ./platform-fisco/src/main/resources/conf:/app/conf
    depends_on:
      - nacos
    restart: unless-stopped

  backend:
    build:
      context: .
      dockerfile: platform-backend/Dockerfile
    container_name: platform-backend
    ports:
      - "8000:8000"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - NACOS_HOST=nacos
      # 后端是 Dubbo 消费者，不需要 DUBBO_HOST
    depends_on:
      - storage
      - fisco
      - mysql
      - redis
      - rabbitmq
    restart: unless-stopped

  frontend:
    build:
      context: ./platform-frontend
      dockerfile: Dockerfile
    container_name: platform-frontend
    ports:
      - "80:80"
    environment:
      - PUBLIC_API_BASE_URL=http://backend:8000/record-platform
    depends_on:
      - backend
    restart: unless-stopped
```

## 部署步骤

### 1. 配置环境变量

```bash
cp .env.example .env
vim .env
```

### 2. 启动基础设施

```bash
docker-compose -f docker-compose.infra.yml up -d
```

### 3. 等待服务就绪

```bash
# 检查 Nacos
curl http://localhost:8848/nacos

# 检查 MySQL
docker-compose -f docker-compose.infra.yml exec mysql mysql -uroot -p -e "SELECT 1"
```

### 4. 构建并启动应用

```bash
# 构建镜像
docker-compose -f docker-compose.app.yml build

# 启动服务
docker-compose -f docker-compose.app.yml up -d
```

### 5. 验证部署

```bash
# 查看日志
docker-compose -f docker-compose.app.yml logs -f

# 健康检查
curl http://localhost:8000/record-platform/actuator/health
```

## 扩容

高可用场景下，扩展 backend 实例：

```bash
docker-compose -f docker-compose.app.yml up -d --scale backend=3
```

在 backend 实例前使用负载均衡器（nginx, traefik）。

## DUBBO_HOST 配置说明

`DUBBO_HOST` 环境变量对于 Docker 部署至关重要。它指定 Dubbo 提供者向 Nacos 注册时使用的 IP 地址。

### 不同场景的配置值

| 场景 | DUBBO_HOST 值 | 说明 |
|------|---------------|------|
| Docker Desktop（Mac/Windows）| `host.docker.internal` | 解析到宿主机的特殊 DNS 名称 |
| Linux 上的 Docker | 宿主机 IP（如 `192.168.1.100`）| 使用实际网络 IP |
| Docker Compose（同一网络）| 容器名（如 `platform-storage`）| 使用内部 Docker DNS |
| Kubernetes | Pod IP 或 Service IP | 使用 K8s 服务发现 |

### 故障排查

**症状**：后端启动正常但无法调用 storage/fisco 服务

**检查服务注册**：
```bash
# 查看 Nacos 中注册的服务
curl "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=platform-storage"
```

如果注册的 IP 显示为 `172.x.x.x`（Docker 内部 IP），说明 `DUBBO_HOST` 配置不正确。

**解决方法**：在 `.env` 文件中添加 `DUBBO_HOST`：
```bash
# .env
DUBBO_HOST=host.docker.internal  # Docker Desktop 用户
# 或
DUBBO_HOST=192.168.1.100         # Linux 用户，使用实际宿主机 IP
```

