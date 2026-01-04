# Docker Compose Deployment

Deploy RecordPlatform using Docker Compose.

## Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- 8GB RAM minimum

## Infrastructure Services

Create `docker-compose.infra.yml`:

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

## Application Services

Create `docker-compose.app.yml`:

::: warning CRITICAL: DUBBO_HOST Configuration
In Docker environments, Dubbo provider services (storage, fisco) must register with host-accessible IPs rather than internal Docker bridge IPs. Without proper `DUBBO_HOST` configuration, the backend cannot discover and call provider services, resulting in "No provider available" errors.
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
      - DUBBO_HOST=${DUBBO_HOST:-host.docker.internal}  # Required for Docker
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
      - DUBBO_HOST=${DUBBO_HOST:-host.docker.internal}  # Required for Docker
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
      # Backend is Dubbo consumer, doesn't need DUBBO_HOST
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

## Deployment Steps

### 1. Configure Environment

```bash
cp .env.example .env
vim .env
```

### 2. Start Infrastructure

```bash
docker-compose -f docker-compose.infra.yml up -d
```

### 3. Wait for Services

```bash
# Check Nacos
curl http://localhost:8848/nacos

# Check MySQL
docker-compose -f docker-compose.infra.yml exec mysql mysql -uroot -p -e "SELECT 1"
```

### 4. Build and Start Applications

```bash
# Build images
docker-compose -f docker-compose.app.yml build

# Start services
docker-compose -f docker-compose.app.yml up -d
```

### 5. Verify Deployment

```bash
# Check logs
docker-compose -f docker-compose.app.yml logs -f

# Health check
curl http://localhost:8000/record-platform/actuator/health
```

## Scaling

For high availability, scale backend instances:

```bash
docker-compose -f docker-compose.app.yml up -d --scale backend=3
```

Use a load balancer (nginx, traefik) in front of backend instances.

## DUBBO_HOST Configuration

The `DUBBO_HOST` environment variable is critical for Docker deployments. It specifies the IP address that Dubbo providers register with Nacos for service discovery.

### Configuration by Scenario

| Scenario | DUBBO_HOST Value | Notes |
|----------|------------------|-------|
| Docker Desktop (Mac/Windows) | `host.docker.internal` | Special DNS name resolving to host |
| Docker on Linux | Host machine IP (e.g., `192.168.1.100`) | Use actual network IP |
| Docker Compose (same network) | Container name (e.g., `platform-storage`) | Internal Docker DNS |
| Kubernetes | Pod IP or Service IP | Use K8s service discovery |

### Troubleshooting

**Symptom**: Backend starts but cannot call storage/fisco services

**Check service registration**:
```bash
# View registered services in Nacos
curl "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=platform-storage"
```

If the registered IP shows `172.x.x.x` (Docker internal IP), the `DUBBO_HOST` is not configured correctly.

**Fix**: Add `DUBBO_HOST` to your `.env` file:
```bash
# .env
DUBBO_HOST=host.docker.internal  # For Docker Desktop
# or
DUBBO_HOST=192.168.1.100         # For Linux, use actual host IP
```

