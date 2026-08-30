#!/bin/bash
# RecordPlatform 服务管理脚本
# 用法: ./start.sh <命令> [服务...] [选项]
#
# 命令:
#   start     启动服务 (默认)
#   stop      停止服务
#   restart   重启服务
#   status    查看服务状态
#
# 服务:
#   all       全部服务 (默认)
#   storage   存储服务
#   fisco     区块链服务
#   backend   后端服务
#
# 选项:
#   --skywalking    启用 SkyWalking 链路追踪
#   --otel          启用 OpenTelemetry 链路追踪
#   --foreground    前台运行 (仅对单服务 start 有效)
#   --profile=xxx   指定 Spring Profile (默认 prod)
#   --help          显示帮助信息

# 加载环境配置
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/env.sh"

# ================================
# 服务定义
# ================================
SERVICE_ORDER=("storage" "fisco" "backend")

HEALTH_CHECK_TIMEOUT=${HEALTH_CHECK_TIMEOUT:-60}
HEALTH_CHECK_INTERVAL=${HEALTH_CHECK_INTERVAL:-2}
HEALTH_CHECK_REQUEST_TIMEOUT=${HEALTH_CHECK_REQUEST_TIMEOUT:-3}

# 获取服务对应的 JAR 路径。
get_service_jar() {
    case "$1" in
        storage) echo "$STORAGE_JAR" ;;
        fisco) echo "$FISCO_JAR" ;;
        backend) echo "$BACKEND_JAR" ;;
        *) echo "" ;;
    esac
}

# 获取服务展示名称。
get_service_name() {
    case "$1" in
        storage) echo "存储服务 (Storage)" ;;
        fisco) echo "区块链服务 (FISCO)" ;;
        backend) echo "后端服务 (Backend)" ;;
        *) echo "$1" ;;
    esac
}

# 获取链路追踪服务名。
get_sw_name() {
    case "$1" in
        storage) echo "record-platform-storage" ;;
        fisco) echo "record-platform-fisco" ;;
        backend) echo "record-platform-web" ;;
        *) echo "$1" ;;
    esac
}

# 获取 OpenTelemetry 服务名。
get_otel_name() {
    get_sw_name "$1"
}

# 获取服务健康检查端口。
get_service_port() {
    case "$1" in
        storage) echo "$STORAGE_PORT" ;;
        fisco) echo "$FISCO_PORT" ;;
        backend)
            if [[ ",$SPRING_PROFILE," == *,prod,* ]]; then
                echo "${SERVER_PORT:-443}"
            else
                echo "${SERVER_PORT:-8080}"
            fi
            ;;
        *) echo "" ;;
    esac
}

# ================================
# PID 安全校验函数
# ================================
# 判断 PID 是否为正整数。
is_valid_pid() {
    local pid=$1
    [[ "$pid" =~ ^[0-9]+$ ]] && [ "$pid" -gt 0 ]
}

# 读取指定 PID 的完整命令行。
get_process_command() {
    local pid=$1
    ps -p "$pid" -o command= 2>/dev/null || true
}

# 校验 PID 指向的进程是否为当前服务对应的 Java JAR。
is_expected_service_process() {
    local svc=$1
    local pid=$2
    local jar_path
    jar_path=$(get_service_jar "$svc")
    local jar_name
    jar_name=$(basename "$jar_path")

    if ! is_valid_pid "$pid"; then
        return 1
    fi

    local command_line
    command_line=$(get_process_command "$pid")
    if [ -z "$command_line" ]; then
        return 1
    fi

    [[ "$command_line" == *"java"* ]] \
        && [[ "$command_line" == *" -jar "* ]] \
        && { [[ "$command_line" == *"$jar_path"* ]] || [[ "$command_line" == *"$jar_name"* ]]; }
}

if [ "${RECORD_PLATFORM_SCRIPT_SELF_TEST:-}" = "pid-validation" ]; then
    if is_expected_service_process "backend" "$$"; then
        echo "PID validation self-test failed: current shell matched backend service" >&2
        exit 1
    fi
    echo "PID validation self-test passed"
    exit 0
fi

# ================================
# 帮助信息函数
# ================================
show_help() {
    echo "用法: $0 <命令> [服务...] [选项]"
    echo ""
    echo "命令:"
    echo "  start     启动服务"
    echo "  stop      停止服务"
    echo "  restart   重启服务"
    echo "  status    查看服务状态"
    echo ""
    echo "服务:"
    echo "  all       全部服务 (默认)"
    echo "  storage   存储服务"
    echo "  fisco     区块链服务"
    echo "  backend   后端服务"
    echo ""
    echo "选项:"
    echo "  --skywalking    启用 SkyWalking 链路追踪"
    echo "  --otel          启用 OpenTelemetry 链路追踪"
    echo "  --foreground    前台运行 (仅对单服务 start 有效)"
    echo "  --profile=xxx   Spring Profile (默认 prod)"
    echo "  --profile xxx   Spring Profile (默认 prod)"
    echo ""
    echo "示例:"
    echo "  $0 start                    # 启动全部服务"
    echo "  $0 start backend            # 仅启动后端服务"
    echo "  $0 restart backend          # 重启后端服务"
    echo "  $0 stop all                 # 停止全部服务"
    echo "  $0 status                   # 查看所有服务状态"
    echo "  $0 start --skywalking       # 启用 SkyWalking 启动"
    echo "  $0 start --otel             # 启用 OpenTelemetry 启动"
}

# 无参数时显示帮助
if [ $# -eq 0 ]; then
    show_help
    exit 0
fi

# ================================
# 参数解析
# ================================
COMMAND=""
SERVICES=()
ENABLE_SKYWALKING=false
ENABLE_OTEL=false
FOREGROUND=false
HAS_ALL_SERVICE=false

# 添加服务到列表（处理别名、去重与 all 覆盖）
add_service() {
    local svc=$1

    if [ "$svc" = "web" ]; then
        svc="backend"
    fi

    if [ "$svc" = "all" ]; then
        HAS_ALL_SERVICE=true
        SERVICES=()
        return 0
    fi

    if [ "$HAS_ALL_SERVICE" = true ]; then
        return 0
    fi

    for existing in "${SERVICES[@]}"; do
        if [ "$existing" = "$svc" ]; then
            return 0
        fi
    done
    SERVICES+=("$svc")
}

while [ $# -gt 0 ]; do
    case "$1" in
        start|stop|restart|status)
            COMMAND="$1"
            shift
            ;;
        storage|fisco|backend|web|all)
            add_service "$1"
            shift
            ;;
        --skywalking)
            ENABLE_SKYWALKING=true
            shift
            ;;
        --otel)
            ENABLE_OTEL=true
            shift
            ;;
        --foreground)
            FOREGROUND=true
            shift
            ;;
        --profile=*)
            SPRING_PROFILE="${1#*=}"
            shift
            ;;
        --profile)
            shift
            if [ -z "${1:-}" ]; then
                echo "错误: --profile 需要参数"
                echo ""
                show_help
                exit 1
            fi
            SPRING_PROFILE="$1"
            shift
            ;;
        --help|-h)
            show_help
            exit 0
            ;;
        *)
            echo "错误: 未知参数 '$1'"
            echo ""
            show_help
            exit 1
            ;;
    esac
done

# 互斥检查: SkyWalking 和 OTel 不能同时启用
if [ "$ENABLE_SKYWALKING" = true ] && [ "$ENABLE_OTEL" = true ]; then
    echo "错误: --skywalking 和 --otel 不能同时启用（双 agent 会导致冲突）"
    exit 1
fi

# 验证命令是否已指定
if [ -z "$COMMAND" ]; then
    echo "错误: 请指定命令 (start/stop/restart/status)"
    echo ""
    show_help
    exit 1
fi

if [ "${#SERVICES[@]}" -eq 0 ] || [ "$HAS_ALL_SERVICE" = true ]; then
    SERVICES=("${SERVICE_ORDER[@]}")
    ALL_SERVICES_SELECTED=true
else
    ALL_SERVICES_SELECTED=false
fi

if [ "$FOREGROUND" = true ]; then
    if [ "$COMMAND" != "start" ]; then
        echo "提示: --foreground 仅对 start 有效，将忽略"
        FOREGROUND=false
    elif [ "${#SERVICES[@]}" -ne 1 ]; then
        echo "错误: --foreground 仅支持单服务 start"
        exit 1
    fi
fi

# ================================
# 进程查找函数
# ================================
get_pid_file() {
    local svc=$1
    echo "$PID_DIR/${svc}.pid"
}

get_pid() {
    local svc=$1
    local pid_file
    pid_file=$(get_pid_file "$svc")
    
    if [ -f "$pid_file" ]; then
        local pid
        pid=$(tr -d '[:space:]' < "$pid_file" 2>/dev/null)
        if is_valid_pid "$pid" && kill -0 "$pid" 2>/dev/null && is_expected_service_process "$svc" "$pid"; then
            echo "$pid"
            return 0
        fi
        echo "⚠ 忽略无效或不匹配的 PID 文件: $pid_file (PID: ${pid:-空})" >&2
        rm -f "$pid_file"
    fi
    echo ""
}

save_pid() {
    local svc=$1
    local pid=$2
    local pid_file
    pid_file=$(get_pid_file "$svc")
    echo "$pid" > "$pid_file"
}

remove_pid() {
    local svc=$1
    local pid_file
    pid_file=$(get_pid_file "$svc")
    rm -f "$pid_file"
}

# Resolve the probe from runtime configuration, never from a provider RPC port.
get_readiness_url() {
    local scheme=http context="" host="${BACKEND_HEALTH_HOST:-${SERVER_ADDRESS:-127.0.0.1}}"
    case "$1" in
        storage) echo "http://127.0.0.1:$QOS_STORAGE_PORT/ready" ;;
        fisco) echo "http://127.0.0.1:$QOS_FISCO_PORT/ready" ;;
        backend)
            if [[ ",$SPRING_PROFILE," == *,prod,* ]]; then
                context=/record-platform
                if [ "${SERVER_SSL_ENABLED:-${SSL_ENABLED:-true}}" = true ]; then
                    scheme=https
                fi
            elif [ "${SERVER_SSL_ENABLED:-false}" = true ]; then
                scheme=https
            fi
            context="${SERVER_SERVLET_CONTEXT_PATH-$context}"
            case "$host" in
                0.0.0.0|::|\[::\]) host=127.0.0.1 ;;
                *:*) [[ "$host" == \[*\] ]] || host="[$host]" ;;
            esac
            echo "$scheme://$host:$(get_service_port backend)${context%/}/actuator/health"
            ;;
        *) return 1 ;;
    esac
}

# Require HTTP success and the expected body; a socket or nested UP is insufficient.
probe_readiness() {
    local svc=$1 timeout=${2:-$HEALTH_CHECK_REQUEST_TIMEOUT} response status
    # Disable curlrc first so user redirect/retry defaults cannot weaken this probe.
    response=$(curl -q --silent --show-error --fail --noproxy '*' \
        --connect-timeout "$timeout" --max-time "$timeout" \
        --write-out $'\n%{http_code}' \
        "$(get_readiness_url "$svc")" 2>/dev/null) || return 1
    status=${response##*$'\n'}
    [[ "$status" =~ ^2[0-9][0-9]$ ]] || return 1
    response=${response%$'\n'*}
    printf '%s' "$response" | python3 -c '
import json, sys
try:
    value = json.load(sys.stdin)
    ready = (isinstance(value, dict) and value.get("status") == "UP") if sys.argv[1] == "backend" else value is True
except (ValueError, TypeError):
    ready = False
sys.exit(0 if ready else 1)
' "$svc"
}

# Bound the complete wait, including curl time, and reject dead/replaced processes.
wait_for_health() {
    local svc=$1
    local deadline=$((SECONDS + HEALTH_CHECK_TIMEOUT)) remaining request_timeout pause pid
    pid=$(get_pid "$svc")
    [ -n "$pid" ] || return 1
    echo "  等待健康检查 (最多 ${HEALTH_CHECK_TIMEOUT}s)..."
    while [ "$SECONDS" -lt "$deadline" ]; do
        [ "$(get_pid "$svc")" = "$pid" ] || return 1
        remaining=$((deadline - SECONDS))
        request_timeout=$HEALTH_CHECK_REQUEST_TIMEOUT
        [ "$request_timeout" -le "$remaining" ] || request_timeout=$remaining
        [ "$request_timeout" -gt 0 ] || return 1
        if probe_readiness "$svc" "$request_timeout" && [ "$(get_pid "$svc")" = "$pid" ]; then
            return 0
        fi
        remaining=$((deadline - SECONDS))
        [ "$remaining" -gt 0 ] || break
        pause=$HEALTH_CHECK_INTERVAL
        [ "$pause" -le "$remaining" ] || pause=$remaining
        sleep "$pause"
    done
    
    return 1
}

# ================================
# 停止服务函数
# ================================
stop_service() {
    local svc=$1
    local name
    name=$(get_service_name "$svc")
    
    local pid
    pid=$(get_pid "$svc")
    
    if [ -n "$pid" ]; then
        echo "正在停止 $name (PID: $pid)..."
        kill $pid 2>/dev/null || true
        
        local count=0
        while [ $count -lt 30 ]; do
            if ! kill -0 $pid 2>/dev/null; then
                remove_pid "$svc"
                echo "✓ $name 已停止"
                return 0
            fi
            sleep 1
            count=$((count + 1))
        done
        
        echo "进程未响应，强制终止..."
        kill -9 $pid 2>/dev/null || true
        remove_pid "$svc"
        echo "✓ $name 已强制停止"
    else
        echo "○ $name 未运行"
    fi
}

# ================================
# 启动服务函数
# ================================
start_service() {
    local svc=$1
    local run_foreground=${2:-false}
    local jar_path
    jar_path=$(get_service_jar "$svc")
    local name
    name=$(get_service_name "$svc")
    local sw_name
    sw_name=$(get_sw_name "$svc")
    local port
    port=$(get_service_port "$svc")

    local existing_pid
    existing_pid=$(get_pid "$svc")
    if [ -n "$existing_pid" ]; then
        echo "⚠ $name 已在运行 (PID: $existing_pid)"
        wait_for_health "$svc"
        return $?
    fi

    echo "----------------------------------------"
    echo "正在启动: $name"
    
    if [ ! -f "$jar_path" ]; then
        echo "✗ 错误: 未找到 JAR 文件: $jar_path"
        return 1
    fi

    local java_opts="$COMMON_JVM_OPTS"

    if [ "$ENABLE_SKYWALKING" = true ]; then
        local sw_opts
        sw_opts=$(get_skywalking_opts "$sw_name" "$(hostname -s)")
        if [ -n "$sw_opts" ]; then
            java_opts="$java_opts $sw_opts"
            echo "  SkyWalking: 已启用"
        else
            echo "  SkyWalking: Agent 未找到"
        fi
    fi

    if [ "$ENABLE_OTEL" = true ]; then
        local otel_name
        otel_name=$(get_otel_name "$svc")
        local otel_opts
        otel_opts=$(get_otel_opts "$otel_name")
        if [ -n "$otel_opts" ]; then
            java_opts="$java_opts $otel_opts"
            echo "  OpenTelemetry: 已启用 (service=$otel_name)"
        else
            echo "  OpenTelemetry: Agent 未找到"
        fi
    fi

    echo "  Profile: $SPRING_PROFILE"
    echo "  端口: $port"
    echo "  工作目录: $PROJECT_ROOT"

    if [ "$run_foreground" = true ]; then
        echo "  模式: 前台运行"
        echo "----------------------------------------"
        cd "$PROJECT_ROOT" && exec java $java_opts -jar "$jar_path" --spring.profiles.active="$SPRING_PROFILE"
    else
        echo "  模式: 后台运行"
        pushd "$PROJECT_ROOT" > /dev/null || return 1
        nohup java $java_opts -jar "$jar_path" \
            --spring.profiles.active="$SPRING_PROFILE" > /dev/null 2>&1 &
        local new_pid=$!
        popd > /dev/null || return 1
        
        sleep 1
        if ! kill -0 $new_pid 2>/dev/null; then
            echo "✗ 启动失败: 进程立即退出"
            return 1
        fi
        
        save_pid "$svc" "$new_pid"
        echo "  PID: $new_pid"
        
        if wait_for_health "$svc"; then
            echo "✓ $name 启动成功，健康检查通过"
        else
            echo "✗ $name 未就绪: 进程退出或健康检查超时 (PID: $new_pid)"
            echo "  请检查日志: $LOG_DIR"
            return 1
        fi
    fi
}

# ================================
# 状态查询函数
# ================================
status_service() {
    local svc=$1
    local name
    name=$(get_service_name "$svc")
    local port
    port=$(get_service_port "$svc")
    
    local pid
    pid=$(get_pid "$svc")
    
    if [ -n "$pid" ]; then
        if probe_readiness "$svc" && [ "$(get_pid "$svc")" = "$pid" ]; then
            echo "✓ $name: 运行中 (PID: $pid, 端口: $port, 状态: 就绪)"
            return 0
        fi
        echo "✗ $name: 运行中但未就绪 (PID: $pid, 端口: $port)"
    else
        echo "○ $name: 未运行"
    fi
    return 1
}

# ================================
# 主逻辑
# ================================
echo "========================================"
echo "RecordPlatform 服务管理"
echo "========================================"

# Reject invalid timing before launching any process.
for timing in "$HEALTH_CHECK_TIMEOUT" "$HEALTH_CHECK_INTERVAL" "$HEALTH_CHECK_REQUEST_TIMEOUT"; do
    if ! [[ "$timing" =~ ^[1-9][0-9]*$ ]]; then
        echo "错误: 健康检查时间必须为正整数秒"
        exit 1
    fi
done
RESULT=0
case "$COMMAND" in
    start)
        echo "命令: 启动服务"
        echo ""
        for svc in "${SERVICES[@]}"; do
            start_service "$svc" "$FOREGROUND" || RESULT=1
            # 全部启动时，服务间等待
            if [ "$ALL_SERVICES_SELECTED" = true ] && [ "$svc" != "backend" ]; then
                echo "等待 10 秒..."
                sleep 10
            fi
        done
        ;;
        
    stop)
        echo "命令: 停止服务"
        echo ""
        # 反向顺序停止
        for ((i=${#SERVICES[@]}-1; i>=0; i--)); do
            stop_service "${SERVICES[$i]}" || RESULT=1
        done
        ;;
        
    restart)
        echo "命令: 重启服务"
        echo ""
        # 先停止（反向）
        for ((i=${#SERVICES[@]}-1; i>=0; i--)); do
            stop_service "${SERVICES[$i]}" || RESULT=1
        done
        echo ""
        echo "等待 3 秒..."
        sleep 3
        echo ""
        # 再启动
        for svc in "${SERVICES[@]}"; do
            start_service "$svc" false || RESULT=1
            if [ "$ALL_SERVICES_SELECTED" = true ] && [ "$svc" != "backend" ]; then
                echo "等待 10 秒..."
                sleep 10
            fi
        done
        ;;
        
    status)
        echo "命令: 查看状态"
        echo ""
        for svc in "${SERVICES[@]}"; do
            status_service "$svc" || RESULT=1
        done
        ;;
        
    *)
        echo "错误: 未知命令 '$COMMAND'"
        echo "可用命令: start, stop, restart, status"
        exit 1
        ;;
esac

echo ""
echo "========================================"
if [ "$RESULT" -eq 0 ]; then
    echo "操作完成"
else
    echo "操作失败: 至少一个服务未就绪或操作失败"
fi
echo "PID 目录: $PID_DIR"
echo "日志目录: $LOG_DIR"
echo "========================================"
exit "$RESULT"
