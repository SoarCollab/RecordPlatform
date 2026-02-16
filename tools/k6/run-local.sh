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
  --engine <auto|local|docker>  执行引擎（默认：auto）
  --run-id <id>                 自定义运行 ID（默认：当前时间）
  --result-dir <dir>            自定义结果目录（默认：tools/k6/results/<run-id>）
  --help                         显示帮助
USAGE
}

# 校验命令是否可用。
#
# @param $1 命令名
# @returns 0=可用，1=不可用
has_command() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1
}

# 根据输入参数解析最终执行引擎。
#
# @param $1 目标引擎（auto|local|docker）
# @returns 通过 stdout 输出最终引擎（local|docker）
resolve_engine() {
  local preferred_engine="$1"
  case "$preferred_engine" in
    local)
      if ! has_command k6; then
        echo "[ERROR] 已指定 --engine local，但未检测到 k6（macOS 可执行: brew install k6）。" >&2
        exit 1
      fi
      echo "local"
      ;;
    docker)
      if ! has_command docker; then
        echo "[ERROR] 已指定 --engine docker，但未检测到 Docker。请先安装并启动 Docker Desktop。" >&2
        exit 1
      fi
      echo "docker"
      ;;
    auto)
      if has_command k6; then
        echo "local"
        return
      fi
      if has_command docker; then
        echo "docker"
        return
      fi
      echo "[ERROR] 未检测到 k6 或 Docker。请安装其一：" >&2
      echo "        - macOS 安装 k6: brew install k6" >&2
      echo "        - 或安装并启动 Docker Desktop（用于 grafana/k6 镜像执行）" >&2
      exit 1
      ;;
    *)
      echo "[ERROR] 不支持的 engine: ${preferred_engine}（可选：auto|local|docker）" >&2
      exit 1
      ;;
  esac
}

# 在 Docker 引擎下将 loopback 地址改写为 host.docker.internal，确保容器可访问宿主机服务。
#
# @param $1 原始 URL
# @returns Docker 可访问的 URL
rewrite_loopback_base_url_for_docker() {
  local raw_url="$1"
  local scheme
  local auth
  local host
  local suffix
  local normalized_host

  if [[ "$raw_url" =~ ^(https?://)([^/@]+@)?(\[[0-9A-Fa-f:]+\]|[^/:?#]+)(.*)$ ]]; then
    scheme="${BASH_REMATCH[1]}"
    auth="${BASH_REMATCH[2]}"
    host="${BASH_REMATCH[3]}"
    suffix="${BASH_REMATCH[4]}"

    normalized_host="${host#[}"
    normalized_host="${normalized_host%]}"
    normalized_host="$(printf '%s' "$normalized_host" | tr '[:upper:]' '[:lower:]')"

    if [[ "$normalized_host" == "localhost" || "$normalized_host" == "127.0.0.1" || "$normalized_host" == "::1" ]]; then
      printf '%s%s%s%s' "$scheme" "$auth" "host.docker.internal" "$suffix"
      return
    fi
  fi

  printf '%s' "$raw_url"
}

# 解析本次运行实际使用的 BASE_URL，并在 Docker 引擎下修正 loopback 地址。
#
# @param $1 engine
# @param $2 原始 BASE_URL
# @returns 实际执行使用的 BASE_URL
resolve_runtime_base_url() {
  local engine="$1"
  local raw_base_url="$2"

  if [[ "$engine" == "docker" ]]; then
    rewrite_loopback_base_url_for_docker "$raw_base_url"
    return
  fi

  printf '%s' "$raw_base_url"
}

# 判断路径是否为绝对路径。
#
# @param $1 路径
# @returns 0=绝对路径，1=非绝对路径
is_absolute_path() {
  local path="$1"
  [[ "$path" == /* ]]
}

# 将已存在目录解析为规范化绝对路径（解析 . / .. 与符号链接）。
#
# @param $1 目录路径（绝对或相对，且必须已存在）
# @returns 规范化后的绝对路径
resolve_existing_dir_absolute_path() {
  local dir_path="$1"
  (
    cd "$dir_path" >/dev/null 2>&1
    pwd -P
  )
}

# 判断是否需要为 Docker 增加 host-gateway 映射。
#
# 仅在 Linux 且 BASE_URL 已使用 host.docker.internal 时启用，
# 用于保证容器内可解析宿主机地址。
#
# @returns 0=需要添加映射，1=不需要
should_add_docker_host_gateway_alias() {
  local os_name
  os_name="$(uname -s 2>/dev/null || true)"
  if [[ "$os_name" != "Linux" ]]; then
    return 1
  fi
  [[ "${BASE_URL:-}" == *"host.docker.internal"* ]]
}

# 对 base URL 做脱敏，避免在运行元数据中泄漏凭证信息。
#
# @param $1 原始 URL
# @returns 脱敏后的 URL
mask_base_url() {
  local raw_url="$1"
  local masked="$raw_url"
  masked="$(printf '%s' "$masked" | sed -E 's#(https?://)[^/@]+@#\1***@#')"
  masked="${masked%%\?*}"
  masked="${masked%%#*}"
  printf '%s' "$masked"
}

# 写入运行元数据，供审计与报告回填使用。
#
# @param $1 结果目录
# @param $2 运行 ID
# @param $3 profile
# @param $4 scenario
# @param $5 engine
write_run_meta() {
  local result_dir="$1"
  local run_id="$2"
  local profile="$3"
  local scenario="$4"
  local engine="$5"
  local timestamp
  local base_url
  local base_url_mask

  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  base_url="${BASE_URL:-http://localhost:8000/record-platform/api/v1}"
  base_url_mask="$(mask_base_url "$base_url")"

  cat >"${result_dir}/run-meta.json" <<EOF
{
  "runId": "${run_id}",
  "profile": "${profile}",
  "scenario": "${scenario}",
  "engine": "${engine}",
  "timestamp": "${timestamp}",
  "baseUrlMask": "${base_url_mask}"
}
EOF
}

# 通过本地 k6 二进制执行压测。
#
# @param $1 suite 脚本路径
run_with_local_k6() {
  local suite_script="$1"
  k6 run "$suite_script"
}

# 通过 Docker 中的 grafana/k6 执行压测。
#
# @param $1 suite 脚本路径
run_with_docker_k6() {
  local suite_script="$1"
  local result_dir="${RESULT_DIR:-}"
  local result_dir_abs=""
  local -a docker_args
  docker_args=(
    run
    --rm
    -v "$PWD:/workspace"
    -w /workspace
  )

  if should_add_docker_host_gateway_alias; then
    docker_args+=(--add-host "host.docker.internal:host-gateway")
  fi

  if [[ -n "$result_dir" ]]; then
    result_dir_abs="$(resolve_existing_dir_absolute_path "$result_dir")"
    docker_args+=(-v "$result_dir_abs:$result_dir_abs")
  fi

  docker "${docker_args[@]}" \
    -e BASE_URL="${BASE_URL:-}" \
    -e TENANT_ID="${TENANT_ID:-}" \
    -e USERNAME="${USERNAME:-}" \
    -e PASSWORD="${PASSWORD:-}" \
    -e K6_PROFILE="${K6_PROFILE:-}" \
    -e K6_SCENARIO="${K6_SCENARIO:-}" \
    -e RUN_ID="${RUN_ID:-}" \
    -e RESULT_DIR="${result_dir_abs:-${RESULT_DIR:-}}" \
    -e CLEANUP="${CLEANUP:-}" \
    -e CI_INCLUDE_CHUNK="${CI_INCLUDE_CHUNK:-}" \
    -e TOTAL_CHUNKS="${TOTAL_CHUNKS:-}" \
    -e CHUNK_SIZE="${CHUNK_SIZE:-}" \
    grafana/k6:0.49.0 \
    run "$suite_script"
}

# 根据执行引擎分发到对应的运行入口。
#
# @param $1 engine
# @param $2 suite 脚本路径
run_k6_suite() {
  local engine="$1"
  local suite_script="$2"
  if [[ "$engine" == "local" ]]; then
    run_with_local_k6 "$suite_script"
  elif [[ "$engine" == "docker" ]]; then
    run_with_docker_k6 "$suite_script"
  else
    echo "[ERROR] 非法执行引擎: $engine" >&2
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
  local engine="${K6_ENGINE:-auto}"
  local default_base_url="http://localhost:8000/record-platform/api/v1"
  local original_base_url="${BASE_URL:-$default_base_url}"
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
      --engine)
        engine="$2"
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

  local resolved_engine
  resolved_engine="$(resolve_engine "$engine")"
  local runtime_base_url
  runtime_base_url="$(resolve_runtime_base_url "$resolved_engine" "$original_base_url")"

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
  result_dir="$(resolve_existing_dir_absolute_path "$result_dir")"

  export K6_PROFILE="$profile"
  export K6_SCENARIO="$scenario"
  export K6_ENGINE="$resolved_engine"
  export BASE_URL="$runtime_base_url"
  export RUN_ID="$run_id"
  export RESULT_DIR="$result_dir"

  if [[ "$resolved_engine" == "docker" && "$runtime_base_url" != "$original_base_url" ]]; then
    echo "[INFO] docker engine 检测到 loopback BASE_URL，已改写为: $(mask_base_url "$runtime_base_url")"
  fi

  write_run_meta "$result_dir" "$run_id" "$profile" "$scenario" "$resolved_engine"

  echo "[INFO] profile=$K6_PROFILE scenario=$K6_SCENARIO run_id=$RUN_ID"
  echo "[INFO] engine=$K6_ENGINE"
  echo "[INFO] result_dir=$RESULT_DIR"
  echo "[INFO] suite=$suite_script"

  run_k6_suite "$resolved_engine" "$suite_script"
}

main "$@"
