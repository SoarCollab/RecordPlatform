#!/usr/bin/env bash
# ==============================================================================
# RecordPlatform Smart Contract Lifecycle Tool
# ==============================================================================
#
# Automates the full FISCO BCOS contract lifecycle:
#   1. Pre-flight checks  - console installed, node reachable
#   2. Compile            - Storage.sol and Sharing.sol via FISCO console
#   3. Deploy             - both contracts, capture on-chain addresses
#   4. ABI sync           - overwrite platform-fisco/src/main/resources/abi/
#   5. Address write-back - update FISCO_STORAGE_CONTRACT / FISCO_SHARING_CONTRACT in .env
#   6. Verify             - call read-only methods to confirm deployment
#
# Usage:
#   ./scripts/contract-deploy.sh [options]
#
# Options:
#   --console-dir DIR    FISCO BCOS console directory (default: ~/fisco/console)
#   --env-file FILE      Target .env file for address write-back (default: <project>/.env)
#   --skip-verify        Skip post-deploy verification phase
#   --dry-run            Print planned actions without executing them
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
            export "${key}=${value}"
        fi
    done < "$env_file"
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
SKIP_VERIFY=false
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
        --skip-verify) SKIP_VERIFY=true; shift ;;
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

# Load current .env for FISCO config (node address, etc.)
load_env_file "$ENV_FILE"

# Enable errexit after environment loading
set -e

# ==============================================================================
# Paths
# ==============================================================================
CONTRACT_SRC_DIR="$PROJECT_ROOT/platform-fisco/contract"
ABI_DEST_DIR="$PROJECT_ROOT/platform-fisco/src/main/resources/abi"
CONSOLE_CONTRACT_DIR="$CONSOLE_DIR/contracts/solidity"
CONSOLE_SDK_DIR="$CONSOLE_DIR/contracts/sdk"

# FISCO BCOS node address from .env (fallback to default)
FISCO_HOST="${FISCO_PEER_ADDRESS:-127.0.0.1:20200}"
FISCO_NODE_HOST="${FISCO_HOST%%:*}"
FISCO_NODE_PORT="${FISCO_HOST##*:}"

# ==============================================================================
# Header
# ==============================================================================
echo
echo "${BOLD}RecordPlatform Smart Contract Deployment${RESET}"
echo "Project root  : $PROJECT_ROOT"
echo "Console dir   : $CONSOLE_DIR"
echo "Env file      : $ENV_FILE"
echo "Skip verify   : $SKIP_VERIFY"
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

# 1c. FISCO BCOS node is reachable
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

# 1d. Source contracts exist
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

if [ "$DRY_RUN" = true ]; then
    dry "mkdir -p $CONSOLE_CONTRACT_DIR"
    dry "cp $CONTRACT_SRC_DIR/Storage.sol $CONSOLE_CONTRACT_DIR/"
    dry "cp $CONTRACT_SRC_DIR/Sharing.sol  $CONSOLE_CONTRACT_DIR/"
    dry "cd $CONSOLE_DIR && echo 'compileByContractLoader' | ./console.sh"
    ok "Dry-run: compile step would copy contracts and invoke FISCO console"
else
    # Copy .sol files into the console's expected location
    mkdir -p "$CONSOLE_CONTRACT_DIR"
    cp "$CONTRACT_SRC_DIR/Storage.sol" "$CONSOLE_CONTRACT_DIR/"
    cp "$CONTRACT_SRC_DIR/Sharing.sol"  "$CONSOLE_CONTRACT_DIR/"
    ok "Contracts copied to console: $CONSOLE_CONTRACT_DIR"

    # FISCO BCOS console compiles .sol files automatically on deploy.
    # For explicit compilation (generates Java wrappers + ABI), run:
    COMPILE_OUTPUT=""
    COMPILE_OUTPUT=$(cd "$CONSOLE_DIR" && printf 'compileByContractLoader org.fisco.bcos.sdk.v3.contract.loadcontract.ContractLoader\nexit\n' | timeout 60 ./console.sh 2>&1) || true

    # Check that compilation produced ABI/BIN artefacts
    if [ -f "$CONSOLE_SDK_DIR/Storage.abi" ] && [ -f "$CONSOLE_SDK_DIR/Sharing.abi" ]; then
        ok "Compilation artefacts found in $CONSOLE_SDK_DIR"
    else
        # Fallback: FISCO 3.x console compiles on first deploy; note it here
        warn "Explicit compile step did not produce artefacts in $CONSOLE_SDK_DIR"
        info "Contracts will be compiled automatically during deployment (Phase 3)"
    fi
fi

# ==============================================================================
# Phase 3: Deploy Contracts
# ==============================================================================
section 3 "Deploy Contracts"

deploy_contract() {
    local name="$1"      # e.g. "Storage"
    local result_var="$2" # name of the variable to set with the address

    if [ "$DRY_RUN" = true ]; then
        dry "cd $CONSOLE_DIR && printf 'deploy $name\\nexit\\n' | timeout 120 ./console.sh"
        eval "$result_var=0xDRYRUN0000000000000000000000000000000001"
        ok "Dry-run: $name would be deployed"
        return
    fi

    info "Deploying $name.sol (this may take up to 120 s)..."

    local output
    output=$(
        cd "$CONSOLE_DIR"
        printf 'deploy %s\nexit\n' "$name" | timeout 120 ./console.sh 2>&1
    ) || true

    # Extract contract address from console output.
    # FISCO BCOS 3.x output format: "contract address: 0x<40 hex chars>"
    local addr
    addr=$(echo "$output" | grep -o 'contract address: 0x[0-9a-fA-F]\{40\}' | head -1 | grep -o '0x[0-9a-fA-F]\{40\}') || true

    if [[ "$addr" =~ ^0x[0-9a-fA-F]{40}$ ]]; then
        ok "$name deployed at $addr"
        eval "$result_var='$addr'"
    else
        fail "$name deployment failed — no valid address in console output"
        info "Console output (last 20 lines):"
        echo "$output" | tail -20 | sed 's/^/    /'
        return 1
    fi
}

STORAGE_ADDR=""
SHARING_ADDR=""

deploy_contract "Storage" "STORAGE_ADDR"
deploy_contract "Sharing" "SHARING_ADDR"

if [ $FAILURES -gt 0 ]; then
    echo
    echo "${RED}${BOLD}Deployment failed — skipping ABI sync, write-back and verification.${RESET}"
    exit 1
fi

# ==============================================================================
# Phase 4: ABI Sync
# ==============================================================================
section 4 "ABI Sync"

sync_abi() {
    local name="$1"       # e.g. "Storage"
    local abi_src=""

    # Look for the compiled ABI in the console SDK output directory
    if [ -f "$CONSOLE_SDK_DIR/${name}.abi" ]; then
        abi_src="$CONSOLE_SDK_DIR/${name}.abi"
    elif [ -f "$CONSOLE_DIR/contracts/sdk/${name}.abi" ]; then
        abi_src="$CONSOLE_DIR/contracts/sdk/${name}.abi"
    fi

    if [ "$DRY_RUN" = true ]; then
        if [ -n "$abi_src" ]; then
            dry "cp $abi_src $ABI_DEST_DIR/${name}.abi"
            ok "Dry-run: $name.abi would be synced from console output"
        else
            warn "Dry-run: $name.abi not found in $CONSOLE_SDK_DIR — sync would be skipped"
        fi
        return
    fi

    mkdir -p "$ABI_DEST_DIR"

    if [ -n "$abi_src" ]; then
        # Compute SHA-256 of existing ABI for change detection
        local old_hash="" new_hash=""
        if [ -f "$ABI_DEST_DIR/${name}.abi" ] && has_cmd sha256sum; then
            old_hash=$(sha256sum "$ABI_DEST_DIR/${name}.abi" | awk '{print $1}')
            new_hash=$(sha256sum "$abi_src" | awk '{print $1}')
        fi

        cp "$abi_src" "$ABI_DEST_DIR/${name}.abi"

        if [ -n "$old_hash" ] && [ "$old_hash" = "$new_hash" ]; then
            ok "$name.abi unchanged (SHA-256 match)"
        elif [ -n "$old_hash" ]; then
            ok "$name.abi updated (was: ${old_hash:0:16}…, now: ${new_hash:0:16}…)"
        else
            ok "$name.abi written to $ABI_DEST_DIR/"
        fi
    else
        warn "$name.abi not found in console output — keeping existing file"
        info "Expected location: $CONSOLE_SDK_DIR/${name}.abi"
        info "The existing ABI in $ABI_DEST_DIR/ will remain unchanged"
    fi
}

sync_abi "Storage"
sync_abi "Sharing"

# ==============================================================================
# Phase 5: Address Write-back
# ==============================================================================
section 5 "Address Write-back"

writeback_env() {
    local key="$1"
    local new_addr="$2"
    local env_file="$3"

    if [ "$DRY_RUN" = true ]; then
        dry "sed -i 's|^${key}=.*|${key}=${new_addr}|' $env_file"
        ok "Dry-run: $key would be set to $new_addr in $env_file"
        return
    fi

    if [ ! -f "$env_file" ]; then
        warn "$env_file not found — cannot write back $key"
        info "Create it from the template: cp .env.example .env"
        return
    fi

    local old_val=""
    old_val=$(grep -E "^${key}=" "$env_file" | head -1 | cut -d'=' -f2-) || true

    if grep -qE "^${key}=" "$env_file"; then
        # Key exists — update in place
        sed -i "s|^${key}=.*|${key}=${new_addr}|" "$env_file"
    else
        # Key absent — append
        echo "${key}=${new_addr}" >> "$env_file"
    fi

    if [ -n "$old_val" ] && [ "$old_val" != "$new_addr" ]; then
        ok "$key updated: ${old_val:0:14}… → $new_addr"
    elif [ -n "$old_val" ]; then
        ok "$key unchanged (already $new_addr)"
    else
        ok "$key added: $new_addr"
    fi
}

writeback_env "FISCO_STORAGE_CONTRACT" "$STORAGE_ADDR" "$ENV_FILE"
writeback_env "FISCO_SHARING_CONTRACT" "$SHARING_ADDR" "$ENV_FILE"

# ==============================================================================
# Phase 6: Deployment Verification
# ==============================================================================
section 6 "Deployment Verification"

if [ "$SKIP_VERIFY" = true ]; then
    info "Verification skipped (--skip-verify)"
else
    verify_contract() {
        local name="$1"
        local addr="$2"
        local method="$3"
        local args="${4:-}"

        if [ "$DRY_RUN" = true ]; then
            dry "cd $CONSOLE_DIR && printf 'call $name $addr $method $args\\nexit\\n' | timeout 30 ./console.sh"
            ok "Dry-run: $name.$method() would be called for verification"
            return
        fi

        info "Calling $name.$method() at ${addr:0:14}…"

        local cmd_input
        if [ -n "$args" ]; then
            cmd_input="call $name $addr $method $args"
        else
            cmd_input="call $name $addr $method"
        fi

        local output
        output=$(
            cd "$CONSOLE_DIR"
            printf '%s\nexit\n' "$cmd_input" | timeout 30 ./console.sh 2>&1
        ) || true

        # A successful call returns "Return value" or "[]" (empty result)
        # A failure contains "Error" or "revert" or no "Return value"
        if echo "$output" | grep -qiE 'Return value|transaction hash'; then
            ok "$name.$method() returned successfully"
        elif echo "$output" | grep -qiE 'Error|revert|Exception|failed'; then
            fail "$name.$method() call failed"
            info "Console output (last 10 lines):"
            echo "$output" | tail -10 | sed 's/^/    /'
        else
            warn "$name.$method() response unclear — manual verification recommended"
            info "Console output (last 5 lines):"
            echo "$output" | tail -5 | sed 's/^/    /'
        fi
    }

    # Verify Storage: getUserFiles(string) with a dummy uploader — expects empty list
    verify_contract "Storage" "$STORAGE_ADDR" "getUserFiles" '"__verify__"'

    # Verify Sharing: getShareInfo(string) with a nonexistent code — expects empty struct
    verify_contract "Sharing" "$SHARING_ADDR" "getShareInfo" '"__verify__"'
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
echo "  Sharing contract  : ${GREEN}$SHARING_ADDR${RESET}"
echo "  ABI destination   : $ABI_DEST_DIR/"
echo "  Env file updated  : $ENV_FILE"
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
