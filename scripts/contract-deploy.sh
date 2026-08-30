#!/usr/bin/env bash
# ==============================================================================
# RecordPlatform Smart Contract Lifecycle Tool
# ==============================================================================
#
# Automates the guarded FISCO BCOS contract lifecycle:
#   1. Pre-flight checks       - verify catalog, tools, console and exact chain/group
#   2. Compile                 - produce fresh Storage/Sharing ABI and BIN
#   3. Artifact verification   - compare compiled outputs with signed artifacts
#   4. Deploy                  - capture address, transaction hash and block number
#   5. On-chain verification   - match signed runtime bytes and exact catalog identities
#   6. Audited activation      - publish a receipt, then atomically activate complete evidence
#
# Usage:
#   ./scripts/contract-deploy.sh [options]
#
# Options:
#   --console-dir DIR    FISCO BCOS console directory (default: ~/fisco/console)
#   --console-launcher FILE
#                        Interactive launcher: start.sh or legacy console.sh
#   --env-file FILE      Target .env file for atomic activation (default: <project>/.env)
#   --catalog-file FILE  Artifact catalog (default: platform-fisco/.../artifacts.json)
#   --receipt-dir DIR    Deployment receipt directory (default: <project>/log/contract-deployments)
#   --dry-run            Validate inputs and print actions without changing files or chain state
#   -h, --help           Show this help message
#
# Exit codes:
#   0 = success
#   1 = one or more phases failed
#
# ==============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# ==============================================================================
# Color & formatting  (identical style to env-check.sh)
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

ok()      { echo "  ${GREEN}[OK]${RESET}   $1"; }
fail()    { echo "  ${RED}[FAIL]${RESET} $1"; FAILURES=$((FAILURES + 1)); }
warn()    { echo "  ${YELLOW}[WARN]${RESET} $1"; }
info()    { echo "  ${CYAN}[INFO]${RESET} $1"; }
section() { echo; echo "${BOLD}[Phase $1/$TOTAL_PHASES] $2${RESET}"; }
dry()     { echo "  ${CYAN}[DRY-RUN]${RESET} $1"; }

FAILURES=0
TOTAL_PHASES=6

# ==============================================================================
# Load .env (same safe parser as env-check.sh - treats content as data)
# ==============================================================================
load_env_file() {
    local env_file="$1"
    [ -f "$env_file" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*$ ]] && continue
        [[ "$line" =~ ^[[:space:]]*# ]] && continue
        if [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z_0-9]*)=(.*)$ ]]; then
            local key="${BASH_REMATCH[1]}"
            local value="${BASH_REMATCH[2]}"
            value="${value#"${value%%[![:space:]]*}"}"
            if [[ "$value" =~ ^\"(.*)\"[[:space:]]*(#.*)?$ ]]; then
                value="${BASH_REMATCH[1]}"
            elif [[ "$value" =~ ^\'(.*)\'[[:space:]]*(#.*)?$ ]]; then
                value="${BASH_REMATCH[1]}"
            else
                value="${value%%[[:space:]]#*}"
                value="${value%"${value##*[![:space:]]}"}"
            fi
            if is_contract_env_key_allowed "$key"; then
                export "${key}=${value}"
            fi
        fi
    done < "$env_file"
}

is_contract_env_key_allowed() {
    case "$1" in
        FISCO_*|BSN_*|BLOCKCHAIN_*|CONTRACT_*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

run_console() {
    # 在清理 shell 注入变量后，以有界时长运行交互式 FISCO Console。
    local timeout_seconds="$1"
    env -u BASH_ENV -u ENV -u SHELLOPTS -u CDPATH \
        "$TIMEOUT_COMMAND" "$timeout_seconds" "$CONSOLE_LAUNCHER"
}

# TCP connectivity probe (bash /dev/tcp → nc fallback, same as env-check.sh)
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

has_cmd() { command -v "$1" &>/dev/null; }

# ==============================================================================
# Argument parsing
# ==============================================================================
CONSOLE_DIR="${FISCO_CONSOLE_DIR:-$HOME/fisco/console}"
ENV_CONSOLE_LAUNCHER_OVERRIDE="${FISCO_CONSOLE_LAUNCHER:-}"
CLI_CONSOLE_LAUNCHER_OVERRIDE=""
ENV_FILE=""
CATALOG_FILE=""
RECEIPT_DIR=""
DRY_RUN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --console-dir)
            if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "Option --console-dir requires a directory path"; exit 1
            fi
            CONSOLE_DIR="$2"; shift 2 ;;
        --console-launcher)
            if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "Option --console-launcher requires a launcher path"; exit 1
            fi
            if [ -n "$CLI_CONSOLE_LAUNCHER_OVERRIDE" ] \
                && [ "$CLI_CONSOLE_LAUNCHER_OVERRIDE" != "$2" ]; then
                echo "Option --console-launcher is ambiguous when specified with different paths"
                exit 1
            fi
            CLI_CONSOLE_LAUNCHER_OVERRIDE="$2"; shift 2 ;;
        --env-file)
            if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "Option --env-file requires a file path"; exit 1
            fi
            ENV_FILE="$2"; shift 2 ;;
        --catalog-file)
            if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "Option --catalog-file requires a file path"; exit 1
            fi
            CATALOG_FILE="$2"; shift 2 ;;
        --receipt-dir)
            if [[ $# -lt 2 || -z "${2:-}" || "${2:-}" == --* ]]; then
                echo "Option --receipt-dir requires a directory path"; exit 1
            fi
            RECEIPT_DIR="$2"; shift 2 ;;
        --skip-verify)
            echo "Option --skip-verify is no longer supported; on-chain verification is mandatory"
            exit 1 ;;
        --dry-run)     DRY_RUN=true; shift ;;
        -h|--help)
            sed -n '/^# Usage:/,/^# =====/{/^# =====/!p}' "$0" | sed 's/^# \{0,2\}//'
            exit 0 ;;
        *) echo "Unknown option: $1"; echo "Run $0 --help for usage."; exit 1 ;;
    esac
done

# Resolve default .env target after arg parsing
if [ -z "$ENV_FILE" ]; then
    ENV_FILE="$PROJECT_ROOT/.env"
fi
if [ -z "$CATALOG_FILE" ]; then
    CATALOG_FILE="$PROJECT_ROOT/platform-fisco/src/main/resources/contract-registry/artifacts.json"
fi

# Load current .env for FISCO config (node address, etc.)
load_env_file "$ENV_FILE"

if [ -n "${FISCO_CONSOLE_LAUNCHER:-}" ]; then
    if [ -n "$ENV_CONSOLE_LAUNCHER_OVERRIDE" ] \
        && [ "$ENV_CONSOLE_LAUNCHER_OVERRIDE" != "$FISCO_CONSOLE_LAUNCHER" ]; then
        echo "FISCO_CONSOLE_LAUNCHER is ambiguous between the process environment and .env"
        exit 1
    fi
    ENV_CONSOLE_LAUNCHER_OVERRIDE="$FISCO_CONSOLE_LAUNCHER"
fi
if [ -n "$CLI_CONSOLE_LAUNCHER_OVERRIDE" ] \
    && [ -n "$ENV_CONSOLE_LAUNCHER_OVERRIDE" ] \
    && [ "$CLI_CONSOLE_LAUNCHER_OVERRIDE" != "$ENV_CONSOLE_LAUNCHER_OVERRIDE" ]; then
    echo "Console launcher override is ambiguous between CLI and environment configuration"
    exit 1
fi

# Resolve the receipt directory after loading .env so operators can configure it there.
if [ -z "$RECEIPT_DIR" ]; then
    RECEIPT_DIR="${CONTRACT_DEPLOYMENT_RECEIPT_DIR:-$PROJECT_ROOT/log/contract-deployments}"
fi
if [[ "$RECEIPT_DIR" != /* ]]; then
    RECEIPT_DIR="$PROJECT_ROOT/$RECEIPT_DIR"
fi

# Enable errexit after environment loading
set -e

# ==============================================================================
# Paths
# ==============================================================================
CONTRACT_SRC_DIR="$PROJECT_ROOT/platform-fisco/contract"
ABI_DEST_DIR="$PROJECT_ROOT/platform-fisco/src/main/resources/abi"
BIN_DEST_DIR="$PROJECT_ROOT/platform-fisco/src/main/resources/bin"
FINGERPRINT_TOOL="$PROJECT_ROOT/tools/contracts/contract_fingerprint.py"
SOLC_VERSION="0.8.11"
SOLC_BUILD_ID="0.8.11+commit.6b4cc280"
CONSOLE_CONTRACT_DIR="$CONSOLE_DIR/contracts/solidity"
CONSOLE_SDK_DIR="$CONSOLE_DIR/contracts/sdk"
ECC_SOLC="$HOME/.fisco/solc/$SOLC_VERSION/keccak256/solc"
SM_SOLC="$HOME/.fisco/solc/$SOLC_VERSION/sm3/solc"
REPRO_BUILD_DIR=""
ECC_REPRO_BUILD_DIR=""
SM_REPRO_BUILD_DIR=""
STAGED_SOURCE_TEMP=""
STAGED_SOURCE_DIRECTORY=""
STAGED_SOURCE_DIRECTORY_DEVICE=""
STAGED_SOURCE_DIRECTORY_INODE=""
STAGED_SOURCE_TEMP_NAME=""
STAGED_SOURCE_TEMP_DEVICE=""
STAGED_SOURCE_TEMP_INODE=""
REPRO_TEMP_ROOT="${TMPDIR:-/tmp}"
FISCO_CRYPTO_VARIANT=""
CONSOLE_LAUNCHER=""

if command -v timeout >/dev/null 2>&1; then
    TIMEOUT_COMMAND="$(command -v timeout)"
elif command -v gtimeout >/dev/null 2>&1; then
    TIMEOUT_COMMAND="$(command -v gtimeout)"
else
    TIMEOUT_COMMAND=""
fi

# FISCO BCOS node address from .env (fallback to default)
FISCO_HOST="${FISCO_PEER_ADDRESS:-127.0.0.1:20200}"
FISCO_NODE_HOST="${FISCO_HOST%%:*}"
FISCO_NODE_PORT="${FISCO_HOST##*:}"

# Resolve one safe interactive launcher before any Console command is executed.
resolve_console_launcher() {
    local override="${CLI_CONSOLE_LAUNCHER_OVERRIDE:-$ENV_CONSOLE_LAUNCHER_OVERRIDE}"
    local candidate=""
    local console_directory_physical=""
    local candidate_directory_physical=""

    if ! console_directory_physical=$(cd -P "$CONSOLE_DIR" 2>/dev/null && pwd); then
        fail "Cannot resolve Console directory: $CONSOLE_DIR"
        return 1
    fi

    if [ -n "$override" ]; then
        case "$override" in
            /*) candidate="$override" ;;
            *) candidate="$CONSOLE_DIR/$override" ;;
        esac
    elif [ -e "$CONSOLE_DIR/start.sh" ] || [ -L "$CONSOLE_DIR/start.sh" ]; then
        candidate="$CONSOLE_DIR/start.sh"
    else
        candidate="$CONSOLE_DIR/console.sh"
    fi

    case "${candidate##*/}" in
        start.sh|console.sh) ;;
        *)
            fail "Console launcher must be named start.sh or console.sh: $candidate"
            return 1
            ;;
    esac
    if [ -L "$candidate" ]; then
        fail "Console launcher must not be a symbolic link: $candidate"
        return 1
    fi
    if [ ! -f "$candidate" ]; then
        fail "Console launcher is not a regular file: $candidate"
        return 1
    fi
    if [ ! -x "$candidate" ]; then
        fail "Console launcher is not executable: $candidate"
        return 1
    fi
    if ! candidate_directory_physical=$(cd -P "$(dirname "$candidate")" 2>/dev/null && pwd); then
        fail "Cannot resolve Console launcher directory: $candidate"
        return 1
    fi
    if [ "$candidate_directory_physical" != "$console_directory_physical" ]; then
        fail "Console launcher must be located directly inside the Console directory: $candidate"
        return 1
    fi

    CONSOLE_LAUNCHER="$candidate_directory_physical/${candidate##*/}"
    ok "Interactive Console launcher resolved: ${candidate##*/}"
}

# 从官方 getGroupInfo JSON 输出中提取唯一的 chain/group/crypto/VM 组合。
extract_fisco_chain_identity() {
    python3 -c '
import json
import re
import sys

text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", sys.stdin.read())
decoder = json.JSONDecoder()
candidates = set()
invalid_identity_metadata = False

def collect(value):
    global invalid_identity_metadata
    if isinstance(value, dict):
        identity_fields = {"chainID", "groupID", "smCryptoType", "wasm", "isWasm"}
        if identity_fields.intersection(value):
            chain_id = value.get("chainID")
            group_id = value.get("groupID")
            sm_crypto = value.get("smCryptoType")
            has_legacy_wasm = "wasm" in value
            has_current_wasm = "isWasm" in value
            legacy_wasm = value.get("wasm")
            current_wasm = value.get("isWasm")
            wasm_aliases_valid = (
                (has_legacy_wasm or has_current_wasm)
                and (not has_legacy_wasm or isinstance(legacy_wasm, bool))
                and (not has_current_wasm or isinstance(current_wasm, bool))
                and not (
                    has_legacy_wasm
                    and has_current_wasm
                    and legacy_wasm != current_wasm
                )
            )
            if (
                isinstance(chain_id, str)
                and isinstance(group_id, str)
                and isinstance(sm_crypto, bool)
                and wasm_aliases_valid
            ):
                if chain_id and group_id and not any(
                    character.isspace() for character in chain_id + group_id
                ):
                    wasm = current_wasm if has_current_wasm else legacy_wasm
                    candidates.add((
                        chain_id,
                        group_id,
                        "sm" if sm_crypto else "ecc",
                        "wasm" if wasm else "evm",
                    ))
                else:
                    invalid_identity_metadata = True
            elif (has_legacy_wasm or has_current_wasm) and not wasm_aliases_valid:
                invalid_identity_metadata = True
        for nested in value.values():
            collect(nested)
    elif isinstance(value, list):
        for nested in value:
            collect(nested)
    elif isinstance(value, str):
        try:
            nested = json.loads(value)
        except json.JSONDecodeError:
            return
        if isinstance(nested, (dict, list)):
            collect(nested)

for index, character in enumerate(text):
    if character not in "[{":
        continue
    try:
        value, _ = decoder.raw_decode(text[index:])
    except json.JSONDecodeError:
        continue
    collect(value)

if invalid_identity_metadata or len(candidates) != 1:
    raise SystemExit(1)
chain_id, group_id, crypto_variant, vm_type = next(iter(candidates))
print(f"{chain_id}\t{group_id}\t{crypto_variant}\t{vm_type}")
'
}

# 计算 catalog 原始文件 bytes 的版本化审计 SHA-256。
calculate_catalog_sha256() {
    python3 - "$CATALOG_FILE" <<'PY'
import hashlib
import sys
from pathlib import Path

print("sha256:" + hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
PY
}

# 将已解析的节点身份与显式 chain/group 及固定 EVM crypto 变体完全对账。
validate_observed_fisco_chain_identity() {
    local actual_identity="$1"
    local expected_chain_id="${FISCO_CHAIN_ID:-}"
    local expected_group_id="${FISCO_GROUP_ID:-}"
    local actual_chain_id
    local actual_group_id
    local actual_crypto_variant
    local actual_vm_type
    IFS=$'\t' read -r actual_chain_id actual_group_id \
        actual_crypto_variant actual_vm_type <<< "$actual_identity"
    if [ "$actual_chain_id" != "$expected_chain_id" ]; then
        fail "FISCO chain mismatch: configured=$expected_chain_id, actual=$actual_chain_id"
        return 1
    fi
    if [ "$actual_group_id" != "$expected_group_id" ]; then
        fail "FISCO group mismatch: configured=$expected_group_id, actual=$actual_group_id"
        return 1
    fi
    if [ "$actual_vm_type" != "evm" ]; then
        fail "FISCO WASM group cannot deploy EVM contract artifacts"
        return 1
    fi
    if [ "$actual_crypto_variant" != "ecc" ] && [ "$actual_crypto_variant" != "sm" ]; then
        fail "Unsupported FISCO crypto variant: $actual_crypto_variant"
        return 1
    fi
    if [ -n "$FISCO_CRYPTO_VARIANT" ] \
        && [ "$FISCO_CRYPTO_VARIANT" != "$actual_crypto_variant" ]; then
        fail "FISCO crypto variant changed during deployment: expected=$FISCO_CRYPTO_VARIANT, actual=$actual_crypto_variant"
        return 1
    fi
    FISCO_CRYPTO_VARIANT="$actual_crypto_variant"
    ok "FISCO node identity matches chain/group; crypto=$actual_crypto_variant, vm=evm"
}

# 部署前查询节点身份，并与显式 chain/group 及 EVM crypto 变体完全对账。
verify_fisco_chain_identity() {
    local active_chain="${BLOCKCHAIN_ACTIVE:-local-fisco}"
    local expected_chain_id="${FISCO_CHAIN_ID:-}"
    local expected_group_id="${FISCO_GROUP_ID:-}"
    if [ "$active_chain" != "local-fisco" ]; then
        fail "contract-deploy.sh supports only BLOCKCHAIN_ACTIVE=local-fisco (configured=$active_chain)"
        return 1
    fi
    if [[ ! "$expected_chain_id" =~ ^[A-Za-z0-9._:-]+$ ]]; then
        fail "FISCO_CHAIN_ID must be explicitly configured with a valid non-empty value"
        return 1
    fi
    if [[ ! "$expected_group_id" =~ ^[A-Za-z0-9._:-]+$ ]]; then
        fail "FISCO_GROUP_ID must be explicitly configured with a valid non-empty value"
        return 1
    fi

    if [ "$DRY_RUN" = true ]; then
        dry "getGroupInfo and compare chain/group/crypto/VM with configured EVM deployment"
        ok "Dry-run: node identity query skipped; deployment would require exact EVM metadata"
        return 0
    fi

    local output
    if ! output=$(
        cd "$CONSOLE_DIR"
        printf 'getGroupInfo\nexit\n' | run_console 30 2>&1
    ); then
        fail "getGroupInfo command failed before deployment"
        printf '%s\n' "$output" | tail -10 | sed 's/^/    /'
        return 1
    fi

    local actual_identity
    if ! actual_identity=$(printf '%s\n' "$output" | extract_fisco_chain_identity); then
        fail "getGroupInfo did not return one valid chain/group/crypto/VM tuple"
        printf '%s\n' "$output" | tail -10 | sed 's/^/    /'
        return 1
    fi
    validate_observed_fisco_chain_identity "$actual_identity"
}

# ==============================================================================
# Header
# ==============================================================================
echo
echo "${BOLD}RecordPlatform Smart Contract Deployment${RESET}"
echo "Project root  : $PROJECT_ROOT"
echo "Console dir   : $CONSOLE_DIR"
echo "Env file      : $ENV_FILE"
echo "Catalog file  : $CATALOG_FILE"
echo "Receipt dir   : $RECEIPT_DIR"
echo "Solidity      : solc $SOLC_VERSION"
if [ "$DRY_RUN" = true ]; then
    echo "${YELLOW}${BOLD}DRY-RUN mode — no changes will be made${RESET}"
fi

# ==============================================================================
# Phase 1: Pre-flight Checks
# ==============================================================================
section 1 "Pre-flight Checks"

# 1a. Console directory exists
if [ -d "$CONSOLE_DIR" ]; then
    ok "Console directory found: $CONSOLE_DIR"
else
    fail "Console directory not found: $CONSOLE_DIR"
    info "Install FISCO BCOS console:"
    info "  cd ~/fisco && curl -#LO https://osp-1257653870.cos.ap-guangzhou.myqcloud.com/FISCO-BCOS/fisco-bcos/releases/v3.8.0/console.tar.gz"
    info "  tar -xzf console.tar.gz"
    info "Or specify a custom path: --console-dir /path/to/console"
    echo
    echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
    exit 1
fi

# 1b. One safe interactive launcher is available
if ! resolve_console_launcher; then
    info "Install an executable regular start.sh, or use a legacy console.sh only when start.sh is absent"
    echo
    echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
    exit 1
fi

# 1c. Required local tools and version-controlled registry are valid
for command_name in bash python3 cmp awk mktemp mv chmod date; do
    if has_cmd "$command_name"; then
        ok "Required command found: $command_name"
    else
        fail "Required command missing: $command_name"
    fi
done
if [ -n "$TIMEOUT_COMMAND" ]; then
    ok "Timeout command found: $TIMEOUT_COMMAND"
else
    fail "Neither timeout nor gtimeout is available"
fi
if [ -f "$CONSOLE_DIR/contract2java.sh" ]; then
    ok "contract2java.sh found"
else
    fail "contract2java.sh not found: $CONSOLE_DIR/contract2java.sh"
fi
if [ -f "$FINGERPRINT_TOOL" ] && [ -f "$CATALOG_FILE" ]; then
    if python3 "$FINGERPRINT_TOOL" verify \
        --project-root "$PROJECT_ROOT" \
        --catalog "$CATALOG_FILE"; then
        ok "Signed contract artifact catalog verified"
    else
        fail "Contract artifact catalog or signed artifacts drifted"
    fi
else
    fail "Fingerprint tool or artifact catalog is missing"
fi

if [ $FAILURES -gt 0 ]; then
    echo
    echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
    exit 1
fi

if [ "$DRY_RUN" = false ]; then
    if [ -f "$ENV_FILE" ] && [ ! -L "$ENV_FILE" ]; then
        ok "Activation target is an existing regular file: $ENV_FILE"
    else
        fail "Activation target must exist and must not be a symlink: $ENV_FILE"
        echo
        echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
        exit 1
    fi
fi

# 1d. FISCO BCOS node is reachable
if [ "$DRY_RUN" = true ]; then
    dry "tcp_check $FISCO_NODE_HOST $FISCO_NODE_PORT"
    ok "Dry-run: node connectivity check skipped ($FISCO_NODE_HOST:$FISCO_NODE_PORT)"
elif tcp_check "$FISCO_NODE_HOST" "$FISCO_NODE_PORT"; then
    ok "FISCO BCOS node is reachable ($FISCO_NODE_HOST:$FISCO_NODE_PORT)"
else
    fail "Cannot connect to FISCO BCOS node at $FISCO_NODE_HOST:$FISCO_NODE_PORT"
    info "Check FISCO_PEER_ADDRESS in $ENV_FILE"
    info "Start nodes: cd ~/fisco/nodes && bash start_all.sh"
    echo
    echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
    exit 1
fi

# 1e. Node chain/group identity matches the deployment target
if ! verify_fisco_chain_identity; then
    echo
    echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
    exit 1
fi

# 1f. Source contracts exist
for sol in Storage.sol Sharing.sol; do
    if [ -f "$CONTRACT_SRC_DIR/$sol" ]; then
        ok "Source contract found: platform-fisco/contract/$sol"
    else
        fail "Source contract missing: $CONTRACT_SRC_DIR/$sol"
    fi
done

if [ $FAILURES -gt 0 ]; then
    echo
    echo "${RED}${BOLD}Pre-flight failed — cannot continue.${RESET}"
    exit 1
fi

# ==============================================================================
# Phase 2: Compile Contracts
# ==============================================================================
section 2 "Compile Contracts"

# 在 FISCO Console 目录中按 ABI/ECC/SM 精确定位编译产物，禁止跨变体回退。
find_console_artifact() {
    local kind="$1"
    local variant="$2"
    local name="$3"
    local candidate
    local candidates=()
    if [ "$kind" = "abi" ]; then
        candidates=(
            "$CONSOLE_SDK_DIR/abi/$name.abi"
            "$CONSOLE_SDK_DIR/abi/sm/$name.abi"
            "$CONSOLE_SDK_DIR/$name.abi"
        )
    elif [ "$variant" = "ecc" ]; then
        candidates=(
            "$CONSOLE_SDK_DIR/bin/$name.bin"
            "$CONSOLE_SDK_DIR/$name.bin"
        )
    elif [ "$variant" = "sm" ]; then
        candidates=(
            "$CONSOLE_SDK_DIR/bin/sm/$name.bin"
            "$CONSOLE_SDK_DIR/sm/$name.bin"
        )
    else
        return 1
    fi
    for candidate in "${candidates[@]}"; do
        if [ -s "$candidate" ] && [ ! -L "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# 校验固定输出目录中的 Console artifact 确实由本次编译刷新。
artifact_is_fresh() {
    local artifact_path="$1"
    local started_at_ns="$2"
    python3 - "$artifact_path" "$started_at_ns" <<'PY'
import pathlib
import sys

artifact = pathlib.Path(sys.argv[1])
started_at_ns = int(sys.argv[2])
raise SystemExit(0 if artifact.stat().st_mtime_ns >= started_at_ns else 1)
PY
}

# 拒绝 Console 固定源码、输出目录或候选 artifact 通过符号链接重定向写入。
validate_console_compile_paths() {
    local name="$1"
    local path
    for path in \
        "$CONSOLE_DIR/contracts" \
        "$CONSOLE_CONTRACT_DIR" \
        "$CONSOLE_SDK_DIR" \
        "$CONSOLE_SDK_DIR/abi" \
        "$CONSOLE_SDK_DIR/abi/sm" \
        "$CONSOLE_SDK_DIR/bin" \
        "$CONSOLE_SDK_DIR/bin/sm" \
        "$CONSOLE_SDK_DIR/sm" \
        "$CONSOLE_CONTRACT_DIR/$name.sol" \
        "$CONSOLE_SDK_DIR/abi/$name.abi" \
        "$CONSOLE_SDK_DIR/abi/sm/$name.abi" \
        "$CONSOLE_SDK_DIR/$name.abi" \
        "$CONSOLE_SDK_DIR/bin/$name.bin" \
        "$CONSOLE_SDK_DIR/$name.bin" \
        "$CONSOLE_SDK_DIR/bin/sm/$name.bin" \
        "$CONSOLE_SDK_DIR/sm/$name.bin"; do
        if [ -L "$path" ]; then
            fail "$name Console compile path must not be a symlink: $path"
            return 1
        fi
    done
}

# 在已验证的 Console 源码目录中创建私有临时普通文件，并记录目录/文件身份。
prepare_staged_source_temp() {
    local name="$1"
    local metadata
    if ! metadata=$(python3 - "$CONSOLE_CONTRACT_DIR" "$name" <<'PY'
import os
import re
import secrets
import stat
import sys
from pathlib import Path

requested_directory = Path(sys.argv[1])
contract_name = sys.argv[2]
if contract_name not in {"Storage", "Sharing"}:
    raise SystemExit(1)

directory_flags = os.O_RDONLY
directory_flags |= getattr(os, "O_DIRECTORY", 0)
directory_flags |= getattr(os, "O_NOFOLLOW", 0)
requested_fd = os.open(requested_directory, directory_flags)
try:
    requested_metadata = os.fstat(requested_fd)
    if not stat.S_ISDIR(requested_metadata.st_mode):
        raise SystemExit(1)
    canonical_directory = requested_directory.resolve(strict=True)
    canonical_fd = os.open(canonical_directory, directory_flags)
    try:
        canonical_metadata = os.fstat(canonical_fd)
        if (
            canonical_metadata.st_dev != requested_metadata.st_dev
            or canonical_metadata.st_ino != requested_metadata.st_ino
        ):
            raise SystemExit(1)

        temporary_name = None
        temporary_metadata = None
        try:
            for _ in range(32):
                candidate_name = (
                    f".record-platform-{contract_name}.{secrets.token_hex(8)}"
                )
                if re.fullmatch(
                    r"\.record-platform-(?:Storage|Sharing)\.[0-9a-f]{16}",
                    candidate_name,
                ) is None:
                    raise SystemExit(1)
                flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
                flags |= getattr(os, "O_NOFOLLOW", 0)
                try:
                    temporary_fd = os.open(
                        candidate_name,
                        flags,
                        0o600,
                        dir_fd=canonical_fd,
                    )
                except FileExistsError:
                    continue
                temporary_name = candidate_name
                try:
                    os.fchmod(temporary_fd, 0o600)
                    temporary_metadata = os.fstat(temporary_fd)
                    if not stat.S_ISREG(temporary_metadata.st_mode):
                        raise SystemExit(1)
                finally:
                    os.close(temporary_fd)
                break
            if temporary_name is None or temporary_metadata is None:
                raise SystemExit(1)
            print(
                canonical_directory,
                canonical_metadata.st_dev,
                canonical_metadata.st_ino,
                temporary_name,
                temporary_metadata.st_dev,
                temporary_metadata.st_ino,
                sep="\t",
            )
        except BaseException:
            if temporary_name is not None:
                try:
                    os.unlink(temporary_name, dir_fd=canonical_fd)
                except FileNotFoundError:
                    pass
            raise
    finally:
        os.close(canonical_fd)
finally:
    os.close(requested_fd)
PY
    ); then
        fail "$name could not allocate a private Console source staging file"
        return 1
    fi

    IFS=$'\t' read -r \
        STAGED_SOURCE_DIRECTORY \
        STAGED_SOURCE_DIRECTORY_DEVICE \
        STAGED_SOURCE_DIRECTORY_INODE \
        STAGED_SOURCE_TEMP_NAME \
        STAGED_SOURCE_TEMP_DEVICE \
        STAGED_SOURCE_TEMP_INODE <<< "$metadata"
    if [ -z "$STAGED_SOURCE_DIRECTORY" ] \
        || [ -z "$STAGED_SOURCE_DIRECTORY_DEVICE" ] \
        || [ -z "$STAGED_SOURCE_DIRECTORY_INODE" ] \
        || [ -z "$STAGED_SOURCE_TEMP_NAME" ] \
        || [ -z "$STAGED_SOURCE_TEMP_DEVICE" ] \
        || [ -z "$STAGED_SOURCE_TEMP_INODE" ]; then
        fail "$name private Console source staging metadata is incomplete"
        return 1
    fi
    STAGED_SOURCE_TEMP="$STAGED_SOURCE_DIRECTORY/$STAGED_SOURCE_TEMP_NAME"
}

# 通过已捕获的目录身份写入源码并原子替换同目录普通目标，拒绝目录目标语义。
populate_and_replace_staged_source() {
    local name="$1"
    local source_path="$2"
    python3 - \
        "$source_path" \
        "$STAGED_SOURCE_DIRECTORY" \
        "$STAGED_SOURCE_DIRECTORY_DEVICE" \
        "$STAGED_SOURCE_DIRECTORY_INODE" \
        "$STAGED_SOURCE_TEMP_NAME" \
        "$STAGED_SOURCE_TEMP_DEVICE" \
        "$STAGED_SOURCE_TEMP_INODE" \
        "$name.sol" <<'PY'
import os
import re
import stat
import sys
from pathlib import Path

(
    source_path_text,
    directory_path_text,
    expected_directory_device,
    expected_directory_inode,
    temporary_name,
    expected_temporary_device,
    expected_temporary_inode,
    target_name,
) = sys.argv[1:]
if re.fullmatch(
    r"\.record-platform-(?:Storage|Sharing)\.[0-9a-f]{16}",
    temporary_name,
) is None:
    raise SystemExit(1)
if target_name not in {"Storage.sol", "Sharing.sol"}:
    raise SystemExit(1)

directory_flags = os.O_RDONLY
directory_flags |= getattr(os, "O_DIRECTORY", 0)
directory_flags |= getattr(os, "O_NOFOLLOW", 0)
directory_fd = os.open(Path(directory_path_text), directory_flags)
try:
    directory_metadata = os.fstat(directory_fd)
    if (
        directory_metadata.st_dev != int(expected_directory_device)
        or directory_metadata.st_ino != int(expected_directory_inode)
    ):
        raise SystemExit(1)

    temporary_flags = os.O_WRONLY
    temporary_flags |= getattr(os, "O_NOFOLLOW", 0)
    temporary_fd = os.open(temporary_name, temporary_flags, dir_fd=directory_fd)
    try:
        temporary_metadata = os.fstat(temporary_fd)
        if (
            not stat.S_ISREG(temporary_metadata.st_mode)
            or temporary_metadata.st_dev != int(expected_temporary_device)
            or temporary_metadata.st_ino != int(expected_temporary_inode)
        ):
            raise SystemExit(1)
        os.ftruncate(temporary_fd, 0)

        source_flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        source_fd = os.open(Path(source_path_text), source_flags)
        try:
            source_metadata = os.fstat(source_fd)
            if not stat.S_ISREG(source_metadata.st_mode):
                raise SystemExit(1)
            if source_metadata.st_size <= 0 or source_metadata.st_size > 5 * 1024 * 1024:
                raise SystemExit(1)
            source_bytes = bytearray()
            while len(source_bytes) <= 5 * 1024 * 1024:
                chunk = os.read(source_fd, 64 * 1024)
                if not chunk:
                    break
                source_bytes.extend(chunk)
            if not source_bytes or len(source_bytes) > 5 * 1024 * 1024:
                raise SystemExit(1)
        finally:
            os.close(source_fd)

        written = 0
        while written < len(source_bytes):
            write_count = os.write(temporary_fd, source_bytes[written:])
            if write_count <= 0:
                raise SystemExit(1)
            written += write_count
        os.fchmod(temporary_fd, 0o600)
        os.fsync(temporary_fd)
    finally:
        os.close(temporary_fd)

    try:
        target_metadata = os.stat(
            target_name,
            dir_fd=directory_fd,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        target_metadata = None
    if target_metadata is not None and not stat.S_ISREG(target_metadata.st_mode):
        raise SystemExit(1)

    os.replace(
        temporary_name,
        target_name,
        src_dir_fd=directory_fd,
        dst_dir_fd=directory_fd,
    )

    target_flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    target_fd = os.open(target_name, target_flags, dir_fd=directory_fd)
    try:
        target_metadata = os.fstat(target_fd)
        if not stat.S_ISREG(target_metadata.st_mode):
            raise SystemExit(1)
        target_bytes = bytearray()
        while len(target_bytes) <= 5 * 1024 * 1024:
            chunk = os.read(target_fd, 64 * 1024)
            if not chunk:
                break
            target_bytes.extend(chunk)
        if target_bytes != source_bytes:
            raise SystemExit(1)
    finally:
        os.close(target_fd)
finally:
    os.close(directory_fd)
PY
}

# 清空已成功原子替换的源码 staging 跟踪信息。
clear_staged_source_tracking() {
    STAGED_SOURCE_TEMP=""
    STAGED_SOURCE_DIRECTORY=""
    STAGED_SOURCE_DIRECTORY_DEVICE=""
    STAGED_SOURCE_DIRECTORY_INODE=""
    STAGED_SOURCE_TEMP_NAME=""
    STAGED_SOURCE_TEMP_DEVICE=""
    STAGED_SOURCE_TEMP_INODE=""
}

# 通过同目录私有临时普通文件原子替换 Console 源码，禁止跟随 symlink 或目录目标。
stage_contract_source() {
    local name="$1"
    local source_path="$CONTRACT_SRC_DIR/$name.sol"
    local target_path="$CONSOLE_CONTRACT_DIR/$name.sol"
    if ! validate_console_compile_paths "$name"; then
        return 1
    fi
    mkdir -p "$CONSOLE_CONTRACT_DIR"
    if ! validate_console_compile_paths "$name"; then
        return 1
    fi

    if ! prepare_staged_source_temp "$name"; then
        return 1
    fi
    if ! populate_and_replace_staged_source "$name" "$source_path"; then
        fail "$name source could not be atomically staged into FISCO Console"
        return 1
    fi
    clear_staged_source_tracking
    if [ -L "$target_path" ] || ! cmp -s "$source_path" "$target_path"; then
        fail "$name source changed while staging it into FISCO Console"
        return 1
    fi
}

# 复制指定源码，调用 Console 生成 wrapper，并返回 ABI/ECC/SM creation 路径。
compile_contract() {
    local name="$1"
    local abi_result_var="$2"
    local ecc_result_var="$3"
    local sm_result_var="$4"
    if [ "$DRY_RUN" = true ]; then
        dry "copy $name.sol to $CONSOLE_CONTRACT_DIR and run contract2java.sh -v $SOLC_VERSION"
        ok "Dry-run: $name compile would be executed"
        return 0
    fi

    if ! stage_contract_source "$name"; then
        return 1
    fi

    local output
    local compile_started_at
    compile_started_at=$(python3 -c 'import time; print(time.time_ns())')
    if ! output=$(
        cd "$CONSOLE_DIR"
        bash ./contract2java.sh solidity \
            -v "$SOLC_VERSION" \
            -p cn.flying.contract.registry \
            -s "contracts/solidity/$name.sol" 2>&1
    ); then
        fail "$name compilation failed"
        printf '%s\n' "$output" | tail -20 | sed 's/^/    /'
        return 1
    fi
    if ! validate_console_compile_paths "$name"; then
        return 1
    fi

    local abi_path
    local ecc_path
    local sm_path
    if ! abi_path=$(find_console_artifact abi any "$name"); then
        fail "$name compilation did not produce an ABI artifact"
        return 1
    fi
    if ! ecc_path=$(find_console_artifact creation ecc "$name"); then
        fail "$name compilation did not produce an ECC creation artifact"
        return 1
    fi
    if ! sm_path=$(find_console_artifact creation sm "$name"); then
        fail "$name compilation did not produce an independent SM creation artifact"
        return 1
    fi
    for artifact_path in "$abi_path" "$ecc_path" "$sm_path"; do
        if ! artifact_is_fresh "$artifact_path" "$compile_started_at"; then
            fail "$name compilation left a stale artifact: $artifact_path"
            return 1
        fi
    done
    if [ "$ecc_path" = "$sm_path" ]; then
        fail "$name ECC and SM creation artifacts must use distinct paths"
        return 1
    fi
    printf -v "$abi_result_var" '%s' "$abi_path"
    printf -v "$ecc_result_var" '%s' "$ecc_path"
    printf -v "$sm_result_var" '%s' "$sm_path"
    ok "$name compiled to ABI plus independent ECC/SM creation artifacts"
}

# 安全删除本次系统临时目录；只允许固定前缀的直属目录，绝不跟随 symlink。
cleanup_reproducible_build_directory() {
    if [ -z "$REPRO_BUILD_DIR" ]; then
        return 0
    fi
    if [ ! -e "$REPRO_BUILD_DIR" ] && [ ! -L "$REPRO_BUILD_DIR" ]; then
        return 0
    fi
    python3 - "$REPRO_BUILD_DIR" "$REPRO_TEMP_ROOT" <<'PY'
import re
import shutil
import sys
from pathlib import Path

candidate = Path(sys.argv[1]).absolute()
temporary_root = Path(sys.argv[2]).resolve()
if candidate.parent.resolve() != temporary_root:
    raise SystemExit(1)
if re.fullmatch(r"record-platform-contract-build\.[A-Za-z0-9]+", candidate.name) is None:
    raise SystemExit(1)
if candidate.is_symlink() or not candidate.is_dir():
    raise SystemExit(1)
shutil.rmtree(candidate)
PY
}

# 删除仍被跟踪的 Console 私有源码临时文件，严格限制目录、名称和文件类型。
cleanup_staged_source_temp() {
    if [ -z "$STAGED_SOURCE_TEMP_NAME" ]; then
        return 0
    fi
    python3 - \
        "$STAGED_SOURCE_DIRECTORY" \
        "$STAGED_SOURCE_DIRECTORY_DEVICE" \
        "$STAGED_SOURCE_DIRECTORY_INODE" \
        "$STAGED_SOURCE_TEMP_NAME" \
        "$STAGED_SOURCE_TEMP_DEVICE" \
        "$STAGED_SOURCE_TEMP_INODE" <<'PY'
import os
import re
import stat
import sys
from pathlib import Path

(
    source_directory_text,
    expected_directory_device,
    expected_directory_inode,
    temporary_name,
    expected_temporary_device,
    expected_temporary_inode,
) = sys.argv[1:]
if re.fullmatch(
    r"\.record-platform-(?:Storage|Sharing)\.[0-9a-f]{16}",
    temporary_name,
) is None:
    raise SystemExit(1)

directory_flags = os.O_RDONLY
directory_flags |= getattr(os, "O_DIRECTORY", 0)
directory_flags |= getattr(os, "O_NOFOLLOW", 0)
directory_fd = os.open(Path(source_directory_text), directory_flags)
try:
    directory_metadata = os.fstat(directory_fd)
    if (
        directory_metadata.st_dev != int(expected_directory_device)
        or directory_metadata.st_ino != int(expected_directory_inode)
    ):
        raise SystemExit(1)
    try:
        metadata = os.stat(
            temporary_name,
            dir_fd=directory_fd,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        raise SystemExit(0)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_dev != int(expected_temporary_device)
        or metadata.st_ino != int(expected_temporary_inode)
    ):
        raise SystemExit(1)
    os.unlink(temporary_name, dir_fd=directory_fd)
finally:
    os.close(directory_fd)
PY
}

# 保留原始退出状态，并把成功路径上的任一临时制品清理失败视为错误。
finish_contract_deploy() {
    local status=$?
    trap - EXIT
    if ! cleanup_staged_source_temp; then
        warn "Could not safely clean private Console source staging file: $STAGED_SOURCE_TEMP"
        if [ "$status" -eq 0 ]; then
            status=1
        fi
    fi
    if ! cleanup_reproducible_build_directory; then
        warn "Could not safely clean reproducible build directory: $REPRO_BUILD_DIR"
        if [ "$status" -eq 0 ]; then
            status=1
        fi
    fi
    exit "$status"
}

trap finish_contract_deploy EXIT

# 用不可预测的系统临时目录承载本次可复现编译输出，避免并发或永久累积。
prepare_reproducible_build_directory() {
    if [ "$DRY_RUN" = true ]; then
        return 0
    fi
    if [ ! -d "$REPRO_TEMP_ROOT" ] || [ -L "$REPRO_TEMP_ROOT" ]; then
        fail "Temporary build root must be a non-symlink directory: $REPRO_TEMP_ROOT"
        return 1
    fi
    if ! REPRO_BUILD_DIR=$(mktemp -d "$REPRO_TEMP_ROOT/record-platform-contract-build.XXXXXX"); then
        fail "Cannot create an isolated reproducible artifact build directory"
        return 1
    fi
    ECC_REPRO_BUILD_DIR="$REPRO_BUILD_DIR/ecc"
    SM_REPRO_BUILD_DIR="$REPRO_BUILD_DIR/sm"
}

# 使用固定 FISCO solc 画像生成可复现 creation 与 deployed runtime 制品。
compile_reproducible_variant() {
    local variant="$1"
    local compiler
    local output_dir
    local required_version_line
    if [ "$variant" = "ecc" ]; then
        compiler="$ECC_SOLC"
        output_dir="$ECC_REPRO_BUILD_DIR"
        required_version_line="Version: $SOLC_BUILD_ID"
    elif [ "$variant" = "sm" ]; then
        compiler="$SM_SOLC"
        output_dir="$SM_REPRO_BUILD_DIR"
        required_version_line="Gm version: $SOLC_BUILD_ID"
    else
        fail "Unsupported reproducible compiler variant: $variant"
        return 1
    fi
    if [ "$DRY_RUN" = true ]; then
        dry "compile ECC/SM creation and runtime with FISCO solc $SOLC_BUILD_ID"
        return 0
    fi
    if [ ! -x "$compiler" ]; then
        fail "FISCO $variant solc not found after Console compilation: $compiler"
        return 1
    fi
    local version_output
    if ! version_output=$("$compiler" --version 2>&1) \
        || [[ "$version_output" != *"$required_version_line"* ]]; then
        fail "FISCO $variant solc must report $required_version_line"
        return 1
    fi
    mkdir -p "$output_dir"
    local output
    if ! output=$(
        cd "$CONTRACT_SRC_DIR"
        "$compiler" \
            --base-path . \
            --include-path . \
            --evm-version london \
            --metadata-hash ipfs \
            --abi \
            --bin \
            --bin-runtime \
            --metadata \
            --overwrite \
            -o "$output_dir" \
            Storage.sol Sharing.sol 2>&1
    ); then
        fail "FISCO $variant reproducible artifact compilation failed"
        printf '%s\n' "$output" | tail -20 | sed 's/^/    /'
        return 1
    fi
    for name in Storage Sharing; do
        for suffix in abi bin bin-runtime; do
            if [ ! -s "$output_dir/$name.$suffix" ]; then
                fail "FISCO $variant compiler did not produce $name.$suffix"
                return 1
            fi
        done
    done
    ok "FISCO $variant reproducible creation/runtime artifacts generated"
}

STORAGE_COMPILED_ABI=""
STORAGE_COMPILED_ECC=""
STORAGE_COMPILED_SM=""
SHARING_COMPILED_ABI=""
SHARING_COMPILED_ECC=""
SHARING_COMPILED_SM=""

if ! compile_contract "Storage" STORAGE_COMPILED_ABI \
    STORAGE_COMPILED_ECC STORAGE_COMPILED_SM; then
    exit 1
fi
if ! compile_contract "Sharing" SHARING_COMPILED_ABI \
    SHARING_COMPILED_ECC SHARING_COMPILED_SM; then
    exit 1
fi
if ! prepare_reproducible_build_directory; then
    exit 1
fi
if ! compile_reproducible_variant ecc; then
    exit 1
fi
if ! compile_reproducible_variant sm; then
    exit 1
fi

# ==============================================================================
# Phase 3: Artifact Verification
# ==============================================================================
section 3 "Artifact Verification"

# 比较 Console 与可复现编译的 ABI/creation/runtime，任何漂移均在链写前阻断。
verify_compiled_artifacts() {
    local name="$1"
    local compiled_abi="$2"
    local compiled_ecc="$3"
    local compiled_sm="$4"
    if [ "$DRY_RUN" = true ]; then
        dry "compare compiled $name ABI plus ECC/SM creation/runtime with signed catalog"
        ok "Dry-run: $name artifact verification would be mandatory"
        return 0
    fi

    if ! python3 "$FINGERPRINT_TOOL" compare-abi \
        --expected "$ABI_DEST_DIR/$name.abi" \
        --actual "$compiled_abi"; then
        fail "$name compiled ABI does not match the signed ABI"
        return 1
    fi

    if ! python3 "$FINGERPRINT_TOOL" compare-bytecode \
        --expected "$BIN_DEST_DIR/ecc/$name.bin" \
        --actual "$compiled_ecc"; then
        fail "$name Console ECC creation bytecode does not match the signed artifact"
        return 1
    fi
    if ! python3 "$FINGERPRINT_TOOL" compare-bytecode \
        --expected "$BIN_DEST_DIR/sm/$name.bin" \
        --actual "$compiled_sm"; then
        fail "$name Console SM creation bytecode does not match the signed artifact"
        return 1
    fi
    for variant in ecc sm; do
        local build_dir="$ECC_REPRO_BUILD_DIR"
        if [ "$variant" = "sm" ]; then
            build_dir="$SM_REPRO_BUILD_DIR"
        fi
        if ! python3 "$FINGERPRINT_TOOL" compare-abi \
            --expected "$ABI_DEST_DIR/$name.abi" \
            --actual "$build_dir/$name.abi"; then
            fail "$name $variant reproducible ABI does not match the signed ABI"
            return 1
        fi
        if ! python3 "$FINGERPRINT_TOOL" compare-bytecode \
            --expected "$BIN_DEST_DIR/$variant/$name.bin" \
            --actual "$build_dir/$name.bin"; then
            fail "$name $variant creation bytecode does not match the signed artifact"
            return 1
        fi
        if ! python3 "$FINGERPRINT_TOOL" compare-bytecode \
            --expected "$BIN_DEST_DIR/runtime/$variant/$name.bin" \
            --actual "$build_dir/$name.bin-runtime"; then
            fail "$name $variant runtime bytecode does not match the signed artifact"
            return 1
        fi
    done
    ok "$name ABI plus ECC/SM creation/runtime match signed artifacts"
}

if ! verify_compiled_artifacts "Storage" "$STORAGE_COMPILED_ABI" \
    "$STORAGE_COMPILED_ECC" "$STORAGE_COMPILED_SM"; then
    exit 1
fi
if ! verify_compiled_artifacts "Sharing" "$SHARING_COMPILED_ABI" \
    "$SHARING_COMPILED_ECC" "$SHARING_COMPILED_SM"; then
    exit 1
fi
if ! catalog_sha256_before_verify=$(calculate_catalog_sha256); then
    fail "Could not calculate catalog SHA-256 before final verification"
    exit 1
fi
if ! python3 "$FINGERPRINT_TOOL" verify \
    --project-root "$PROJECT_ROOT" \
    --catalog "$CATALOG_FILE"; then
    fail "Signed artifacts changed between pre-flight and deployment"
    exit 1
fi
if ! VERIFIED_CATALOG_SHA256=$(calculate_catalog_sha256); then
    fail "Could not calculate catalog SHA-256 after final verification"
    exit 1
fi
if [ "$catalog_sha256_before_verify" != "$VERIFIED_CATALOG_SHA256" ]; then
    fail "Artifact catalog changed during final verification"
    exit 1
fi
ok "Signed artifact catalog remained stable through compilation"

# 从已验证 catalog 中读取指定合约唯一 ACTIVE 条目的名称与语义版本。
read_active_contract_identity() {
    local contract_name="$1"
    python3 - "$CATALOG_FILE" "$contract_name" <<'PY'
import json
import re
import sys
from pathlib import Path

catalog_path = Path(sys.argv[1])
requested_name = sys.argv[2]
with catalog_path.open("r", encoding="utf-8") as catalog_file:
    catalog = json.load(catalog_file)

contracts = catalog.get("contracts")
if not isinstance(contracts, list):
    raise SystemExit(1)
matches = [
    entry
    for entry in contracts
    if isinstance(entry, dict)
    and entry.get("contractName") == requested_name
    and entry.get("status") == "ACTIVE"
]
if len(matches) != 1:
    raise SystemExit(1)

contract_name = matches[0].get("contractName")
semantic_version = matches[0].get("semanticVersion")
semantic_version_pattern = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$"
)
if contract_name != requested_name or not isinstance(semantic_version, str):
    raise SystemExit(1)
if not semantic_version_pattern.fullmatch(semantic_version):
    raise SystemExit(1)
print(f"{contract_name}\t{semantic_version}")
PY
}

STORAGE_EXPECTED_NAME=""
STORAGE_EXPECTED_VERSION=""
SHARING_EXPECTED_NAME=""
SHARING_EXPECTED_VERSION=""
if ! storage_identity=$(read_active_contract_identity Storage); then
    fail "Catalog must contain exactly one valid ACTIVE Storage identity"
    exit 1
fi
if ! sharing_identity=$(read_active_contract_identity Sharing); then
    fail "Catalog must contain exactly one valid ACTIVE Sharing identity"
    exit 1
fi
IFS=$'\t' read -r STORAGE_EXPECTED_NAME STORAGE_EXPECTED_VERSION <<< "$storage_identity"
IFS=$'\t' read -r SHARING_EXPECTED_NAME SHARING_EXPECTED_VERSION <<< "$sharing_identity"
if ! catalog_sha256_after_identity=$(calculate_catalog_sha256); then
    fail "Could not re-check catalog SHA-256 after selecting ACTIVE identities"
    exit 1
fi
if [ "$catalog_sha256_after_identity" != "$VERIFIED_CATALOG_SHA256" ]; then
    fail "Artifact catalog changed while selecting ACTIVE identities"
    exit 1
fi
ok "Catalog identities selected: $STORAGE_EXPECTED_NAME@$STORAGE_EXPECTED_VERSION, $SHARING_EXPECTED_NAME@$SHARING_EXPECTED_VERSION"
ok "Verified catalog bytes fixed at $VERIFIED_CATALOG_SHA256"

# ==============================================================================
# Phase 4: Deploy Contracts
# ==============================================================================
section 4 "Deploy Contracts"

# 从 FISCO Console 部署输出中提取唯一的规范化地址和交易哈希。
parse_deployment_metadata() {
    python3 -c '
import re
import sys

text = sys.stdin.read()
address_patterns = (
    r"contract\s+address\s*:\s*(0x[0-9a-fA-F]{40})",
    r"\"contractAddress\"\s*:\s*\"(0x[0-9a-fA-F]{40})\"",
)
transaction_patterns = (
    r"transaction\s+hash\s*:\s*(0x[0-9a-fA-F]{64})",
    r"\"transactionHash\"\s*:\s*\"(0x[0-9a-fA-F]{64})\"",
)

addresses = {
    match.lower()
    for pattern in address_patterns
    for match in re.findall(pattern, text, re.IGNORECASE)
}
transaction_hashes = {
    match.lower()
    for pattern in transaction_patterns
    for match in re.findall(pattern, text, re.IGNORECASE)
}
if len(addresses) != 1 or len(transaction_hashes) != 1:
    raise SystemExit(1)
address = next(iter(addresses))
transaction_hash = next(iter(transaction_hashes))
print(f"{address}\t{transaction_hash}")
'
}

# 每笔链写紧前重新核验 catalog、staged source 与 Console ABI/ECC/SM creation。
revalidate_contract_deploy_inputs() {
    local name="$1"
    local compiled_abi="$2"
    local compiled_ecc="$3"
    local compiled_sm="$4"
    local crypto_variant="$5"
    if [ "$crypto_variant" != "ecc" ] && [ "$crypto_variant" != "sm" ]; then
        fail "$name cannot deploy with unsupported crypto variant: $crypto_variant"
        return 1
    fi
    if ! validate_console_compile_paths "$name"; then
        return 1
    fi
    for artifact_path in "$compiled_abi" "$compiled_ecc" "$compiled_sm"; do
        if [ ! -s "$artifact_path" ] || [ -L "$artifact_path" ]; then
            fail "$name verified Console artifact was replaced before deployment: $artifact_path"
            return 1
        fi
    done
    if [ -L "$CONSOLE_CONTRACT_DIR/$name.sol" ] \
        || ! cmp -s "$CONTRACT_SRC_DIR/$name.sol" "$CONSOLE_CONTRACT_DIR/$name.sol"; then
        fail "$name staged source drifted after artifact verification"
        return 1
    fi
    if ! python3 "$FINGERPRINT_TOOL" verify \
        --project-root "$PROJECT_ROOT" \
        --catalog "$CATALOG_FILE"; then
        fail "$name signed artifacts changed before deployment"
        return 1
    fi
    local current_catalog_sha256
    if ! current_catalog_sha256=$(calculate_catalog_sha256) \
        || [ "$current_catalog_sha256" != "$VERIFIED_CATALOG_SHA256" ]; then
        fail "$name artifact catalog changed before deployment"
        return 1
    fi
    if ! python3 "$FINGERPRINT_TOOL" compare-abi \
        --expected "$ABI_DEST_DIR/$name.abi" \
        --actual "$compiled_abi"; then
        fail "$name Console ABI changed after artifact verification"
        return 1
    fi
    if ! python3 "$FINGERPRINT_TOOL" compare-bytecode \
        --expected "$BIN_DEST_DIR/ecc/$name.bin" \
        --actual "$compiled_ecc"; then
        fail "$name Console ECC creation changed after artifact verification"
        return 1
    fi
    if ! python3 "$FINGERPRINT_TOOL" compare-bytecode \
        --expected "$BIN_DEST_DIR/sm/$name.bin" \
        --actual "$compiled_sm"; then
        fail "$name Console SM creation changed after artifact verification"
        return 1
    fi
    ok "$name deploy inputs revalidated immediately before $crypto_variant chain write"
}

# 从结构化 RPC 输出中提取唯一、成功且与部署输出一致的完整回执。
parse_successful_deployment_receipt() {
    local expected_transaction_hash="$1"
    local expected_contract_address="$2"
    python3 -c '
import json
import re
import sys

expected_transaction_hash = sys.argv[1].lower()
expected_contract_address = sys.argv[2].lower()
text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", sys.stdin.read())
decoder = json.JSONDecoder()
required_fields = {"status", "transactionHash", "contractAddress", "blockNumber"}
transaction_pattern = re.compile(r"0x[0-9a-f]{64}", re.IGNORECASE)
address_pattern = re.compile(r"0x[0-9a-f]{40}", re.IGNORECASE)
candidates = set()
invalid_receipt_shape = False
rpc_error = False

def parse_quantity(value):
    if isinstance(value, bool):
        raise ValueError
    if isinstance(value, int):
        if value < 0:
            raise ValueError
        return value
    if not isinstance(value, str):
        raise ValueError
    if re.fullmatch(r"0[xX][0-9a-fA-F]+", value):
        return int(value, 16)
    if re.fullmatch(r"0|[1-9][0-9]*", value):
        return int(value, 10)
    raise ValueError

def collect(value):
    global invalid_receipt_shape, rpc_error
    if isinstance(value, dict):
        if value.get("error") not in (None, {}, []):
            rpc_error = True
        present_fields = required_fields.intersection(value)
        if present_fields.intersection({"transactionHash", "contractAddress"}):
            if present_fields != required_fields:
                invalid_receipt_shape = True
            else:
                try:
                    status = parse_quantity(value["status"])
                    block_number = parse_quantity(value["blockNumber"])
                    transaction_hash = value["transactionHash"]
                    contract_address = value["contractAddress"]
                    if (
                        status != 0
                        or block_number > 9223372036854775807
                        or not isinstance(transaction_hash, str)
                        or transaction_pattern.fullmatch(transaction_hash) is None
                        or not isinstance(contract_address, str)
                        or address_pattern.fullmatch(contract_address) is None
                        or contract_address.lower()
                            == "0x0000000000000000000000000000000000000000"
                    ):
                        raise ValueError
                    candidates.add((
                        transaction_hash.lower(),
                        contract_address.lower(),
                        block_number,
                    ))
                except (TypeError, ValueError):
                    invalid_receipt_shape = True
        for nested in value.values():
            collect(nested)
    elif isinstance(value, list):
        for nested in value:
            collect(nested)
    elif isinstance(value, str):
        try:
            nested = json.loads(value)
        except json.JSONDecodeError:
            return
        if isinstance(nested, (dict, list)):
            collect(nested)

for index, character in enumerate(text):
    if character not in "[{":
        continue
    try:
        value, _ = decoder.raw_decode(text[index:])
    except json.JSONDecodeError:
        continue
    collect(value)

if rpc_error or invalid_receipt_shape or len(candidates) != 1:
    raise SystemExit(1)
transaction_hash, contract_address, block_number = next(iter(candidates))
if (
    transaction_hash != expected_transaction_hash
    or contract_address != expected_contract_address
):
    raise SystemExit(1)
print(f"{transaction_hash}\t{contract_address}\t{block_number}")
' "$expected_transaction_hash" "$expected_contract_address"
}

# 部署单个合约并强制取得地址、交易哈希和交易所在区块。
deploy_contract() {
    local name="$1"
    local address_result_var="$2"
    local transaction_result_var="$3"
    local block_result_var="$4"
    local compiled_abi="$5"
    local compiled_ecc="$6"
    local compiled_sm="$7"
    if [ "$DRY_RUN" = true ]; then
        local dry_address="0x1111111111111111111111111111111111111111"
        if [ "$name" = "Sharing" ]; then
            dry_address="0x2222222222222222222222222222222222222222"
        fi
        printf -v "$address_result_var" '%s' "$dry_address"
        printf -v "$transaction_result_var" '%s' \
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        printf -v "$block_result_var" '%s' "1"
        dry "deploy $name and resolve its transaction receipt"
        ok "Dry-run: $name deployment would collect address/transaction/block evidence"
        return 0
    fi

    if ! revalidate_contract_deploy_inputs \
        "$name" "$compiled_abi" "$compiled_ecc" "$compiled_sm" \
        "$FISCO_CRYPTO_VARIANT"; then
        return 1
    fi
    if ! verify_fisco_chain_identity; then
        fail "$name chain/group context changed before deployment"
        return 1
    fi

    local output
    if ! output=$(
        cd "$CONSOLE_DIR"
        printf 'deploy %s\nexit\n' "$name" | run_console 120 2>&1
    ); then
        fail "$name deployment command failed"
        printf '%s\n' "$output" | tail -20 | sed 's/^/    /'
        return 1
    fi

    local metadata
    if ! metadata=$(printf '%s\n' "$output" | parse_deployment_metadata); then
        fail "$name deployment did not return a valid address and transaction hash"
        printf '%s\n' "$output" | tail -20 | sed 's/^/    /'
        return 1
    fi

    local address
    local transaction_hash
    IFS=$'\t' read -r address transaction_hash <<< "$metadata"

    local receipt_output
    if ! receipt_output=$(
        cd "$CONSOLE_DIR"
        printf 'getGroupInfo\ngetTransactionReceipt %s\nexit\n' "$transaction_hash" | \
            run_console 30 2>&1
    ); then
        fail "$name deployment receipt lookup failed"
        return 1
    fi

    local receipt_chain_identity
    if ! receipt_chain_identity=$(
        printf '%s\n' "$receipt_output" | extract_fisco_chain_identity
    ); then
        fail "$name deployment receipt lookup did not return one chain/group context"
        printf '%s\n' "$receipt_output" | tail -20 | sed 's/^/    /'
        return 1
    fi
    if ! validate_observed_fisco_chain_identity "$receipt_chain_identity"; then
        fail "$name deployment receipt came from an unexpected chain/group context"
        return 1
    fi

    local receipt_metadata
    if ! receipt_metadata=$(
        printf '%s\n' "$receipt_output" \
            | parse_successful_deployment_receipt "$transaction_hash" "$address"
    ); then
        fail "$name deployment receipt was missing, failed, ambiguous or mismatched"
        printf '%s\n' "$receipt_output" | tail -20 | sed 's/^/    /'
        return 1
    fi

    local receipt_transaction_hash
    local receipt_address
    local block_number
    IFS=$'\t' read -r receipt_transaction_hash receipt_address block_number \
        <<< "$receipt_metadata"

    printf -v "$address_result_var" '%s' "$receipt_address"
    printf -v "$transaction_result_var" '%s' "$receipt_transaction_hash"
    printf -v "$block_result_var" '%s' "$block_number"
    ok "$name deployed with a successful chain-bound receipt at $receipt_address (tx=$receipt_transaction_hash, block=$block_number)"
}

STORAGE_ADDR=""
STORAGE_TX=""
STORAGE_BLOCK=""
SHARING_ADDR=""
SHARING_TX=""
SHARING_BLOCK=""

if ! deploy_contract Storage STORAGE_ADDR STORAGE_TX STORAGE_BLOCK \
    "$STORAGE_COMPILED_ABI" "$STORAGE_COMPILED_ECC" "$STORAGE_COMPILED_SM"; then
    exit 1
fi
if ! deploy_contract Sharing SHARING_ADDR SHARING_TX SHARING_BLOCK \
    "$SHARING_COMPILED_ABI" "$SHARING_COMPILED_ECC" "$SHARING_COMPILED_SM"; then
    exit 1
fi
if [ "$STORAGE_ADDR" = "$SHARING_ADDR" ]; then
    fail "Storage and Sharing deployments returned the same contract address"
    exit 1
fi

# ==============================================================================
# Phase 5: On-chain Verification
# ==============================================================================
section 5 "On-chain Verification"

# 从 getCode 输出中提取独立一行或 JSON 字段中的候选 EVM runtime code。
extract_runtime_code() {
    python3 -c '
import re
import sys

text = sys.stdin.read()
patterns = (
    r"(?im)^\s*\"?(0x[0-9a-fA-F]+)\"?\s*$",
    r"\"(?:code|result)\"\s*:\s*\"(0x[0-9a-fA-F]+)\"",
)
candidates = set()
for pattern in patterns:
    matches = re.findall(pattern, text)
    candidates.update(
        value.lower()
        for value in matches
        if value.lower() not in {"0x", "0x0"}
    )
if len(candidates) != 1:
    raise SystemExit(1)
print(next(iter(candidates)))
'
}

# 强制校验目标地址完整 runtime code 与实际链 crypto 变体的签入制品一致。
verify_contract_code() {
    local name="$1"
    local address="$2"
    local crypto_variant="$3"
    if [ "$DRY_RUN" = true ]; then
        dry "getCode $address"
        ok "Dry-run: $name runtime code would have to match the node-selected variant"
        return 0
    fi

    local output
    if ! output=$(
        cd "$CONSOLE_DIR"
        printf 'getCode %s\nexit\n' "$address" | run_console 30 2>&1
    ); then
        fail "$name getCode request failed"
        return 1
    fi
    local runtime_code
    if ! runtime_code=$(printf '%s\n' "$output" | extract_runtime_code); then
        fail "$name address has no verifiable runtime code: $address"
        printf '%s\n' "$output" | tail -10 | sed 's/^/    /'
        return 1
    fi
    if ! printf '%s\n' "$runtime_code" | python3 "$FINGERPRINT_TOOL" \
        compare-bytecode \
        --expected "$BIN_DEST_DIR/runtime/$crypto_variant/$name.bin" \
        --actual-stdin; then
        fail "$name runtime bytecode mismatch for FISCO $crypto_variant variant"
        return 1
    fi
    ok "$name runtime bytecode matches signed $crypto_variant artifact"
}

# 从 contractIdentity 只读调用中提取唯一的合约名称和语义版本。
extract_contract_identity_response() {
    python3 -c '
import re
import sys

text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", sys.stdin.read())
if re.search(r"revert|exception|failed", text, re.IGNORECASE):
    raise SystemExit(1)
return_codes = re.findall(
    r"(?im)^\s*Return code:\s*(-?[0-9]+)\s*$",
    text,
)
if len(return_codes) != 1 or int(return_codes[0]) != 0:
    raise SystemExit(1)
return_values = re.findall(
    r"(?im)^\s*Return values:\s*\(([^()\r\n]*)\)\s*$",
    text,
)
if len(return_values) != 1:
    raise SystemExit(1)
identity = [part.strip() for part in return_values[0].split(",")]
if len(identity) != 2 or not all(identity):
    raise SystemExit(1)
if any(any(character.isspace() for character in value) for value in identity):
    raise SystemExit(1)
print(f"{identity[0]}\t{identity[1]}")
'
}

# 调用 contractIdentity，并与已验证 catalog 的 ACTIVE 名称/版本严格对账。
verify_contract_identity() {
    local name="$1"
    local address="$2"
    local expected_name="$3"
    local expected_version="$4"
    if [ "$DRY_RUN" = true ]; then
        dry "call $name $address contractIdentity"
        ok "Dry-run: $name identity would have to equal $expected_name@$expected_version"
        return 0
    fi

    local output
    if ! output=$(
        cd "$CONSOLE_DIR"
        printf 'call %s %s contractIdentity\nexit\n' "$name" "$address" | \
            run_console 30 2>&1
    ); then
        fail "$name.contractIdentity verification command failed"
        return 1
    fi

    local actual_identity
    if ! actual_identity=$(printf '%s\n' "$output" | extract_contract_identity_response); then
        fail "$name.contractIdentity did not return one parseable successful identity"
        printf '%s\n' "$output" | tail -10 | sed 's/^/    /'
        return 1
    fi

    local actual_name
    local actual_version
    IFS=$'\t' read -r actual_name actual_version <<< "$actual_identity"
    if [ "$actual_name" != "$expected_name" ] || [ "$actual_version" != "$expected_version" ]; then
        fail "$name identity mismatch: expected=$expected_name@$expected_version, actual=$actual_name@$actual_version"
        return 1
    fi
    ok "$name identity matches verified catalog ($actual_name@$actual_version)"
}

if ! verify_contract_code Storage "$STORAGE_ADDR" \
    "${FISCO_CRYPTO_VARIANT:-node-selected}"; then
    exit 1
fi
if ! verify_contract_identity Storage "$STORAGE_ADDR" \
    "$STORAGE_EXPECTED_NAME" "$STORAGE_EXPECTED_VERSION"; then
    exit 1
fi
if ! verify_contract_code Sharing "$SHARING_ADDR" \
    "${FISCO_CRYPTO_VARIANT:-node-selected}"; then
    exit 1
fi
if ! verify_contract_identity Sharing "$SHARING_ADDR" \
    "$SHARING_EXPECTED_NAME" "$SHARING_EXPECTED_VERSION"; then
    exit 1
fi

# ==============================================================================
# Phase 6: Audited Atomic Activation
# ==============================================================================
section 6 "Audited Atomic Activation"

if ! python3 "$FINGERPRINT_TOOL" verify \
    --project-root "$PROJECT_ROOT" \
    --catalog "$CATALOG_FILE"; then
    fail "Signed artifacts changed after deployment; receipt and activation are blocked"
    exit 1
fi
if ! current_catalog_sha256=$(calculate_catalog_sha256); then
    fail "Could not re-check catalog SHA-256 before audited activation"
    exit 1
fi
if [ "$current_catalog_sha256" != "$VERIFIED_CATALOG_SHA256" ]; then
    fail "Artifact catalog changed after verification; receipt and activation are blocked"
    exit 1
fi

# 生成一次秒级 UTC ISO-8601 生效时间，并拒绝任何非规范日期输出。
generate_deployment_effective_at() {
    local effective_at
    effective_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    python3 - "$effective_at" <<'PY'
import datetime
import sys

value = sys.argv[1]
try:
    parsed = datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
except ValueError:
    raise SystemExit(1)
if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
    raise SystemExit(1)
print(value)
PY
}

DEPLOYMENT_EFFECTIVE_AT=""
DEPLOYMENT_RECEIPT=""
if [ "$DRY_RUN" = true ]; then
    DEPLOYMENT_EFFECTIVE_AT="not-generated-in-dry-run"
    dry "generate one UTC effectiveAt after successful on-chain verification"
else
    if ! DEPLOYMENT_EFFECTIVE_AT=$(generate_deployment_effective_at); then
        fail "Could not generate a canonical UTC deployment effective time"
        exit 1
    fi
    ok "Deployment effective time fixed at $DEPLOYMENT_EFFECTIVE_AT"
fi

# 生成不含 RPC/凭据的结构化部署回执，并在同目录内原子发布。
write_deployment_receipt() {
    local receipt_dir="$1"
    if [ "$DRY_RUN" = true ]; then
        dry "atomically write a structured deployment receipt under $receipt_dir"
        ok "Dry-run: no deployment receipt would be created"
        return 0
    fi

    if [ -e "$receipt_dir" ]; then
        if [ ! -d "$receipt_dir" ] || [ -L "$receipt_dir" ]; then
            fail "Deployment receipt target must be a non-symlink directory: $receipt_dir"
            return 1
        fi
    elif ! mkdir -p "$receipt_dir"; then
        fail "Could not create deployment receipt directory: $receipt_dir"
        return 1
    fi

    local temporary_file
    if ! temporary_file=$(mktemp "$receipt_dir/.contract-deployment-receipt.tmp.XXXXXX"); then
        fail "Could not allocate a deployment receipt temporary file"
        return 1
    fi
    local receipt_nonce="${temporary_file##*.tmp.}"
    local receipt_name="record-platform-contract-deployment-${DEPLOYMENT_EFFECTIVE_AT//:/-}-$receipt_nonce.json"
    local receipt_path="$receipt_dir/$receipt_name"
    if [ -e "$receipt_path" ] || [ -L "$receipt_path" ]; then
        fail "Deployment receipt path collision; temporary file retained: $temporary_file"
        return 1
    fi
    chmod 600 "$temporary_file"
    if ! python3 - \
        "$VERIFIED_CATALOG_SHA256" \
        "${FISCO_CHAIN_ID:-}" \
        "${FISCO_GROUP_ID:-}" \
        "$DEPLOYMENT_EFFECTIVE_AT" \
        "$STORAGE_EXPECTED_NAME" \
        "$STORAGE_EXPECTED_VERSION" \
        "$STORAGE_ADDR" \
        "$STORAGE_TX" \
        "$STORAGE_BLOCK" \
        "$SHARING_EXPECTED_NAME" \
        "$SHARING_EXPECTED_VERSION" \
        "$SHARING_ADDR" \
        "$SHARING_TX" \
        "$SHARING_BLOCK" > "$temporary_file" <<'PY'
import json
import sys

(
    catalog_sha256,
    chain_id,
    group_id,
    effective_at,
    storage_name,
    storage_version,
    storage_address,
    storage_transaction,
    storage_block,
    sharing_name,
    sharing_version,
    sharing_address,
    sharing_transaction,
    sharing_block,
) = sys.argv[1:]
receipt = {
    "schemaVersion": "record-platform-contract-deployment-receipt.v2",
    "verificationStatus": "VERIFIED",
    "catalogSha256": catalog_sha256,
    "chainType": "LOCAL_FISCO",
    "chainId": chain_id,
    "groupId": group_id,
    "effectiveAt": effective_at,
    "contracts": [
        {
            "contractName": storage_name,
            "semanticVersion": storage_version,
            "address": storage_address,
            "receiptStatus": "SUCCESS",
            "transactionHash": storage_transaction,
            "blockNumber": int(storage_block),
            "effectiveAt": effective_at,
        },
        {
            "contractName": sharing_name,
            "semanticVersion": sharing_version,
            "address": sharing_address,
            "receiptStatus": "SUCCESS",
            "transactionHash": sharing_transaction,
            "blockNumber": int(sharing_block),
            "effectiveAt": effective_at,
        },
    ],
}
json.dump(receipt, sys.stdout, ensure_ascii=False, indent=2)
sys.stdout.write("\n")
PY
    then
        fail "Could not render deployment receipt; temporary file retained: $temporary_file"
        return 1
    fi
    if ! mv "$temporary_file" "$receipt_path"; then
        fail "Could not atomically publish deployment receipt; temporary file retained: $temporary_file"
        return 1
    fi
    DEPLOYMENT_RECEIPT="$receipt_path"
    ok "Structured deployment receipt published atomically: $receipt_path"
}

if ! write_deployment_receipt "$RECEIPT_DIR"; then
    exit 1
fi

# 以同目录临时文件一次性更新地址和完整部署证据，避免只激活一半配置。
writeback_deployment_env() {
    local env_file="$1"
    if [ "$DRY_RUN" = true ]; then
        dry "atomically update contract addresses, transaction/block and effectiveAt in $env_file"
        ok "Dry-run: activation file would remain unchanged"
        return 0
    fi
    if [ ! -f "$env_file" ] || [ -L "$env_file" ]; then
        fail "Activation target must be an existing regular non-symlink file: $env_file"
        return 1
    fi

    local temporary_file
    temporary_file=$(mktemp "${env_file}.tmp.XXXXXX")
    chmod 600 "$temporary_file"
    if ! awk \
        -v storage_address="$STORAGE_ADDR" \
        -v sharing_address="$SHARING_ADDR" \
        -v storage_tx="$STORAGE_TX" \
        -v sharing_tx="$SHARING_TX" \
        -v storage_block="$STORAGE_BLOCK" \
        -v sharing_block="$SHARING_BLOCK" \
        -v storage_effective_at="$DEPLOYMENT_EFFECTIVE_AT" \
        -v sharing_effective_at="$DEPLOYMENT_EFFECTIVE_AT" '
        BEGIN {
            values["FISCO_STORAGE_CONTRACT"] = storage_address
            values["FISCO_SHARING_CONTRACT"] = sharing_address
            values["FISCO_STORAGE_DEPLOYMENT_TX"] = storage_tx
            values["FISCO_SHARING_DEPLOYMENT_TX"] = sharing_tx
            values["FISCO_STORAGE_DEPLOYMENT_BLOCK"] = storage_block
            values["FISCO_SHARING_DEPLOYMENT_BLOCK"] = sharing_block
            values["FISCO_STORAGE_DEPLOYMENT_EFFECTIVE_AT"] = storage_effective_at
            values["FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT"] = sharing_effective_at
            order[1] = "FISCO_STORAGE_CONTRACT"
            order[2] = "FISCO_SHARING_CONTRACT"
            order[3] = "FISCO_STORAGE_DEPLOYMENT_TX"
            order[4] = "FISCO_SHARING_DEPLOYMENT_TX"
            order[5] = "FISCO_STORAGE_DEPLOYMENT_BLOCK"
            order[6] = "FISCO_SHARING_DEPLOYMENT_BLOCK"
            order[7] = "FISCO_STORAGE_DEPLOYMENT_EFFECTIVE_AT"
            order[8] = "FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT"
        }
        {
            candidate = $0
            sub(/^[[:space:]]*/, "", candidate)
            key = candidate
            sub(/=.*/, "", key)
            if (key in values) {
                if (!seen[key]) {
                    print key "=" values[key]
                    seen[key] = 1
                }
                next
            }
            print $0
        }
        END {
            for (position = 1; position <= 8; position++) {
                key = order[position]
                if (!seen[key]) {
                    print key "=" values[key]
                }
            }
        }
    ' "$env_file" > "$temporary_file"; then
        fail "Could not prepare atomic activation file; temporary file retained: $temporary_file"
        return 1
    fi
    if ! mv "$temporary_file" "$env_file"; then
        fail "Could not atomically replace activation file; temporary file retained: $temporary_file"
        return 1
    fi
    ok "Contract addresses and complete deployment evidence activated atomically in $env_file"
}

if ! writeback_deployment_env "$ENV_FILE"; then
    exit 1
fi

# ==============================================================================
# Summary
# ==============================================================================
echo
echo "${BOLD}========================================${RESET}"
echo "${BOLD}Deployment Summary${RESET}"
echo "${BOLD}========================================${RESET}"
if [ "$DRY_RUN" = true ]; then
    echo "  Mode              : ${YELLOW}DRY-RUN (no changes made)${RESET}"
fi
echo "  Storage contract  : ${GREEN}$STORAGE_ADDR${RESET}"
echo "  Storage tx/block  : $STORAGE_TX / $STORAGE_BLOCK"
echo "  Sharing contract  : ${GREEN}$SHARING_ADDR${RESET}"
echo "  Sharing tx/block  : $SHARING_TX / $SHARING_BLOCK"
echo "  Effective at      : $DEPLOYMENT_EFFECTIVE_AT"
echo "  Artifact catalog  : $CATALOG_FILE"
if [ "$DRY_RUN" = true ]; then
    echo "  Deployment receipt: not created"
    echo "  Activation target : $ENV_FILE (unchanged)"
else
    echo "  Deployment receipt: $DEPLOYMENT_RECEIPT"
    echo "  Activation target : $ENV_FILE (updated atomically)"
fi
echo "${BOLD}========================================${RESET}"

if [ $FAILURES -eq 0 ]; then
    echo "${GREEN}${BOLD}All phases completed successfully!${RESET}"
    echo
    echo "Next steps:"
    echo "  1. Restart the FISCO service to pick up the new contract addresses:"
    echo "     ./scripts/start.sh restart fisco"
    echo "  2. Verify environment:"
    echo "     ./scripts/env-check.sh --service contracts"
else
    echo "${RED}${BOLD}$FAILURES phase(s) failed.${RESET}"
    echo "Review the errors above and re-run after fixing."
    exit 1
fi
