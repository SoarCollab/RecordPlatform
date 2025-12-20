#!/bin/bash
# RecordPlatform 环境变量配置
# 用法: source ./env.sh

# ================================
# 路径配置
# ================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 加载 .env 文件
if [ -f "$PROJECT_ROOT/.env" ]; then
    set -a
    source "$PROJECT_ROOT/.env"
    set +a
fi

# ================================
# JAR 包位置
# ================================
if [ -d "$PROJECT_ROOT/jars" ]; then
    JAR_BASE="$PROJECT_ROOT/jars"
else
    JAR_BASE=""
fi

export STORAGE_JAR="${JAR_BASE:-$PROJECT_ROOT/platform-storage/target}/platform-storage-0.0.1-SNAPSHOT.jar"
export FISCO_JAR="${JAR_BASE:-$PROJECT_ROOT/platform-fisco/target}/platform-fisco-0.0.1-SNAPSHOT.jar"
export BACKEND_JAR="${JAR_BASE:-$PROJECT_ROOT/platform-backend/backend-web/target}/backend-web-0.0.1-SNAPSHOT.jar"

# ================================
# 日志配置
# ================================
export LOG_DIR="${LOG_DIR:-$PROJECT_ROOT/log}"
mkdir -p "$LOG_DIR"

# ================================
# SkyWalking 配置
# ================================
export SW_AGENT_HOME="${SW_AGENT_HOME:-$PROJECT_ROOT/agent}"
export SW_COLLECTOR="${SW_COLLECTOR:-127.0.0.1:11800}"

# 检查 SkyWalking Agent
check_skywalking_agent() {
    if [ -f "${SW_AGENT_HOME}/skywalking-agent.jar" ]; then
        return 0
    elif [ -f "/opt/skywalking-agent/skywalking-agent.jar" ]; then
        export SW_AGENT_HOME="/opt/skywalking-agent"
        return 0
    else
        return 1
    fi
}

# 生成 SkyWalking JVM 选项
get_skywalking_opts() {
    local service_name=$1
    local instance_name=$2
    
    if check_skywalking_agent; then
        echo "-javaagent:${SW_AGENT_HOME}/skywalking-agent.jar \
            -Dskywalking.agent.service_name=${service_name} \
            -Dskywalking.agent.instance_name=${instance_name} \
            -Dskywalking.collector.backend_service=${SW_COLLECTOR} \
            -Dskywalking.agent.sample_n_per_3_secs=50 \
            -Dskywalking.logging.level=WARN \
            -Dskywalking.plugin.dubbo.collect_consumer_arguments=true \
            -Dskywalking.plugin.dubbo.collect_provider_arguments=true"
    else
        echo ""
    fi
}

# ================================
# 通用 JVM 配置
# ================================
export COMMON_JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -DLOG_PATH=$LOG_DIR"

# ================================
# Spring Profile
# ================================
export SPRING_PROFILE="${SPRING_PROFILES_ACTIVE:-prod}"

echo "环境配置已加载:"
echo "  项目根目录: $PROJECT_ROOT"
echo "  日志目录: $LOG_DIR"
echo "  Spring Profile: $SPRING_PROFILE"
