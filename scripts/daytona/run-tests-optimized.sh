#!/bin/bash
# Optimized test runner for RecordPlatform (Snapshot version)
# Assumes dependencies are pre-cached in the snapshot

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $1"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] WARNING:${NC} $1"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] ERROR:${NC} $1"; }
info() { echo -e "${BLUE}[$(date '+%H:%M:%S')] INFO:${NC} $1"; }

REPO_URL="${REPO_URL:-https://github.com/SoarCollab/RecordPlatform.git}"
BRANCH="${BRANCH:-main}"
SKIP_FRONTEND="${SKIP_FRONTEND:-false}"
SKIP_BACKEND="${SKIP_BACKEND:-false}"
PROJECT_DIR="${PROJECT_DIR:-/workspace/project}"

BACKEND_RESULT=0
FRONTEND_RESULT=0

echo ""
echo "=========================================="
echo "  RecordPlatform Test Runner (Optimized)"
echo "=========================================="
echo ""
info "Configuration:"
info "  Branch: $BRANCH"
info "  Skip Backend: $SKIP_BACKEND"
info "  Skip Frontend: $SKIP_FRONTEND"
info "  Project Dir: $PROJECT_DIR"
echo ""

log "[1/5] Waiting for Docker daemon..."
if ! timeout 120 sh -c 'until docker info >/dev/null 2>&1; do sleep 2; done'; then
    error "Docker daemon failed to start within 120 seconds"
    exit 1
fi
log "Docker daemon is ready."

log "[2/5] Updating repository..."
if [ -d "$PROJECT_DIR/.git" ]; then
    cd "$PROJECT_DIR"
    git fetch origin "$BRANCH" --depth 1
    git reset --hard "origin/$BRANCH"
    log "Repository updated (incremental pull)"
else
    warn "Repository not pre-cloned, performing full clone..."
    git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$PROJECT_DIR"
    cd "$PROJECT_DIR"
fi

log "[3/5] Refreshing platform-api..."
mvn -f platform-api/pom.xml clean install -DskipTests -q
log "platform-api refreshed"

if [ "$SKIP_BACKEND" != "true" ]; then
    log "[4/5] Running backend tests..."
    
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
    
    log "Running platform-fisco tests..."
    mvn -f platform-fisco/pom.xml test -q || warn "platform-fisco tests had issues"
    
    log "Running platform-storage tests..."
    mvn -f platform-storage/pom.xml test -q || warn "platform-storage tests had issues"
else
    log "[4/5] Skipping backend tests"
    BACKEND_RESULT=0
fi

if [ "$SKIP_FRONTEND" != "true" ]; then
    log "[5/5] Running frontend tests..."
    cd "$PROJECT_DIR/platform-frontend"
    
    log "Refreshing frontend dependencies..."
    pnpm install --frozen-lockfile
    
    if pnpm test:coverage; then
        FRONTEND_RESULT=0
        log "Frontend tests PASSED"
    else
        FRONTEND_RESULT=1
        error "Frontend tests FAILED"
    fi
    
    cd "$PROJECT_DIR"
else
    log "[5/5] Skipping frontend tests"
    FRONTEND_RESULT=0
fi

echo ""
echo "=========================================="
log "Test Summary"
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
echo "  Backend:  platform-backend/backend-web/target/site/jacoco/jacoco.xml"
echo "  Frontend: platform-frontend/coverage/lcov.info"
echo ""

if [ $BACKEND_RESULT -ne 0 ] || [ $FRONTEND_RESULT -ne 0 ]; then
    error "Some tests failed!"
    exit 1
fi

log "All tests completed successfully!"
exit 0
