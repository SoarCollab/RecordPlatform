# Daytona Test Environment

Run RecordPlatform tests in Daytona cloud sandbox with Docker-in-Docker support for Testcontainers.

## Quick Links

| Method | Description | Startup Time |
|--------|-------------|--------------|
| [Standard](#mcp-tool-usage-step-by-step) | Basic DinD sandbox | ~15-20 min |
| [**Snapshot (Recommended)**](#snapshot-optimized-version) | Pre-cached dependencies | **< 2 min** |

---

## Snapshot Optimized Version

**Recommended for most use cases.** Uses pre-built snapshot with cached dependencies.

### Benefits
- **Fast startup**: < 10 seconds to create sandbox
- **Pre-cached Maven**: Skip 5-8 minutes of dependency downloads
- **Pre-cached pnpm**: Skip 2-3 minutes of npm installs
- **Pre-pulled images**: MySQL, Redis, RabbitMQ ready to go

### Quick Start

```bash
# 1. Set API key
export DAYTONA_API_KEY="your-key-here"

# 2. Create snapshot (first time only, ~15 min)
cd scripts/daytona
./create-snapshot.sh

# 3. Run tests (< 15 min total)
npx tsx daytona-sdk-example.ts run-tests
# or
python daytona-sdk-example.py run-tests
```

### Available Files

| File | Description |
|------|-------------|
| `Dockerfile.snapshot` | Optimized Dockerfile with pre-cached deps |
| `create-snapshot.sh` | Snapshot creation script |
| `run-tests-optimized.sh` | Optimized test runner |
| `daytona-sdk-example.ts` | TypeScript SDK example |
| `daytona-sdk-example.py` | Python SDK example |
| `SNAPSHOT_GUIDE.md` | **Detailed usage guide** |

**See [SNAPSHOT_GUIDE.md](SNAPSHOT_GUIDE.md) for complete documentation.**

---

## Standard Version (Basic)

Use this if you don't want to create a snapshot, or need a fresh environment each time.

## Prerequisites

1. **Daytona API Key**: Get from https://app.daytona.io/dashboard/api-keys
2. **Daytona CLI** (optional): `brew install daytonaio/cli/daytona`

## Quick Start with Claude Code

In Claude Code, simply ask:

```
Please run integration tests in Daytona for me
```

Or use the MCP tools directly:

```
1. Create sandbox: mcp__daytona-mcp__create_sandbox (snapshot: docker:28.3.3-dind)
2. Clone repo: mcp__daytona-mcp__git_clone
3. Run tests: mcp__daytona-mcp__execute_command
```

## MCP Tool Usage (Step by Step)

### Step 1: Create DinD Sandbox

```
Tool: mcp__daytona-mcp__create_sandbox
Parameters:
  snapshot: "docker:28.3.3-dind"
  cpu: 4
  memory: 8
  disk: 10
  autoStopInterval: 30
```

### Step 2: Clone Repository

```
Tool: mcp__daytona-mcp__git_clone
Parameters:
  id: <sandbox_id>
  url: "https://github.com/YOUR_ORG/RecordPlatform.git"
  path: "/workspace/project"
```

### Step 3: Run Tests

```
Tool: mcp__daytona-mcp__execute_command
Parameters:
  id: <sandbox_id>
  command: |
    # Install dependencies
    apk add --no-cache openjdk21 maven nodejs npm bash curl git
    npm install -g pnpm@10
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
    export PATH="$JAVA_HOME/bin:$PATH"

    # Wait for Docker daemon
    timeout 60 sh -c 'until docker info >/dev/null 2>&1; do sleep 1; done'

    cd /workspace/project

    # Install platform-api
    mvn -f platform-api/pom.xml clean install -DskipTests -q

    # Run backend tests (unit + integration)
    mvn -f platform-backend/pom.xml clean verify -pl backend-service,backend-web -am -Pit

    # Run frontend tests
    cd platform-frontend && pnpm install --frozen-lockfile && pnpm test:coverage
```

### Step 4: Download Coverage Report (Optional)

```
Tool: mcp__daytona-mcp__file_download
Parameters:
  id: <sandbox_id>
  filePath: "/workspace/project/platform-backend/backend-web/target/site/jacoco/jacoco.xml"
```

### Step 5: Cleanup

```
Tool: mcp__daytona-mcp__destroy_sandbox
Parameters:
  id: <sandbox_id>
```

## CLI Usage

### Create Custom Snapshot (One-time)

```bash
cd scripts/daytona
daytona snapshot create record-platform-test --dockerfile ./Dockerfile
```

### Run Tests

```bash
# Full test suite
daytona sandbox create --snapshot record-platform-test --cpu 4 --memory 8 --name test-run
daytona exec test-run -- bash /workspace/run-tests.sh

# Backend only
SKIP_FRONTEND=true daytona exec test-run -- bash /workspace/run-tests.sh

# Frontend only
SKIP_BACKEND=true daytona exec test-run -- bash /workspace/run-tests.sh

# Cleanup
daytona sandbox delete test-run
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `REPO_URL` | - | Git repository URL |
| `BRANCH` | main | Git branch to test |
| `SKIP_FRONTEND` | false | Skip frontend tests |
| `SKIP_BACKEND` | false | Skip backend tests |
| `PROJECT_DIR` | /workspace/project | Project directory path |

## Resource Requirements

| Resource | Recommended | Minimum |
|----------|-------------|---------|
| CPU | 4 cores | 2 cores |
| Memory | 8 GB | 4 GB |
| Disk | 10 GB | 5 GB |

## Test Coverage

The test suite includes:

**Backend (Maven)**:
- Unit tests: `backend-service`, `backend-web`
- Integration tests: Testcontainers (MySQL, Redis, RabbitMQ)
- Coverage: JaCoCo XML reports

**Frontend (pnpm)**:
- Unit tests: Vitest
- Coverage: LCOV reports

## Output Files

| Path | Description |
|------|-------------|
| `platform-backend/backend-service/target/site/jacoco/jacoco.xml` | Backend service coverage |
| `platform-backend/backend-web/target/site/jacoco/jacoco.xml` | Backend web coverage |
| `platform-frontend/coverage/lcov.info` | Frontend coverage |

## Troubleshooting

### Docker daemon not starting

Ensure you're using a DinD-enabled snapshot (`docker:*-dind` series).

### Tests timing out

Increase `autoStopInterval` or resource allocation (CPU/memory).

### Maven dependencies failing

Check network access to Maven Central. Ensure the sandbox has internet connectivity.

### pnpm install failing

Verify `pnpm-lock.yaml` exists and `--frozen-lockfile` is appropriate for your use case.
