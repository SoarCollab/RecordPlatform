# Prerequisites

Before running RecordPlatform, ensure the following infrastructure services are available.

## Required Services

| Service | Version | Default Port | Purpose |
|---------|---------|--------------|---------|
| **Nacos** | 2.x | 8848 | Service discovery and configuration center |
| **MySQL** | 8.0+ | 3306 | Relational database |
| **Redis** | 7.0+ | 6379 | Caching and distributed locks |
| **RabbitMQ** | 3.8+ | 5672, 15672 | Asynchronous message queue |
| **S3-compatible Storage** | - | 9000 | Object storage (MinIO recommended) |
| **FISCO BCOS** | 3.x | 20200 | Blockchain node |

## Development Environment

### JDK

- **Required**: JDK 21 or later
- **Recommendation**: Eclipse Temurin or Amazon Corretto

```bash
# Verify Java version
java -version
# Should show: openjdk version "21.x.x"
```

### Maven

- **Required**: Maven 3.8+

```bash
# Verify Maven version
mvn -version
```

### Node.js (for frontend)

- **Required**: Node.js 18+ and pnpm

```bash
# Verify Node.js
node -v

# Install pnpm if not available
npm install -g pnpm
```

## Quick Setup with Docker Compose

For development, you can start all infrastructure services using Docker Compose:

```yaml
# docker-compose.yml (infrastructure only)
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
# Start infrastructure
docker-compose up -d
```

## FISCO BCOS Setup

FISCO BCOS requires separate setup. Refer to the official documentation:

- [FISCO BCOS Quick Start](https://fisco-bcos-doc.readthedocs.io/zh_CN/latest/docs/quick_start/air_installation.html)

### Minimum Configuration

1. Single-node development chain
2. Default peer address: `127.0.0.1:20200`
3. SDK certificates in `platform-fisco/src/main/resources/conf/`

## Verifying Setup

After starting all services, verify connectivity:

| Service | Health Check |
|---------|--------------|
| Nacos | http://localhost:8848/nacos |
| MySQL | `mysql -h localhost -u root -p` |
| Redis | `redis-cli ping` |
| RabbitMQ | http://localhost:15672 (guest/guest) |
| MinIO | http://localhost:9001 |

