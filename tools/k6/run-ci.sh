#!/usr/bin/env bash
set -euo pipefail

# 打印脚本帮助信息。
print_usage() {
  cat <<'USAGE'
Usage: tools/k6/run-ci.sh [options]

Options:
  --include-chunk               在 CI smoke 中追加 chunk-upload 场景
  --run-id <id>                 自定义运行 ID（默认：ci-当前时间）
  --result-dir <dir>            自定义结果目录（默认：tools/k6/results/<run-id>）
  --help                         显示帮助

Environment (required):
  BASE_URL
  TENANT_ID
  USERNAME
  PASSWORD
USAGE
}

# 校验 k6 是否已安装。
ensure_k6_installed() {
  if ! command -v k6 >/dev/null 2>&1; then
    echo "[ERROR] 未检测到 k6，请先安装。" >&2
    exit 1
  fi
}

# 校验 CI 运行必须的环境变量。
validate_required_env() {
  local missing=0
  for name in BASE_URL TENANT_ID USERNAME PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
      echo "[ERROR] 缺少必填环境变量: $name" >&2
      missing=1
    fi
  done

  if [[ "$missing" -ne 0 ]]; then
    exit 1
  fi
}

# 主流程：解析参数并运行 CI smoke。
main() {
  local include_chunk="${CI_INCLUDE_CHUNK:-false}"
  local run_id="${RUN_ID:-ci-$(date +%Y%m%d%H%M%S)}"
  local result_dir="${RESULT_DIR:-}"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --include-chunk)
        include_chunk="true"
        shift
        ;;
      --run-id)
        run_id="$2"
        shift 2
        ;;
      --result-dir)
        result_dir="$2"
        shift 2
        ;;
      --help)
        print_usage
        exit 0
        ;;
      *)
        echo "[ERROR] 未知参数: $1" >&2
        print_usage
        exit 1
        ;;
    esac
  done

  ensure_k6_installed
  validate_required_env

  if [[ -z "$result_dir" ]]; then
    result_dir="tools/k6/results/${run_id}"
  fi

  mkdir -p "$result_dir"

  export K6_PROFILE="smoke"
  export K6_SCENARIO="${K6_SCENARIO:-all}"
  export CI_INCLUDE_CHUNK="$include_chunk"
  export RUN_ID="$run_id"
  export RESULT_DIR="$result_dir"

  echo "[INFO] BASE_URL=$BASE_URL"
  echo "[INFO] TENANT_ID=$TENANT_ID"
  echo "[INFO] USERNAME=$USERNAME"
  echo "[INFO] run_id=$RUN_ID result_dir=$RESULT_DIR include_chunk=$CI_INCLUDE_CHUNK"

  k6 run tools/k6/suites/ci-smoke.js
}

main "$@"
