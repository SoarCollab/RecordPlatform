#!/usr/bin/env bash
# ==============================================================================
# RecordPlatform Environment Pre-Check Script
# ==============================================================================
#
# Usage:
#   ./scripts/env-check.sh              # check all services
#   ./scripts/env-check.sh --fix        # auto-fix where possible
#   ./scripts/env-check.sh --service mysql  # check single service
#
# Exit codes:
#   0 = all checks passed
#   1 = one or more checks failed
#
# ==============================================================================

set -uo pipefail

# Load environment.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Load environment variables from .env without executing shell expressions.
load_env_file() {
    local env_file="$1"
    [ -f "$env_file" ] || return 0

    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"

        # Skip empty lines and full-line comments.
        [[ "$line" =~ ^[[:space:]]*$ ]] && continue
        [[ "$line" =~ ^[[:space:]]*# ]] && continue

        if [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z_0-9]*)=(.*)$ ]]; then
            local key="${BASH_REMATCH[1]}"
            local value="${BASH_REMATCH[2]}"

            # Trim leading whitespace in value.
            value="${value#"${value%%[![:space:]]*}"}"

            # Parse quoted values first, allowing optional trailing inline comments.
            if [[ "$value" =~ ^\"(.*)\"[[:space:]]*(#.*)?$ ]]; then
                value="${BASH_REMATCH[1]}"
            elif [[ "$value" =~ ^\'(.*)\'[[:space:]]*(#.*)?$ ]]; then
                value="${BASH_REMATCH[1]}"
            else
                # Drop trailing inline comments for unquoted values.
                value="${value%%[[:space:]]#*}"
                # Trim trailing whitespace in value.
                value="${value%"${value##*[![:space:]]}"}"
            fi

            export "${key}=${value}"
        fi
    done < "$env_file"
}

# Load .env safely (treat content as data, never execute).
load_env_file "$PROJECT_ROOT/.env"

# Enable errexit after environment loading
set -e

# ==============================================================================
# Color & formatting
# ==============================================================================
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && command -v tput &>/dev/null; then
    GREEN=$(tput setaf 2)
    RED=$(tput setaf 1)
    YELLOW=$(tput setaf 3)
    CYAN=$(tput setaf 6)
    BOLD=$(tput bold)
    RESET=$(tput sgr0)
else
    GREEN="" RED="" YELLOW="" CYAN="" BOLD="" RESET=""
fi

ok()   { echo "  ${GREEN}[OK]${RESET}   $1"; }
fail() { echo "  ${RED}[FAIL]${RESET} $1"; FAILURES=$((FAILURES + 1)); }
warn() { echo "  ${YELLOW}[WARN]${RESET} $1"; WARNINGS=$((WARNINGS + 1)); }
info() { echo "  ${CYAN}[INFO]${RESET} $1"; }
section() { echo; echo "${BOLD}[$1/$TOTAL_CHECKS] $2${RESET}"; }

FAILURES=0
WARNINGS=0
TOTAL_CHECKS=8

# ==============================================================================
# Parse arguments
# ==============================================================================
FIX_MODE=false
TARGET_SERVICE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --fix) FIX_MODE=true; shift ;;
        --service)
            if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "Option --service requires a service name"
                echo "Services: nacos, mysql, redis, rabbitmq, fisco, s3, certs, contracts"
                exit 1
            fi
            TARGET_SERVICE="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [--fix] [--service NAME]"
            echo ""
            echo "Services: nacos, mysql, redis, rabbitmq, fisco, s3, certs, contracts"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# ==============================================================================
# Connectivity helpers
# ==============================================================================

# TCP port check with fallback: bash /dev/tcp -> nc -z
tcp_check() {
    local host="$1" port="$2" timeout="${3:-3}"
    if (echo >/dev/tcp/"$host"/"$port") 2>/dev/null; then
        return 0
    elif command -v nc &>/dev/null; then
        nc -z -w "$timeout" "$host" "$port" 2>/dev/null
        return $?
    else
        return 1
    fi
}

# Check if a CLI tool is available
has_cmd() { command -v "$1" &>/dev/null; }

# ==============================================================================
# 1. Nacos
# ==============================================================================
check_nacos() {
    local host="${NACOS_HOST:-localhost}"
    local port="${NACOS_PORT:-8848}"

    section 1 "Nacos ($host:$port)"

    # Connectivity
    if curl -sf --max-time 5 "http://$host:$port/nacos/v1/console/health/readiness" &>/dev/null; then
        ok "Nacos is reachable and ready"
    elif tcp_check "$host" "$port"; then
        warn "Nacos port open but readiness endpoint failed"
    else
        fail "Cannot connect to Nacos at $host:$port"
        info "Start with: docker compose -f docker-compose.infra.yml up -d nacos"
        return
    fi

    # Deep check: login API
    local user="${NACOS_USERNAME:-nacos}"
    local pass="${NACOS_PASSWORD:-nacos}"
    local token
    token=$(curl -sf --max-time 5 -X POST \
        "http://$host:$port/nacos/v1/auth/login" \
        --data-urlencode "username=$user" \
        --data-urlencode "password=$pass" 2>/dev/null | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4 || true)

    if [ -n "$token" ]; then
        ok "Nacos login successful (user: $user)"

        # Check key config Data IDs
        for data_id in "backend-web.yaml" "platform-storage.yaml"; do
            local resp
            # Keep pre-check running even if a single Nacos query fails.
            resp=$(curl -sf --max-time 5 \
                "http://$host:$port/nacos/v1/cs/configs?dataId=$data_id&group=DEFAULT_GROUP&accessToken=$token" 2>/dev/null || true)
            if [ -n "$resp" ] && [ "$resp" != "config data not exist" ]; then
                ok "Config found: $data_id"
            else
                warn "Config not found: $data_id"
                info "Import Nacos configs from nacos-config-template.yaml"
            fi
        done
    else
        warn "Nacos login failed (user: $user) - auth may be disabled or credentials wrong"
    fi
}

# ==============================================================================
# 2. MySQL
# ==============================================================================
check_mysql() {
    local host="${MYSQL_HOST:-127.0.0.1}"
    local port="${MYSQL_PORT:-3306}"
    local password="${DB_PASSWORD:-recordplatform}"

    section 2 "MySQL ($host:$port)"

    if has_cmd mysql; then
        if mysql -h "$host" -P "$port" -uroot -p"$password" -e "SELECT 1" &>/dev/null; then
            ok "MySQL connection successful"

            # Check database exists
            if mysql -h "$host" -P "$port" -uroot -p"$password" -e "USE RecordPlatform" &>/dev/null; then
                ok "Database 'RecordPlatform' exists"
            else
                fail "Database 'RecordPlatform' does not exist"
                if [ "$FIX_MODE" = true ]; then
                    info "Creating database..."
                    if mysql -h "$host" -P "$port" -uroot -p"$password" \
                        -e "CREATE DATABASE IF NOT EXISTS RecordPlatform CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci" &>/dev/null; then
                        ok "Database 'RecordPlatform' created"
                        FAILURES=$((FAILURES - 1))
                    else
                        fail "Failed to create database"
                    fi
                else
                    info "Run with --fix to auto-create, or manually:"
                    info "  CREATE DATABASE RecordPlatform CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
                fi
            fi
        else
            fail "MySQL authentication failed"
            info "Check DB_PASSWORD in .env (current: ****)"
        fi
    elif tcp_check "$host" "$port"; then
        ok "MySQL port is open"
        warn "mysql CLI not installed - deep check skipped"
        info "Install: brew install mysql-client (macOS) / apt install mysql-client (Linux)"
    else
        fail "Cannot connect to MySQL at $host:$port"
        info "Start with: docker compose -f docker-compose.infra.yml up -d mysql"
    fi
}

# ==============================================================================
# 3. Redis
# ==============================================================================
check_redis() {
    local host="${REDIS_HOST:-localhost}"
    local port="${REDIS_PORT:-6379}"
    local password="${REDIS_PASSWORD:-redis123}"

    section 3 "Redis ($host:$port)"

    if has_cmd redis-cli; then
        local pong
        local redis_exit_code=0
        pong=$(redis-cli -h "$host" -p "$port" -a "$password" --no-auth-warning PING 2>/dev/null) || redis_exit_code=$?
        if [ "$redis_exit_code" -eq 0 ] && [ "$pong" = "PONG" ]; then
            ok "Redis PING -> PONG (authenticated)"
        else
            fail "Redis PING failed (got: ${pong:-empty})"
            info "Check REDIS_PASSWORD in .env"
        fi
    elif tcp_check "$host" "$port"; then
        ok "Redis port is open"
        warn "redis-cli not installed - deep check skipped"
        info "Install: brew install redis (macOS) / apt install redis-tools (Linux)"
    else
        fail "Cannot connect to Redis at $host:$port"
        info "Start with: docker compose -f docker-compose.infra.yml up -d redis"
    fi
}

# ==============================================================================
# 4. RabbitMQ
# ==============================================================================
check_rabbitmq() {
    local host="${RABBITMQ_HOST:-localhost}"
    local port="${RABBITMQ_PORT:-5672}"
    local mgmt_port="${RABBITMQ_MANAGEMENT_PORT:-15672}"
    local user="${RABBITMQ_USERNAME:-rabbitmq}"
    local pass="${RABBITMQ_PASSWORD:-rabbitmq}"

    section 4 "RabbitMQ ($host:$port)"

    # AMQP port
    if tcp_check "$host" "$port"; then
        ok "RabbitMQ AMQP port is open"
    else
        fail "Cannot connect to RabbitMQ at $host:$port"
        info "Start with: docker compose -f docker-compose.infra.yml up -d rabbitmq"
        return
    fi

    # Management API
    local resp
    resp=$(curl -sf --max-time 5 -u "$user:$pass" "http://$host:$mgmt_port/api/overview" 2>/dev/null || true)
    if [ -n "$resp" ]; then
        local version
        version=$(echo "$resp" | grep -o '"rabbitmq_version":"[^"]*"' | cut -d'"' -f4)
        ok "Management API accessible (version: ${version:-unknown})"
    else
        warn "Management API not accessible at :$mgmt_port (AMQP is fine)"
        info "Management plugin may not be enabled, or credentials are wrong"
    fi
}

# ==============================================================================
# 5. FISCO BCOS
# ==============================================================================
check_fisco() {
    local peer="${FISCO_PEER_ADDRESS:-127.0.0.1:20200}"
    local host="${peer%%:*}"
    local port="${peer##*:}"

    section 5 "FISCO BCOS ($host:$port)"

    if tcp_check "$host" "$port"; then
        ok "FISCO BCOS node is reachable"
    else
        fail "Cannot connect to FISCO BCOS at $host:$port"
        info "FISCO BCOS cannot be started via docker-compose."
        info "Follow the official setup guide:"
        info "  https://fisco-bcos-doc.readthedocs.io/zh-cn/latest/docs/quick_start/air_installation.html"
        info "Key steps: build_chain.sh -> start nodes -> copy SDK certs"
    fi
}

# ==============================================================================
# 6. S3 / MinIO
# ==============================================================================
check_s3() {
    local endpoint="${S3_ENDPOINT:-http://localhost:9000}"
    local access_key="${S3_ACCESS_KEY:-minioadmin}"
    local secret_key="${S3_SECRET_KEY:-minioadmin}"
    local bucket="${S3_BUCKET_NAME:-forum}"

    section 6 "S3/MinIO ($endpoint)"

    # Health check
    if curl -sf --max-time 5 "$endpoint/minio/health/live" &>/dev/null; then
        ok "MinIO health endpoint is live"
    else
        local endpoint_status="000"
        # Root path on S3-compatible services often returns 403/405 when reachable.
        endpoint_status=$(curl -sS -o /dev/null --max-time 5 -w "%{http_code}" "$endpoint" || true)
        if [ "$endpoint_status" != "000" ]; then
            ok "S3 endpoint is reachable (HTTP $endpoint_status)"
        else
            fail "Cannot reach S3 endpoint at $endpoint"
            info "Start with: docker compose -f docker-compose.infra.yml up -d minio-a minio-b"
            return
        fi
    fi

    # Check bucket
    local bucket_found=false
    local mc_alias="envcheck_${$}"
    if has_cmd aws; then
        if AWS_ACCESS_KEY_ID="$access_key" AWS_SECRET_ACCESS_KEY="$secret_key" \
            aws --endpoint-url "$endpoint" s3 ls "s3://$bucket" &>/dev/null; then
            bucket_found=true
        fi
    elif has_cmd mc; then
        if mc alias set "$mc_alias" "$endpoint" "$access_key" "$secret_key" &>/dev/null; then
            if mc ls "$mc_alias/$bucket" &>/dev/null; then
                bucket_found=true
            fi
        else
            warn "Failed to configure mc alias for endpoint"
        fi
        mc alias rm "$mc_alias" &>/dev/null 2>&1 || true
    else
        warn "Neither aws CLI nor mc installed - bucket check skipped"
        info "Install: brew install awscli (macOS) / pip install awscli"
        return
    fi

    if [ "$bucket_found" = true ]; then
        ok "Bucket '$bucket' exists"
    else
        fail "Bucket '$bucket' not found"
        if [ "$FIX_MODE" = true ]; then
            info "Creating bucket '$bucket'..."
            local created=false
            if has_cmd aws; then
                if AWS_ACCESS_KEY_ID="$access_key" AWS_SECRET_ACCESS_KEY="$secret_key" \
                    aws --endpoint-url "$endpoint" s3 mb "s3://$bucket" &>/dev/null; then
                    created=true
                fi
            elif has_cmd mc; then
                if mc alias set "$mc_alias" "$endpoint" "$access_key" "$secret_key" &>/dev/null; then
                    if mc mb "$mc_alias/$bucket" &>/dev/null; then
                        created=true
                    fi
                else
                    warn "Failed to configure mc alias for endpoint"
                fi
                mc alias rm "$mc_alias" &>/dev/null 2>&1 || true
            fi
            if [ "$created" = true ]; then
                ok "Bucket '$bucket' created"
                FAILURES=$((FAILURES - 1))
            else
                fail "Failed to create bucket"
            fi
        else
            info "Run with --fix to auto-create, or manually:"
            info "  aws --endpoint-url $endpoint s3 mb s3://$bucket"
        fi
    fi
}

# ==============================================================================
# 7. FISCO TLS Certificates
# ==============================================================================
check_certs() {
    local cert_path="${FISCO_CERT_PATH:-conf}"
    # Resolve relative path from project root
    if [[ "$cert_path" != /* ]]; then
        cert_path="$PROJECT_ROOT/platform-fisco/src/main/resources/$cert_path"
    fi

    section 7 "FISCO TLS Certificates ($cert_path)"

    local all_found=true
    for cert_file in ca.crt sdk.crt sdk.key; do
        if [ -f "$cert_path/$cert_file" ]; then
            ok "Found: $cert_file"
            # Check expiry for .crt files
            if [[ "$cert_file" == *.crt ]] && has_cmd openssl; then
                if openssl x509 -checkend 2592000 -noout -in "$cert_path/$cert_file" &>/dev/null; then
                    ok "  $cert_file valid for > 30 days"
                else
                    warn "$cert_file expires within 30 days or is already expired"
                fi
            fi
        else
            fail "Missing: $cert_path/$cert_file"
            all_found=false
        fi
    done

    if [ "$all_found" = false ]; then
        info "Copy SDK certificates from FISCO BCOS node:"
        info "  cp nodes/127.0.0.1/sdk/* $cert_path/"
    fi
}

# ==============================================================================
# 8. Contract Addresses
# ==============================================================================
check_contracts() {
    section 8 "Smart Contract Addresses"

    local storage_addr="${FISCO_STORAGE_CONTRACT:-}"
    local sharing_addr="${FISCO_SHARING_CONTRACT:-}"

    for name_addr in "Storage:$storage_addr" "Sharing:$sharing_addr"; do
        local name="${name_addr%%:*}"
        local addr="${name_addr##*:}"

        if [ -z "$addr" ]; then
            local env_key
            env_key=$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')
            fail "$name contract address is empty (FISCO_${env_key}_CONTRACT)"
            info "Deploy contracts first, then set address in .env"
        elif [[ "$addr" =~ ^0x[0-9a-fA-F]{40}$ ]]; then
            ok "$name contract: ${addr:0:10}...${addr: -4}"
        else
            fail "$name contract address invalid: $addr (expected 0x + 40 hex chars)"
        fi
    done
}

# ==============================================================================
# Main
# ==============================================================================

echo "${BOLD}RecordPlatform Environment Check${RESET}"
echo "Project root: $PROJECT_ROOT"
echo "Fix mode: $FIX_MODE"
if [ -f "$PROJECT_ROOT/.env" ]; then
    ok ".env file found"
else
    warn ".env file not found - using defaults"
    info "Run: cp .env.example .env"
fi

if [ -n "$TARGET_SERVICE" ]; then
    TOTAL_CHECKS=1
    case "$TARGET_SERVICE" in
        nacos)     check_nacos ;;
        mysql)     check_mysql ;;
        redis)     check_redis ;;
        rabbitmq)  check_rabbitmq ;;
        fisco)     check_fisco ;;
        s3|minio)  check_s3 ;;
        certs)     check_certs ;;
        contracts) check_contracts ;;
        *) echo "Unknown service: $TARGET_SERVICE"; exit 1 ;;
    esac
else
    check_nacos
    check_mysql
    check_redis
    check_rabbitmq
    check_fisco
    check_s3
    check_certs
    check_contracts
fi

# ==============================================================================
# Summary
# ==============================================================================
echo
echo "${BOLD}========================================${RESET}"
if [ $FAILURES -eq 0 ] && [ $WARNINGS -eq 0 ]; then
    echo "${GREEN}${BOLD}All checks passed!${RESET}"
elif [ $FAILURES -eq 0 ]; then
    echo "${YELLOW}${BOLD}Passed with $WARNINGS warning(s)${RESET}"
else
    echo "${RED}${BOLD}$FAILURES check(s) failed${RESET}, $WARNINGS warning(s)"
fi
echo "${BOLD}========================================${RESET}"

if [ $FAILURES -gt 0 ]; then
    exit 1
fi
exit 0
