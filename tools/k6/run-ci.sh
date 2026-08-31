#!/usr/bin/env bash
set -euo pipefail

# 打印脚本帮助信息。
print_usage() {
  cat <<'USAGE'
Usage: tools/k6/run-ci.sh [options]

Options:
  --include-chunk               在 CI smoke 中追加 chunk-upload 场景
  --include-direct              在 CI smoke 中追加 direct-path 场景
  --scenario <all|file-query|core-mixed|chunk-upload|direct-path>
                                 场景过滤（默认：all）
  --engine <auto|local|docker>  执行引擎（默认：auto；auto 仅自动使用本地 k6）
  --run-id <id>                 自定义运行 ID（默认：ci-当前时间）
  --result-dir <dir>            自定义结果目录（默认：tools/k6/results/<run-id>）
  --help                         显示帮助

Environment (required):
  BASE_URL
  TENANT_ID
  USERNAME
  PASSWORD
  K6_DOCKER_IMAGE               Docker 引擎必填，且必须使用 digest 固定镜像
  K6_CA_CERT_FILE               Docker 可选：本地可信 PEM CA/自签公共证书文件
  ENVIRONMENT_FINGERPRINT       同环境 baseline 比较标识（默认按目标掩码/引擎/OS 生成）
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

# 校验运行标识可安全用于文件名、会话名和精确清理关键字。
#
# @param $1 运行 ID
validate_run_id() {
  local run_id="$1"
  if [[ ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
    echo "[ERROR] run-id 必须为 1-64 位，首位为字母或数字，其余仅允许字母、数字、点、下划线或连字符。" >&2
    exit 1
  fi
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
      if [[ "${K6_DOCKER_IMAGE:-}" != *@sha256:* ]]; then
        echo "[ERROR] Docker 引擎要求设置 K6_DOCKER_IMAGE，且必须使用 digest 固定镜像，例如 grafana/k6@sha256:<digest>。" >&2
        exit 1
      fi
      echo "docker"
      ;;
    auto)
      if has_command k6; then
        echo "local"
        return
      fi
      echo "[ERROR] 未检测到本地 k6。auto 模式不会自动回退到 Docker。" >&2
      echo "        - macOS 安装 k6: brew install k6" >&2
      echo "        - 或显式使用 --engine docker，并设置 digest 固定的 K6_DOCKER_IMAGE。" >&2
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

# 生成不包含凭证的环境指纹，供 baseline 比较前置校验。
#
# @param $1 脱敏目标地址
# @param $2 engine
# @returns 环境指纹
build_environment_fingerprint() {
  local target_mask="$1"
  local engine="$2"
  local source="target=${target_mask};engine=${engine};artifact=${K6_ENGINE_ARTIFACT:-unavailable};os=$(uname -s);arch=$(uname -m)"
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$source" | shasum -a 256 | awk '{print $1}'
    return
  fi
  printf '%s' "$source" | sha256sum | awk '{print $1}'
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
  local cpu_count
  local memory_bytes
  local os_name
  local architecture
  local k6_version

  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  base_url="${BASE_URL:-http://localhost:8000/record-platform/api/v1}"
  base_url_mask="$(mask_base_url "$base_url")"
  os_name="$(uname -s)"
  architecture="$(uname -m)"
  cpu_count="$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo unavailable)"
  memory_bytes="$(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo 2>/dev/null || echo unavailable)"
  k6_version="${K6_ENGINE_ARTIFACT:-unavailable:not-configured}"

  cat >"${result_dir}/run-meta.json" <<EOF
{
  "runId": "${run_id}",
  "profile": "${profile}",
  "scenario": "${scenario}",
  "engine": "${engine}",
  "engineArtifact": "${K6_ENGINE_ARTIFACT:-unavailable:not-configured}",
  "logOutput": "${K6_EFFECTIVE_LOG_OUTPUT:-stderr}",
  "timestamp": "${timestamp}",
  "baseUrlMask": "${base_url_mask}",
  "environmentFingerprint": "${ENVIRONMENT_FINGERPRINT}",
  "host": {
    "os": "${os_name}",
    "architecture": "${architecture}",
    "cpuCount": "${cpu_count}",
    "memoryBytes": "${memory_bytes}",
    "k6Version": "${k6_version}"
  }
}
EOF
}

# 判断当前 CI 套件是否会访问带签名查询参数的对象存储 URL。
#
# @param $1 scenario
# @param $2 include_direct
# @returns 0=包含 direct-path，1=不包含
includes_direct_path() {
  local scenario="$1"
  local include_direct="$2"
  [[ "$scenario" == "direct-path" || ("$scenario" == "all" && "$include_direct" == "true") ]]
}

# 通过本地 k6 二进制执行压测。
#
# @param $1 suite 脚本路径
run_with_local_k6() {
  local suite_script="$1"
  k6 run --log-output "${K6_EFFECTIVE_LOG_OUTPUT:-stderr}" "$suite_script"
}

# 通过 Docker 中的 grafana/k6 执行压测。
#
# @param $1 suite 脚本路径
run_with_docker_k6() {
  local suite_script="$1"
  local result_dir="${RESULT_DIR:-}"
  local result_dir_abs=""
  local docker_image="${K6_DOCKER_IMAGE:?K6_DOCKER_IMAGE is required for docker engine}"
  local -a docker_args
  docker_args=(
    run
    --rm
    -v "$PWD:/workspace:ro"
    -w /workspace
  )

  # Mount only the public trust bundle; keep TLS verification enabled.
  if [[ -n "${K6_CA_CERT_FILE:-}" ]]; then
    if [[ ! -f "$K6_CA_CERT_FILE" || ! -r "$K6_CA_CERT_FILE" ]]; then
      echo '[ERROR] K6_CA_CERT_FILE must be a readable public PEM certificate file' >&2
      return 1
    fi
    local ca_file
    ca_file="$(cd "$(dirname "$K6_CA_CERT_FILE")" && pwd)/$(basename "$K6_CA_CERT_FILE")"
    docker_args+=(-v "$ca_file:/etc/ssl/certs/record-platform-ca.pem:ro"
      -e SSL_CERT_FILE=/etc/ssl/certs/record-platform-ca.pem)
  fi

  if should_add_docker_host_gateway_alias; then
    docker_args+=(--add-host "host.docker.internal:host-gateway")
  fi

  if [[ -n "$result_dir" ]]; then
    result_dir_abs="$(resolve_existing_dir_absolute_path "$result_dir")"
    docker_args+=(-v "$result_dir_abs:$result_dir_abs:rw")
  fi

  docker "${docker_args[@]}" \
    -e BASE_URL \
    -e TENANT_ID \
    -e USERNAME \
    -e PASSWORD \
    -e K6_PROFILE \
    -e K6_SCENARIO \
    -e K6_SUITE \
    -e K6_ENGINE \
    -e K6_ENGINE_ARTIFACT \
    -e RUN_ID \
    -e RESULT_DIR \
    -e CLEANUP \
    -e CI_INCLUDE_CHUNK \
    -e CI_INCLUDE_DIRECT \
    -e TOTAL_CHUNKS \
    -e CHUNK_SIZE \
    -e DIRECT_TOTAL_CHUNKS \
    -e DIRECT_CHUNK_SIZE \
    -e DIRECT_P99_BUDGET_MS \
    -e DIRECT_RESOURCE_SNAPSHOT_PATH \
    -e DIRECT_LIFECYCLE_SNAPSHOT_PATH \
    -e ENVIRONMENT_FINGERPRINT \
    -e VUS \
    -e DURATION \
    -e MIX_QUERY_WEIGHT \
    -e CI_FILE_QUERY_VUS \
    -e CI_FILE_QUERY_DURATION \
    -e CI_CORE_MIXED_VUS \
    -e CI_CORE_MIXED_DURATION \
    -e CI_CORE_MIXED_START_TIME \
    -e CI_CHUNK_UPLOAD_VUS \
    -e CI_CHUNK_UPLOAD_DURATION \
    -e CI_CHUNK_START_TIME \
    -e CI_DIRECT_PATH_VUS \
    -e CI_DIRECT_PATH_DURATION \
    -e CI_DIRECT_PATH_START_TIME \
    "$docker_image" \
    run --log-output "${K6_EFFECTIVE_LOG_OUTPUT:-stderr}" "$suite_script"
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
  local include_direct="${CI_INCLUDE_DIRECT:-false}"
  local scenario="${K6_SCENARIO:-all}"
  local engine="${K6_ENGINE:-auto}"
  local default_base_url="http://localhost:8000/record-platform/api/v1"
  local original_base_url="${BASE_URL:-$default_base_url}"
  local run_id="${RUN_ID:-ci-$(date +%Y%m%d%H%M%S)}"
  local result_dir="${RESULT_DIR:-}"

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --include-chunk)
        include_chunk="true"
        shift
        ;;
      --include-direct)
        include_direct="true"
        shift
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

  validate_run_id "$run_id"

  local resolved_engine
  resolved_engine="$(resolve_engine "$engine")"
  validate_required_env
  local runtime_base_url
  runtime_base_url="$(resolve_runtime_base_url "$resolved_engine" "$original_base_url")"

  if [[ -z "$result_dir" ]]; then
    result_dir="tools/k6/results/${run_id}"
  fi

  mkdir -p "$result_dir"
  result_dir="$(resolve_existing_dir_absolute_path "$result_dir")"

  export K6_PROFILE="smoke"
  export K6_SCENARIO="$scenario"
  export K6_SUITE="ci"
  export K6_ENGINE="$resolved_engine"
  if [[ "$resolved_engine" == "docker" ]]; then
    export K6_ENGINE_ARTIFACT="$K6_DOCKER_IMAGE"
  else
    export K6_ENGINE_ARTIFACT="$(k6 version 2>/dev/null | head -1 | tr '"' "'")"
  fi
  export BASE_URL="$runtime_base_url"
  export CI_INCLUDE_CHUNK="$include_chunk"
  export CI_INCLUDE_DIRECT="$include_direct"
  export RUN_ID="$run_id"
  export RESULT_DIR="$result_dir"
  export ENVIRONMENT_FINGERPRINT="${ENVIRONMENT_FINGERPRINT:-$(build_environment_fingerprint "$(mask_base_url "$runtime_base_url")" "$resolved_engine")}"
  if [[ ! "$ENVIRONMENT_FINGERPRINT" =~ ^[A-Za-z0-9._:-]{1,128}$ ]]; then
    echo "[ERROR] ENVIRONMENT_FINGERPRINT 仅允许 1-128 位字母、数字、点、下划线、冒号或连字符。" >&2
    exit 1
  fi
  if includes_direct_path "$scenario" "$include_direct"; then
    export K6_EFFECTIVE_LOG_OUTPUT="none"
  else
    export K6_EFFECTIVE_LOG_OUTPUT="${K6_LOG_OUTPUT:-stderr}"
  fi

  if [[ "$resolved_engine" == "docker" && "$runtime_base_url" != "$original_base_url" ]]; then
    echo "[INFO] docker engine 检测到 loopback BASE_URL，已改写为: $(mask_base_url "$runtime_base_url")"
  fi

  write_run_meta "$result_dir" "$run_id" "$K6_PROFILE" "$K6_SCENARIO" "$resolved_engine"

  echo "[INFO] BASE_URL=$(mask_base_url "$BASE_URL")"
  echo "[INFO] run_id=$RUN_ID result_dir=$RESULT_DIR include_chunk=$CI_INCLUDE_CHUNK include_direct=$CI_INCLUDE_DIRECT engine=$K6_ENGINE"
  echo "[INFO] k6_log_output=$K6_EFFECTIVE_LOG_OUTPUT"

  run_k6_suite "$resolved_engine" "tools/k6/suites/ci-smoke.js"
}

main "$@"
