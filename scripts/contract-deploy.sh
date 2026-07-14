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
#   5. On-chain verification   - require non-empty code and exact catalog identities
#   6. Audited activation      - publish a receipt, then atomically activate complete evidence
#
# Usage:
#   ./scripts/contract-deploy.sh [options]
#
# Options:
#   --console-dir DIR    FISCO BCOS console directory (default: ~/fisco/console)
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
        "$TIMEOUT_COMMAND" "$timeout_seconds" ./console.sh
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
SOLC_VERSION="0.8.35"
CONSOLE_CONTRACT_DIR="$CONSOLE_DIR/contracts/solidity"
CONSOLE_SDK_DIR="$CONSOLE_DIR/contracts/sdk"

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

# 从官方 getGroupInfo JSON 输出中提取唯一的 chainID/groupID 组合。
extract_fisco_chain_identity() {
    python3 -c '
import json
import re
import sys

text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", sys.stdin.read())
decoder = json.JSONDecoder()
candidates = set()

def collect(value):
    if isinstance(value, dict):
        if "chainID" in value or "groupID" in value:
            chain_id = value.get("chainID")
            group_id = value.get("groupID")
            if isinstance(chain_id, str) and isinstance(group_id, str):
                if chain_id and group_id and not any(
                    character.isspace() for character in chain_id + group_id
                ):
                    candidates.add((chain_id, group_id))
        for nested in value.values():
            collect(nested)
    elif isinstance(value, list):
        for nested in value:
            collect(nested)

for index, character in enumerate(text):
    if character not in "[{":
        continue
    try:
        value, _ = decoder.raw_decode(text[index:])
    except json.JSONDecodeError:
        continue
    collect(value)

if len(candidates) != 1:
    raise SystemExit(1)
chain_id, group_id = next(iter(candidates))
print(f"{chain_id}\t{group_id}")
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

# 部署前查询节点身份，并与显式 FISCO_CHAIN_ID/FISCO_GROUP_ID 完全对账。
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
        dry "getGroupInfo and compare chainID/groupID with $expected_chain_id/$expected_group_id"
        ok "Dry-run: node chain/group query skipped; deployment would require an exact match"
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
        fail "getGroupInfo did not return one valid chainID/groupID pair"
        printf '%s\n' "$output" | tail -10 | sed 's/^/    /'
        return 1
    fi

    local actual_chain_id
    local actual_group_id
    IFS=$'\t' read -r actual_chain_id actual_group_id <<< "$actual_identity"
    if [ "$actual_chain_id" != "$expected_chain_id" ]; then
        fail "FISCO chain mismatch: configured=$expected_chain_id, actual=$actual_chain_id"
        return 1
    fi
    if [ "$actual_group_id" != "$expected_group_id" ]; then
        fail "FISCO group mismatch: configured=$expected_group_id, actual=$actual_group_id"
        return 1
    fi
    ok "FISCO node identity matches configured chain/group ($actual_chain_id/$actual_group_id)"
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

# 1b. console.sh is executable
if [ -x "$CONSOLE_DIR/console.sh" ]; then
    ok "console.sh is executable"
else
    fail "console.sh not found or not executable: $CONSOLE_DIR/console.sh"
    info "Ensure FISCO BCOS console is correctly installed"
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

# 在 FISCO Console 支持的目录布局中定位一份编译产物。
find_compiled_artifact() {
    local kind="$1"
    local name="$2"
    local candidate
    local candidates=()
    if [ "$kind" = "abi" ]; then
        candidates=(
            "$CONSOLE_SDK_DIR/abi/$name.abi"
            "$CONSOLE_SDK_DIR/abi/sm/$name.abi"
            "$CONSOLE_SDK_DIR/$name.abi"
        )
    else
        candidates=(
            "$CONSOLE_SDK_DIR/bin/$name.bin"
            "$CONSOLE_SDK_DIR/bin/sm/$name.bin"
            "$CONSOLE_SDK_DIR/$name.bin"
        )
    fi
    for candidate in "${candidates[@]}"; do
        if [ -s "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# 复制指定合约源码，调用官方编译器，并返回 ABI/BIN 产物路径。
compile_contract() {
    local name="$1"
    local abi_result_var="$2"
    local bin_result_var="$3"
    if [ "$DRY_RUN" = true ]; then
        dry "copy $name.sol to $CONSOLE_CONTRACT_DIR and run contract2java.sh -v $SOLC_VERSION"
        ok "Dry-run: $name compile would be executed"
        return 0
    fi

    mkdir -p "$CONSOLE_CONTRACT_DIR"
    cp "$CONTRACT_SRC_DIR/$name.sol" "$CONSOLE_CONTRACT_DIR/$name.sol"
    if ! cmp -s "$CONTRACT_SRC_DIR/$name.sol" "$CONSOLE_CONTRACT_DIR/$name.sol"; then
        fail "$name source changed while staging it into FISCO Console"
        return 1
    fi

    local output
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

    local abi_path
    local bin_path
    if ! abi_path=$(find_compiled_artifact abi "$name"); then
        fail "$name compilation did not produce an ABI artifact"
        return 1
    fi
    if ! bin_path=$(find_compiled_artifact bin "$name"); then
        fail "$name compilation did not produce a BIN artifact"
        return 1
    fi
    printf -v "$abi_result_var" '%s' "$abi_path"
    printf -v "$bin_result_var" '%s' "$bin_path"
    ok "$name compiled to ABI and BIN"
}

STORAGE_COMPILED_ABI=""
STORAGE_COMPILED_BIN=""
SHARING_COMPILED_ABI=""
SHARING_COMPILED_BIN=""

if ! compile_contract "Storage" STORAGE_COMPILED_ABI STORAGE_COMPILED_BIN; then
    exit 1
fi
if ! compile_contract "Sharing" SHARING_COMPILED_ABI SHARING_COMPILED_BIN; then
    exit 1
fi

# ==============================================================================
# Phase 3: Artifact Verification
# ==============================================================================
section 3 "Artifact Verification"

# 比较编译产物与签入 ABI/ECC/SM bytecode，任何漂移均在链写之前阻断。
verify_compiled_artifacts() {
    local name="$1"
    local compiled_abi="$2"
    local compiled_bin="$3"
    if [ "$DRY_RUN" = true ]; then
        dry "compare compiled $name ABI/BIN with signed artifacts and catalog"
        ok "Dry-run: $name artifact verification would be mandatory"
        return 0
    fi

    if ! python3 "$FINGERPRINT_TOOL" compare-abi \
        --expected "$ABI_DEST_DIR/$name.abi" \
        --actual "$compiled_abi"; then
        fail "$name compiled ABI does not match the signed ABI"
        return 1
    fi

    local ecc_result=""
    local sm_result=""
    if ecc_result=$(python3 "$FINGERPRINT_TOOL" compare-bytecode \
        --expected "$BIN_DEST_DIR/ecc/$name.bin" \
        --actual "$compiled_bin" 2>&1); then
        ok "$name compiled bytecode matches signed ECC artifact"
    elif sm_result=$(python3 "$FINGERPRINT_TOOL" compare-bytecode \
        --expected "$BIN_DEST_DIR/sm/$name.bin" \
        --actual "$compiled_bin" 2>&1); then
        ok "$name compiled bytecode matches signed SM artifact"
    else
        fail "$name compiled bytecode matches neither signed ECC nor SM artifact"
        info "ECC comparison: $ecc_result"
        info "SM comparison: $sm_result"
        return 1
    fi
}

if ! verify_compiled_artifacts "Storage" "$STORAGE_COMPILED_ABI" "$STORAGE_COMPILED_BIN"; then
    exit 1
fi
if ! verify_compiled_artifacts "Sharing" "$SHARING_COMPILED_ABI" "$SHARING_COMPILED_BIN"; then
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

# 从 FISCO Console 部署输出中提取规范化地址和交易哈希。
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

def first_match(patterns):
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(1).lower()
    return None

address = first_match(address_patterns)
transaction_hash = first_match(transaction_patterns)
if not address or not transaction_hash:
    raise SystemExit(1)
print(f"{address}\t{transaction_hash}")
'
}

# 从交易回执或部署输出中提取十进制区块号。
extract_block_number() {
    python3 -c '
import re
import sys

text = sys.stdin.read()
patterns = (
    r"\"blockNumber\"\s*:\s*\"?((?:0x)?[0-9a-fA-F]+)\"?",
    r"block\s+number\s*:\s*((?:0x)?[0-9a-fA-F]+)",
    r"on\s+block\s*:\s*([0-9]+)",
)
for pattern in patterns:
    match = re.search(pattern, text, re.IGNORECASE)
    if match:
        value = match.group(1)
        print(int(value, 16) if value.lower().startswith("0x") else int(value, 10))
        raise SystemExit(0)
raise SystemExit(1)
'
}

# 部署单个合约并强制取得地址、交易哈希和交易所在区块。
deploy_contract() {
    local name="$1"
    local address_result_var="$2"
    local transaction_result_var="$3"
    local block_result_var="$4"
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

    if ! cmp -s "$CONTRACT_SRC_DIR/$name.sol" "$CONSOLE_CONTRACT_DIR/$name.sol"; then
        fail "$name staged source drifted after artifact verification"
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
        printf 'getTransactionReceipt %s\nexit\n' "$transaction_hash" | \
            run_console 30 2>&1
    ); then
        fail "$name deployment receipt lookup failed"
        return 1
    fi

    local block_number
    if ! block_number=$(printf '%s\n%s\n' "$output" "$receipt_output" | extract_block_number); then
        fail "$name deployment receipt did not contain a valid block number"
        printf '%s\n' "$receipt_output" | tail -20 | sed 's/^/    /'
        return 1
    fi

    printf -v "$address_result_var" '%s' "$address"
    printf -v "$transaction_result_var" '%s' "$transaction_hash"
    printf -v "$block_result_var" '%s' "$block_number"
    ok "$name deployed at $address (tx=$transaction_hash, block=$block_number)"
}

STORAGE_ADDR=""
STORAGE_TX=""
STORAGE_BLOCK=""
SHARING_ADDR=""
SHARING_TX=""
SHARING_BLOCK=""

if ! deploy_contract Storage STORAGE_ADDR STORAGE_TX STORAGE_BLOCK; then
    exit 1
fi
if ! deploy_contract Sharing SHARING_ADDR SHARING_TX SHARING_BLOCK; then
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

# 从 getCode 输出中提取独立一行或 JSON 字段中的非空 EVM runtime code。
extract_runtime_code() {
    python3 -c '
import re
import sys

text = sys.stdin.read()
patterns = (
    r"(?im)^\s*\"?(0x[0-9a-fA-F]+)\"?\s*$",
    r"\"(?:code|result)\"\s*:\s*\"(0x[0-9a-fA-F]+)\"",
)
for pattern in patterns:
    matches = re.findall(pattern, text)
    valid = [value for value in matches if value.lower() not in {"0x", "0x0"}]
    if valid:
        print(max(valid, key=len).lower())
        raise SystemExit(0)
raise SystemExit(1)
'
}

# 强制校验目标地址存在非空 runtime code。
verify_contract_code() {
    local name="$1"
    local address="$2"
    if [ "$DRY_RUN" = true ]; then
        dry "getCode $address"
        ok "Dry-run: $name runtime code would be required"
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
    ok "$name address returned non-empty runtime code (${#runtime_code} hex chars)"
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

if ! verify_contract_code Storage "$STORAGE_ADDR"; then
    exit 1
fi
if ! verify_contract_identity Storage "$STORAGE_ADDR" \
    "$STORAGE_EXPECTED_NAME" "$STORAGE_EXPECTED_VERSION"; then
    exit 1
fi
if ! verify_contract_code Sharing "$SHARING_ADDR"; then
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
    "schemaVersion": "record-platform-contract-deployment-receipt.v1",
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
            "transactionHash": storage_transaction,
            "blockNumber": int(storage_block),
            "effectiveAt": effective_at,
        },
        {
            "contractName": sharing_name,
            "semanticVersion": sharing_version,
            "address": sharing_address,
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
