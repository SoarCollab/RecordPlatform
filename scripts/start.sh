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
#   --foreground    前台运行 (仅对单服务 start 有效)
#   --profile=xxx   指定 Spring Profile (默认 prod)
#   --help          显示帮助信息

# 加载环境配置
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/env.sh"

# ================================
# 服务定义
# ================================
declare -A SERVICE_JARS=(
    ["storage"]="$STORAGE_JAR"
    ["fisco"]="$FISCO_JAR"
    ["backend"]="$BACKEND_JAR"
)

declare -A SERVICE_NAMES=(
    ["storage"]="存储服务 (Storage)"
    ["fisco"]="区块链服务 (FISCO)"
    ["backend"]="后端服务 (Backend)"
)

declare -A SW_NAMES=(
    ["storage"]="record-platform-storage"
    ["fisco"]="record-platform-fisco"
    ["backend"]="record-platform-web"
)

declare -A SERVICE_PORTS=(
    ["storage"]="$STORAGE_PORT"
    ["fisco"]="$FISCO_PORT"
    ["backend"]="$BACKEND_PORT"
)

SERVICE_ORDER=("storage" "fisco" "backend")

HEALTH_CHECK_TIMEOUT=60
HEALTH_CHECK_INTERVAL=2

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
    local pid_file=$(get_pid_file "$svc")
    
    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "$pid"
            return 0
        fi
        rm -f "$pid_file"
    fi
    echo ""
}

save_pid() {
    local svc=$1
    local pid=$2
    local pid_file=$(get_pid_file "$svc")
    echo "$pid" > "$pid_file"
}

remove_pid() {
    local svc=$1
    local pid_file=$(get_pid_file "$svc")
    rm -f "$pid_file"
}

wait_for_health() {
    local svc=$1
    local port="${SERVICE_PORTS[$svc]}"
    local elapsed=0
    
    if [ -z "$port" ]; then
        return 0
    fi
    
    echo "  等待健康检查 (最多 ${HEALTH_CHECK_TIMEOUT}s)..."
    
    while [ $elapsed -lt $HEALTH_CHECK_TIMEOUT ]; do
        if curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep $HEALTH_CHECK_INTERVAL
        elapsed=$((elapsed + HEALTH_CHECK_INTERVAL))
    done
    
    return 1
}

# ================================
# 停止服务函数
# ================================
stop_service() {
    local svc=$1
    local jar_path="${SERVICE_JARS[$svc]}"
    local jar_name=$(basename "$jar_path")
    local name="${SERVICE_NAMES[$svc]}"
    
    local pid=$(get_pid "$svc")
    
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
    local jar_path="${SERVICE_JARS[$svc]}"
    local jar_name=$(basename "$jar_path")
    local name="${SERVICE_NAMES[$svc]}"
    local sw_name="${SW_NAMES[$svc]}"
    local port="${SERVICE_PORTS[$svc]}"

    local existing_pid=$(get_pid "$svc")
    if [ -n "$existing_pid" ]; then
        echo "⚠ $name 已在运行 (PID: $existing_pid)"
        return 0
    fi

    echo "----------------------------------------"
    echo "正在启动: $name"
    
    if [ ! -f "$jar_path" ]; then
        echo "✗ 错误: 未找到 JAR 文件: $jar_path"
        return 1
    fi

    local java_opts="$COMMON_JVM_OPTS"
    
    if [ "$ENABLE_SKYWALKING" = true ]; then
        local sw_opts=$(get_skywalking_opts "$sw_name" "$(hostname -s)")
        if [ -n "$sw_opts" ]; then
            java_opts="$java_opts $sw_opts"
            echo "  SkyWalking: 已启用"
        else
            echo "  SkyWalking: Agent 未找到"
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
        pushd "$PROJECT_ROOT" > /dev/null
        nohup java $java_opts -jar "$jar_path" \
            --spring.profiles.active="$SPRING_PROFILE" > /dev/null 2>&1 &
        local new_pid=$!
        popd > /dev/null
        
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
            echo "⚠ $name 已启动 (PID: $new_pid)，但健康检查超时"
            echo "  请检查日志: $LOG_DIR"
        fi
    fi
}

# ================================
# 状态查询函数
# ================================
status_service() {
    local svc=$1
    local jar_path="${SERVICE_JARS[$svc]}"
    local jar_name=$(basename "$jar_path")
    local name="${SERVICE_NAMES[$svc]}"
    local port="${SERVICE_PORTS[$svc]}"
    
    local pid=$(get_pid "$svc")
    
    if [ -n "$pid" ]; then
        local health_status="未知"
        if [ -n "$port" ]; then
            if curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
                health_status="健康"
            else
                health_status="不健康"
            fi
        fi
        echo "✓ $name: 运行中 (PID: $pid, 端口: $port, 状态: $health_status)"
    else
        echo "○ $name: 未运行"
    fi
}

# ================================
# 主逻辑
# ================================
echo "========================================"
echo "RecordPlatform 服务管理"
echo "========================================"

case "$COMMAND" in
    start)
        echo "命令: 启动服务"
        echo ""
        for svc in "${SERVICES[@]}"; do
            start_service "$svc" "$FOREGROUND"
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
            stop_service "${SERVICES[$i]}"
        done
        ;;
        
    restart)
        echo "命令: 重启服务"
        echo ""
        # 先停止（反向）
        for ((i=${#SERVICES[@]}-1; i>=0; i--)); do
            stop_service "${SERVICES[$i]}"
        done
        echo ""
        echo "等待 3 秒..."
        sleep 3
        echo ""
        # 再启动
        for svc in "${SERVICES[@]}"; do
            start_service "$svc" false
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
            status_service "$svc"
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
echo "操作完成"
echo "PID 目录: $PID_DIR"
echo "日志目录: $LOG_DIR"
echo "========================================"
