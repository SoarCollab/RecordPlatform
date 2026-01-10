# Installation

This guide covers building and running RecordPlatform from source.

## Clone the Repository

```bash
git clone https://github.com/SoarCollab/RecordPlatform.git
cd RecordPlatform
```

## Build

### 1. Install Shared Interfaces

The `platform-api` module must be installed first as other modules depend on it:

```bash
mvn -f platform-api/pom.xml clean install
```

### 2. Build Backend Modules

```bash
# Build backend (multi-module)
mvn -f platform-backend/pom.xml clean package -DskipTests

# Build FISCO service
mvn -f platform-fisco/pom.xml clean package -DskipTests

# Build storage service
mvn -f platform-storage/pom.xml clean package -DskipTests
```

### 3. Build Frontend

```bash
cd platform-frontend
pnpm install
pnpm run build
```

## Run Services

### Development Mode

**Option 1: Manual Startup**

Start services in order (providers before consumer):

```bash
# Terminal 1: Storage Service
java -jar "$(ls platform-storage/target/platform-storage-*.jar | head -n 1)" \
  --spring.profiles.active=local

# Terminal 2: FISCO Service
java -jar "$(ls platform-fisco/target/platform-fisco-*.jar | head -n 1)" \
  --spring.profiles.active=local

# Terminal 3: Backend Web
java -jar "$(ls platform-backend/backend-web/target/backend-web-*.jar | head -n 1)" \
  --spring.profiles.active=local

> Note: The `$(...)` form is for macOS/Linux shells. On Windows PowerShell, pick a single JAR explicitly (same idea for fisco/backend):
> `java -jar (Get-ChildItem platform-storage/target/platform-storage-*.jar | Select-Object -First 1).FullName --spring.profiles.active=local`

# Terminal 4: Frontend (dev server)
cd platform-frontend && pnpm run dev
```

**Option 2: Using Scripts**

```bash
# Start all services
./scripts/start.sh start all

# Or start individually
./scripts/start.sh start storage
./scripts/start.sh start fisco
./scripts/start.sh start backend
```

### Production Mode

For production deployment with SkyWalking tracing:

```bash
# Start all with SkyWalking
./scripts/start.sh start all --skywalking --profile=prod

# Or individually
./scripts/start.sh start storage --skywalking --profile=prod
./scripts/start.sh start fisco --skywalking --profile=prod
./scripts/start.sh start backend --skywalking --profile=prod
```

## Verify Installation

After all services are running, verify:

| Endpoint | URL | Expected |
|----------|-----|----------|
| Swagger UI | http://localhost:8000/record-platform/swagger-ui.html | API documentation |
| Health Check | http://localhost:8000/record-platform/actuator/health | `{"status":"UP"}` |
| Frontend | http://localhost:5173 | Login page |

### Default Credentials

| Purpose | Username | Password |
|---------|----------|----------|
| Swagger Auth | admin | 123456 |
| Application | (register new) | - |

## Database Initialization

The database schema is automatically created on first startup via Flyway migrations.

To manually initialize:

```sql
CREATE DATABASE RecordPlatform
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
```

## Troubleshooting

**Nacos connection failed**
- Verify Nacos is running: `curl http://localhost:8848/nacos`
- Check `NACOS_HOST` in your environment

**Dubbo service not found**
- Ensure provider services (storage, fisco) started before backend
- Check Nacos service list for registered services

**Build failures**
- Ensure `platform-api` is installed first
- Check Maven and JDK versions

