#!/bin/bash
# RecordPlatform Snapshot Creation Script
# Creates optimized Daytona snapshot with pre-cached dependencies
#
# Usage:
#   ./create-snapshot.sh [OPTIONS]
#
# Options:
#   --name NAME         Snapshot name (default: recordplatform-test-env)
#   --cpu N             CPU cores (default: 4)
#   --memory N          Memory in GB (default: 8)
#   --disk N            Disk in GB (default: 10)
#   --dockerfile PATH   Dockerfile path (default: ./Dockerfile.snapshot)
#   --dry-run           Show commands without executing
#   --help              Show this help message

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

SNAPSHOT_NAME="recordplatform-test-env"
CPU=4
MEMORY=8
DISK=10
DOCKERFILE="./Dockerfile.snapshot"
DRY_RUN=false

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $1"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] WARNING:${NC} $1"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] ERROR:${NC} $1"; }
info() { echo -e "${BLUE}[$(date '+%H:%M:%S')] INFO:${NC} $1"; }
step() { echo -e "${CYAN}[$(date '+%H:%M:%S')] ▶${NC} $1"; }

show_help() {
    cat << EOF
RecordPlatform Snapshot Creation Script

Usage: $0 [OPTIONS]

Options:
    --name NAME         Snapshot name (default: recordplatform-test-env)
    --cpu N             CPU cores (default: 4)
    --memory N          Memory in GB (default: 8)
    --disk N            Disk in GB (default: 10)
    --dockerfile PATH   Dockerfile path (default: ./Dockerfile.snapshot)
    --dry-run           Show commands without executing
    --help              Show this help message

Environment Variables:
    DAYTONA_API_KEY     Daytona API key (required)
    DAYTONA_TARGET      Target region: us/eu (default: us)

Examples:
    $0
    $0 --name my-snapshot --cpu 8 --memory 16
    $0 --dry-run

Prerequisites:
    1. Daytona CLI installed: brew install daytonaio/cli/daytona
    2. DAYTONA_API_KEY environment variable set
    3. Dockerfile.snapshot in the current directory

EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --name)
            SNAPSHOT_NAME="$2"
            shift 2
            ;;
        --cpu)
            CPU="$2"
            shift 2
            ;;
        --memory)
            MEMORY="$2"
            shift 2
            ;;
        --disk)
            DISK="$2"
            shift 2
            ;;
        --dockerfile)
            DOCKERFILE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --help)
            show_help
            ;;
        *)
            error "Unknown option: $1"
            echo "Run '$0 --help' for usage information"
            exit 1
            ;;
    esac
done

echo ""
echo "=========================================="
echo "  RecordPlatform Snapshot Creator"
echo "=========================================="
echo ""

step "Checking prerequisites..."

if ! command -v daytona &> /dev/null; then
    error "Daytona CLI not found!"
    echo ""
    echo "Install instructions:"
    echo "  macOS:   brew install daytonaio/cli/daytona"
    echo "  Windows: powershell -Command \"irm https://get.daytona.io/windows | iex\""
    echo "  Linux:   curl -sf https://get.daytona.io | sh"
    echo ""
    exit 1
fi
log "✓ Daytona CLI found: $(daytona version 2>&1 | head -1 || echo 'unknown version')"

if [ -z "$DAYTONA_API_KEY" ]; then
    error "DAYTONA_API_KEY not set!"
    echo ""
    echo "Get your API key from: https://app.daytona.io/dashboard/api-keys"
    echo "Then export it: export DAYTONA_API_KEY='your-key-here'"
    echo ""
    exit 1
fi
log "✓ DAYTONA_API_KEY is set"

DAYTONA_TARGET="${DAYTONA_TARGET:-us}"
log "✓ Target region: $DAYTONA_TARGET"

if [ ! -f "$DOCKERFILE" ]; then
    error "Dockerfile not found: $DOCKERFILE"
    echo ""
    echo "Expected file location: $(pwd)/$DOCKERFILE"
    echo ""
    exit 1
fi
log "✓ Dockerfile found: $DOCKERFILE"

echo ""
info "Configuration:"
info "  Snapshot Name: $SNAPSHOT_NAME"
info "  CPU Cores:     $CPU"
info "  Memory:        ${MEMORY}GB"
info "  Disk:          ${DISK}GB"
info "  Dockerfile:    $DOCKERFILE"
info "  Target:        $DAYTONA_TARGET"
echo ""

if [ "$DRY_RUN" = false ]; then
    read -p "$(echo -e ${YELLOW}Proceed with snapshot creation? [y/N]:${NC} )" -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        warn "Snapshot creation cancelled by user"
        exit 0
    fi
fi

if [ "$DRY_RUN" = true ]; then
    warn "DRY RUN MODE - Commands will not be executed"
    echo ""
    echo "Commands that would be executed:"
    echo ""
    echo "daytona snapshot create $SNAPSHOT_NAME \\"
    echo "  --dockerfile $DOCKERFILE \\"
    echo "  --cpu $CPU \\"
    echo "  --memory $MEMORY \\"
    echo "  --disk $DISK \\"
    echo "  --target $DAYTONA_TARGET"
    echo ""
    exit 0
fi

echo ""
step "Creating snapshot (this may take 15-20 minutes)..."
echo ""

START_TIME=$(date +%s)

if daytona snapshot create "$SNAPSHOT_NAME" \
    --dockerfile "$DOCKERFILE" \
    --cpu "$CPU" \
    --memory "$MEMORY" \
    --disk "$DISK" \
    --target "$DAYTONA_TARGET"; then
    
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    MINUTES=$((DURATION / 60))
    SECONDS=$((DURATION % 60))
    
    echo ""
    log "✓ Snapshot created successfully!"
    info "  Name: $SNAPSHOT_NAME"
    info "  Duration: ${MINUTES}m ${SECONDS}s"
    echo ""
    
    step "Verifying snapshot..."
    if daytona snapshot list | grep -q "$SNAPSHOT_NAME"; then
        log "✓ Snapshot verified in list"
    else
        warn "Snapshot not found in list (may take a moment to appear)"
    fi
    
    echo ""
    echo "=========================================="
    log "Snapshot creation complete!"
    echo "=========================================="
    echo ""
    echo "Next steps:"
    echo ""
    echo "  1. Test the snapshot:"
    echo "     daytona sandbox create --snapshot $SNAPSHOT_NAME --id test-snapshot"
    echo ""
    echo "  2. Run tests:"
    echo "     daytona exec test-snapshot -- bash /workspace/run-tests-optimized.sh"
    echo ""
    echo "  3. Check environment:"
    echo "     daytona exec test-snapshot -- bash /workspace/health-check.sh"
    echo ""
    echo "  4. Cleanup:"
    echo "     daytona sandbox delete test-snapshot"
    echo ""
    echo "  5. Use in CI/CD:"
    echo "     See scripts/daytona/SNAPSHOT_GUIDE.md for examples"
    echo ""
    
else
    error "Snapshot creation failed!"
    echo ""
    echo "Troubleshooting tips:"
    echo "  1. Check your API key is valid"
    echo "  2. Verify network connectivity"
    echo "  3. Check Daytona service status"
    echo "  4. Review Dockerfile for syntax errors"
    echo "  5. Try with fewer resources (--cpu 2 --memory 4)"
    echo ""
    echo "For detailed logs, run:"
    echo "  daytona logs --follow"
    echo ""
    exit 1
fi

exit 0
