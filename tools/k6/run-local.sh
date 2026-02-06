#!/usr/bin/env bash
set -euo pipefail

# 打印脚本帮助信息。
print_usage() {
  cat <<'USAGE'
Usage: tools/k6/run-local.sh [options]

Options:
  --profile <smoke|load>        运行档位（默认：smoke）
  --scenario <all|file-query|chunk-upload|core-mixed>
                                 场景过滤（默认：all）
  --run-id <id>                 自定义运行 ID（默认：当前时间）
  --result-dir <dir>            自定义结果目录（默认：tools/k6/results/<run-id>）
  --help                         显示帮助
USAGE
}

# 校验 k6 是否已安装。
ensure_k6_installed() {
  if ! command -v k6 >/dev/null 2>&1; then
    echo "[ERROR] 未检测到 k6，请先安装（macOS: brew install k6）" >&2
    exit 1
  fi
}

# 根据 profile 选择 suite 脚本。
resolve_suite_script() {
  local profile="$1"
  case "$profile" in
    smoke)
      echo "tools/k6/suites/local-smoke.js"
      ;;
    load)
      echo "tools/k6/suites/local-load.js"
      ;;
    *)
      echo "" ;;
  esac
}

# 解析命令行参数并导出运行环境。
main() {
  local profile="${K6_PROFILE:-smoke}"
  local scenario="${K6_SCENARIO:-all}"
  local run_id="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
  local result_dir="${RESULT_DIR:-}"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --profile)
        profile="$2"
        shift 2
        ;;
      --scenario)
        scenario="$2"
        shift 2
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

  if [[ -z "$result_dir" ]]; then
    result_dir="tools/k6/results/${run_id}"
  fi

  local suite_script
  suite_script="$(resolve_suite_script "$profile")"
  if [[ -z "$suite_script" ]]; then
    echo "[ERROR] 不支持的 profile: $profile（可选：smoke|load）" >&2
    exit 1
  fi

  mkdir -p "$result_dir"

  export K6_PROFILE="$profile"
  export K6_SCENARIO="$scenario"
  export RUN_ID="$run_id"
  export RESULT_DIR="$result_dir"

  echo "[INFO] profile=$K6_PROFILE scenario=$K6_SCENARIO run_id=$RUN_ID"
  echo "[INFO] result_dir=$RESULT_DIR"
  echo "[INFO] suite=$suite_script"

  k6 run "$suite_script"
}

main "$@"
