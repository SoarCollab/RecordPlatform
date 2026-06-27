# Environment Setup

Complete environment setup guide for RecordPlatform from scratch.

## Prerequisites

### Host Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 4 cores | 8 cores |
| Memory | 8 GB | 16 GB |
| Disk | 40 GB | 100 GB SSD |
| OS | Ubuntu 20.04+ / CentOS 8+ / macOS 12+ | Ubuntu 22.04 LTS |

### Software Requirements

| Software | Version | Purpose |
|----------|---------|---------|
| Docker | 20.10+ | Infrastructure containerization |
| Docker Compose | 2.0+ | Container orchestration |
| Java | 21 (LTS) | Backend services |
| Maven | 3.8+ | Java build |
| Node.js | 20+ | Frontend build |
| pnpm | 10+ | Frontend package manager |
| Git | 2.30+ | Version control |

## Step 1: Configure Environment Variables

```bash
# Clone the repository
git clone https://github.com/SoarCollab/RecordPlatform.git
cd RecordPlatform

# Copy the environment template
cp .env.example .env
```

Edit `.env` and update key settings:

```bash
# Must change before starting infrastructure
JWT_KEY=<random-string-at-least-32-chars>
DB_PASSWORD=<database-password>
REDIS_PASSWORD=<redis-password>
RABBITMQ_USERNAME=<rabbitmq-username>
RABBITMQ_PASSWORD=<rabbitmq-password>
NACOS_USERNAME=<nacos-username>
NACOS_PASSWORD=<nacos-password>
NACOS_AUTH_TOKEN=<base64-random-token-at-least-32-bytes>
NACOS_AUTH_IDENTITY_KEY=<nacos-identity-key-name>
NACOS_AUTH_IDENTITY_VALUE=<nacos-identity-secret>
S3_ACCESS_KEY=<minio-access-key>
S3_SECRET_KEY=<minio-secret-key>

# Optional: adjust for your environment
SPRING_PROFILES_ACTIVE=local   # local / dev / prod
```

::: warning
Do not use `.env.example` placeholder values on any shared or server host. The infrastructure compose file fails fast when required secrets are missing, and all passwords/tokens above must be replaced with strong environment-specific values.
:::

`docker-compose.infra.yml` binds published service ports to `127.0.0.1` by default through `INFRA_BIND_ADDRESS` and `OBSERVABILITY_BIND_ADDRESS`. Keep this default on servers and expose only the application-facing endpoints through a firewall, VPN, SSH tunnel, or authenticated reverse proxy. Do not set these bind addresses to `0.0.0.0` unless the host firewall restricts every infrastructure port.

## Step 2: Start Infrastructure

Use `docker-compose.infra.yml` to start all containerizable infrastructure:

```bash
# Start all infrastructure services
docker compose -f docker-compose.infra.yml up -d

# Wait until all services are healthy
docker compose -f docker-compose.infra.yml up -d --wait
```

Included services:

| Service | Port | Management UI |
|---------|------|---------------|
| Nacos | 127.0.0.1:8848 | http://localhost:8848/nacos |
| MySQL | 127.0.0.1:3306 | — |
| Redis | 127.0.0.1:6379 | — |
| RabbitMQ | 127.0.0.1:5672 | http://localhost:15672 |
| MinIO-A | 127.0.0.1:9000 | http://localhost:9001 |
| MinIO-B | 127.0.0.1:9010 | http://localhost:9011 |
| OTel Collector | 127.0.0.1:4317/4318/8889 | — |
| Jaeger | 127.0.0.1:16686 | http://localhost:16686 |

Verify status:

```bash
docker compose -f docker-compose.infra.yml ps
```

## Step 3: Configure Nacos

Nacos serves as the configuration center. Application configs must be imported.

1. Open Nacos console: http://localhost:8848/nacos and sign in with the credentials configured for this environment. If the Nacos image initializes a built-in default account, rotate it before exposing the host beyond your workstation.
2. Create configurations:

| Data ID | Group | Description |
|---------|-------|-------------|
| `backend-web.yaml` | DEFAULT_GROUP | Backend main config (DB, Redis, RabbitMQ connections) |
| `platform-storage.yaml` | DEFAULT_GROUP | Storage service config (S3 node list, encryption params) |

::: warning Important
Sensitive credentials (DB password, Redis password, etc.) are stored in Nacos configurations, not in `.env`. The infrastructure credentials in `.env` are only used by docker-compose. `platform-fisco` reads blockchain node and contract settings from `.env` (`FISCO_*`), not from a Nacos Data ID.
:::

## Step 4: FISCO BCOS Node

The FISCO BCOS blockchain node **cannot be started via docker-compose** and requires manual deployment on the server.

### Quick Setup (Single Group, 4 Nodes)

```bash
# Download build_chain script
curl -#LO https://github.com/FISCO-BCOS/FISCO-BCOS/releases/download/v3.8.0/build_chain.sh
chmod +x build_chain.sh

# Generate 4-node chain (Air version)
bash build_chain.sh -l 127.0.0.1:4 -p 30300,20200

# Start all nodes
bash nodes/127.0.0.1/start_all.sh
```

### Copy SDK Certificates

```bash
# Copy certificates to the FISCO service resource directory
cp nodes/127.0.0.1/sdk/* platform-fisco/src/main/resources/conf/
```

### Deploy Smart Contracts

Deploy `Storage.sol` and `Sharing.sol` using the FISCO BCOS console:

```bash
# Download and start the console
# See: https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/quick_start/air_installation.html

# After deployment, update contract addresses in .env
FISCO_STORAGE_CONTRACT=0x<deployed-address>
FISCO_SHARING_CONTRACT=0x<deployed-address>
```

::: info
For detailed node setup and contract deployment, see the [FISCO BCOS Documentation](https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/).
:::

## Step 5: Verify Environment

Run the environment pre-check script to validate all infrastructure at once:

```bash
./scripts/env-check.sh
```

The script checks 8 items:

| # | Check | Validates |
|---|-------|-----------|
| 1 | Nacos | Connectivity + config existence |
| 2 | MySQL | Connection + database existence |
| 3 | Redis | Authentication + PING |
| 4 | RabbitMQ | AMQP port + management API |
| 5 | FISCO BCOS | Node port connectivity |
| 6 | S3/MinIO | Health check + bucket existence |
| 7 | TLS Certificates | File existence + expiry |
| 8 | Contract Addresses | Format validation |

Auto-fix mode (creates database, buckets, etc.):

```bash
./scripts/env-check.sh --fix
```

Check a single service:

```bash
./scripts/env-check.sh --service mysql
```

## Step 6: Build and Start

### Build

```bash
# 1. Install shared interfaces (first time or when dependencies change)
mvn -f platform-api/pom.xml clean install

# 2. Build backend
mvn -f platform-backend/pom.xml clean package -DskipTests

# 3. Build FISCO service
mvn -f platform-fisco/pom.xml clean package -DskipTests

# 4. Build storage service
mvn -f platform-storage/pom.xml clean package -DskipTests

# 5. Frontend
cd platform-frontend && pnpm install && pnpm build
```

### Start

```bash
# Start all services with the management script
./scripts/start.sh start all

# Check service status
./scripts/start.sh status
```

Startup order: `platform-storage` → `platform-fisco` → `platform-backend` → frontend

### Verify

```bash
# Backend health check
curl http://localhost:8000/record-platform/actuator/health

# Frontend dev server
cd platform-frontend && pnpm dev
```

## Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| Nacos fails to start | Insufficient memory | Ensure Docker has ≥ 4GB memory allocated |
| MySQL connection refused | Container not ready | `docker compose -f docker-compose.infra.yml up -d --wait` |
| Redis AUTH failed | Password mismatch | Check `REDIS_PASSWORD` in `.env` matches Nacos config |
| FISCO service hangs on startup | Node unreachable | `SdkBeanConfig` connects on init — ensure node is running |
| MinIO inaccessible | Port conflict | Check if ports 9000/9001 are already in use |
| Dubbo service discovery fails | Wrong DUBBO_HOST | Set `DUBBO_HOST` to host IP in Docker environments |
| env-check.sh deep checks skipped | CLI tools missing | Install `mysql-client`, `redis-cli`, `aws`, etc. |
