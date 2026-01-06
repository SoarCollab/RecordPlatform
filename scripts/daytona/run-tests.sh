#!/bin/bash
# RecordPlatform Daytona Test Runner
# Runs backend (Maven) and frontend (pnpm) tests in Daytona sandbox
#
# Environment Variables:
#   REPO_URL       - Git repository URL (default: uses placeholder)
#   BRANCH         - Git branch to test (default: main)
#   SKIP_FRONTEND  - Skip frontend tests (default: false)
#   SKIP_BACKEND   - Skip backend tests (default: false)
#   PROJECT_DIR    - Project directory (default: /workspace/project)

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $1"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] WARNING:${NC} $1"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] ERROR:${NC} $1"; }
info() { echo -e "${BLUE}[$(date '+%H:%M:%S')] INFO:${NC} $1"; }

# Configuration
REPO_URL="${REPO_URL:-}"
BRANCH="${BRANCH:-main}"
SKIP_FRONTEND="${SKIP_FRONTEND:-false}"
SKIP_BACKEND="${SKIP_BACKEND:-false}"
PROJECT_DIR="${PROJECT_DIR:-/workspace/project}"

# Result tracking
BACKEND_RESULT=0
FRONTEND_RESULT=0

echo ""
echo "=========================================="
echo "  RecordPlatform Daytona Test Runner"
echo "=========================================="
echo ""
info "Configuration:"
info "  Branch: $BRANCH"
info "  Skip Backend: $SKIP_BACKEND"
info "  Skip Frontend: $SKIP_FRONTEND"
info "  Project Dir: $PROJECT_DIR"
echo ""

# Step 1: Wait for Docker daemon
log "[1/6] Waiting for Docker daemon..."
if ! timeout 120 sh -c 'until docker info >/dev/null 2>&1; do sleep 2; done'; then
    error "Docker daemon failed to start within 120 seconds"
    error "Testcontainers require Docker-in-Docker support"
    exit 1
fi
log "Docker daemon is ready."
docker info --format '  Docker version: {{.ServerVersion}}'

# Step 2: Clone or update repository
log "[2/6] Setting up project..."
if [ -n "$REPO_URL" ]; then
    if [ ! -d "$PROJECT_DIR/.git" ]; then
        log "Cloning repository (branch: $BRANCH)..."
        git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$PROJECT_DIR"
    else
        log "Repository exists, pulling latest changes..."
        cd "$PROJECT_DIR"
        git fetch origin "$BRANCH"
        git reset --hard "origin/$BRANCH"
    fi
else
    if [ ! -d "$PROJECT_DIR" ]; then
        error "No REPO_URL provided and project directory does not exist"
        error "Please set REPO_URL or mount/copy the project to $PROJECT_DIR"
        exit 1
    fi
    log "Using existing project directory: $PROJECT_DIR"
fi

cd "$PROJECT_DIR"
log "Working directory: $(pwd)"

# Step 3: Install platform-api (required dependency)
log "[3/6] Installing platform-api..."
if [ -f "platform-api/pom.xml" ]; then
    mvn -f platform-api/pom.xml clean install -DskipTests -q
    log "platform-api installed successfully"
else
    error "platform-api/pom.xml not found"
    exit 1
fi

# Step 4: Run backend tests
if [ "$SKIP_BACKEND" != "true" ]; then
    log "[4/6] Running backend tests (unit + integration)..."

    # Run with integration tests profile
    if mvn -f platform-backend/pom.xml clean verify \
        -pl backend-service,backend-web -am \
        -Pit \
        -Dmaven.test.failure.ignore=false; then
        BACKEND_RESULT=0
        log "Backend tests PASSED"
    else
        BACKEND_RESULT=1
        error "Backend tests FAILED"
    fi

    # Also run FISCO and Storage module tests
    log "Running platform-fisco tests..."
    if ! mvn -f platform-fisco/pom.xml test -q; then
        warn "platform-fisco tests had issues"
    fi

    log "Running platform-storage tests..."
    if ! mvn -f platform-storage/pom.xml test -q; then
        warn "platform-storage tests had issues"
    fi
else
    log "[4/6] Skipping backend tests (SKIP_BACKEND=true)"
    BACKEND_RESULT=0
fi

# Step 5: Run frontend tests
if [ "$SKIP_FRONTEND" != "true" ]; then
    log "[5/6] Running frontend tests..."
    cd "$PROJECT_DIR/platform-frontend"

    # Install dependencies
    log "Installing frontend dependencies..."
    pnpm install --frozen-lockfile

    # Run tests with coverage
    if pnpm test:coverage; then
        FRONTEND_RESULT=0
        log "Frontend tests PASSED"
    else
        FRONTEND_RESULT=1
        error "Frontend tests FAILED"
    fi

    cd "$PROJECT_DIR"
else
    log "[5/6] Skipping frontend tests (SKIP_FRONTEND=true)"
    FRONTEND_RESULT=0
fi

# Step 6: Generate summary
echo ""
echo "=========================================="
log "[6/6] Test Summary"
echo "=========================================="
echo ""

if [ $BACKEND_RESULT -eq 0 ]; then
    echo -e "  Backend Tests:  ${GREEN}PASSED${NC}"
else
    echo -e "  Backend Tests:  ${RED}FAILED${NC}"
fi

if [ $FRONTEND_RESULT -eq 0 ]; then
    echo -e "  Frontend Tests: ${GREEN}PASSED${NC}"
else
    echo -e "  Frontend Tests: ${RED}FAILED${NC}"
fi

echo ""
echo "Coverage Reports:"
echo "  Backend (JaCoCo):"
echo "    - platform-backend/backend-service/target/site/jacoco/jacoco.xml"
echo "    - platform-backend/backend-web/target/site/jacoco/jacoco.xml"
echo "  Frontend (Vitest):"
echo "    - platform-frontend/coverage/lcov.info"
echo ""
echo "=========================================="

# Exit with appropriate code
if [ $BACKEND_RESULT -ne 0 ] || [ $FRONTEND_RESULT -ne 0 ]; then
    error "Some tests failed!"
    exit 1
fi

log "All tests completed successfully!"
exit 0
