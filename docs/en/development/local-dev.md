# Local Development Environment

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 (enforced `[21,22)`) | [Adoptium OpenJDK 21](https://adoptium.net/) |
| Maven | 3.8+ | Build backend modules |
| Node.js | 20+ | Frontend development |
| pnpm | 10+ | Frontend package manager |
| Docker | 20+ | Run Testcontainers integration tests |

## Start Infrastructure

```bash
# Start Nacos, MySQL, Redis, RabbitMQ, MinIO
docker compose -f docker-compose.infra.yml up -d

# Verify all prerequisites
./scripts/env-check.sh

# Check individual services
./scripts/env-check.sh --service nacos
./scripts/env-check.sh --service mysql
./scripts/env-check.sh --service fisco
```

## Configuration

Configuration is split into two layers:

1. **Profile config** (`application-local.yml`): database host, ports, and other local settings
2. **Nacos config**: sensitive configs (keys, S3 credentials, etc.) delivered dynamically

```bash
# Copy and fill in local config
cp .env.example .env
```

Required `.env` variables:

| Variable | Description |
|----------|-------------|
| `JWT_KEY` | JWT signing key (recommend 64+ character random string) |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | S3/MinIO access credentials |
| `FISCO_CONTRACT_STORAGE_ADDRESS` | Storage.sol contract address |
| `FISCO_CONTRACT_SHARING_ADDRESS` | Sharing.sol contract address |

## IntelliJ IDEA Setup

1. Import as Maven project (select repository root)
2. Set Project SDK to JDK 21
3. Create run configurations for each service:

| Service | Main Class | Active Profiles |
|---------|------------|-----------------|
| Storage Service | `cn.flying.StorageApplication` | `local` |
| FISCO Service | `cn.flying.FiscoApplication` | `local` |
| Backend Web | `cn.flying.WebApplication` | `local` |

4. VM Options (required for running tests):
```
-javaagent:<path>/byte-buddy-agent-1.14.19.jar -Djdk.attach.allowAttachSelf=true
```

## Frontend Development

```bash
cd platform-frontend
pnpm install

# Start development server
pnpm dev

# Regenerate API types after backend changes
pnpm types:gen

# Code quality checks
pnpm lint        # ESLint
pnpm check       # svelte-check type checking
pnpm format      # Prettier formatting
```

## Multi-Module Build Order

```bash
# 1. Shared interfaces (required by all other modules — build first)
mvn -f platform-api/pom.xml clean install

# 2. Backend services (order among these three is interchangeable)
mvn -f platform-backend/pom.xml clean package -DskipTests
mvn -f platform-fisco/pom.xml clean package -DskipTests
mvn -f platform-storage/pom.xml clean package -DskipTests

# 3. Frontend build
cd platform-frontend && pnpm build
```

## Service URLs

| Service | URL | Notes |
|---------|-----|-------|
| Swagger UI | http://localhost:8000/record-platform/swagger-ui.html | API docs, auth: admin/123456 |
| OpenAPI JSON | http://localhost:8000/record-platform/v3/api-docs | OpenAPI spec |
| Druid Monitor | http://localhost:8000/record-platform/druid/ | SQL monitoring |
| Frontend | http://localhost:5173 | Dev server |
| Nacos Console | http://localhost:8848/nacos | Service registry and config |
| MinIO Console | http://localhost:9001 | Object storage management |
| RabbitMQ Management | http://localhost:15672 | Message queue monitoring |
