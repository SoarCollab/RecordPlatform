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
# PID 文件目录
# ================================
export PID_DIR="${PID_DIR:-$PROJECT_ROOT/run}"
mkdir -p "$PID_DIR"

# ================================
# 服务端口配置 (用于健康检查)
# ================================
export STORAGE_PORT="${STORAGE_PORT:-8092}"
export FISCO_PORT="${FISCO_PORT:-8091}"
export BACKEND_PORT="${BACKEND_PORT:-8000}"

# ================================
# SkyWalking 配置
# ================================
export SW_AGENT_HOME="${SW_AGENT_HOME:-$PROJECT_ROOT/agent}"
export SW_AGENT_COLLECTOR_BACKEND_SERVICES="${SW_AGENT_COLLECTOR_BACKEND_SERVICES:-127.0.0.1:11800}"

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
            -Dskywalking.collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES}"
    else
        echo ""
    fi
}

# ================================
# OpenTelemetry 配置
# ================================
export OTEL_AGENT_HOME="${OTEL_AGENT_HOME:-$PROJECT_ROOT/agent}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317}"
export OTEL_TRACES_SAMPLER="${OTEL_TRACES_SAMPLER:-parentbased_traceidratio}"
export OTEL_TRACES_SAMPLER_ARG="${OTEL_TRACES_SAMPLER_ARG:-0.1}"

declare -A OTEL_NAMES=(
    ["storage"]="record-platform-storage"
    ["fisco"]="record-platform-fisco"
    ["backend"]="record-platform-web"
)

# 检查 OpenTelemetry Agent
check_otel_agent() {
    if [ -f "${OTEL_AGENT_HOME}/opentelemetry-javaagent.jar" ]; then
        return 0
    elif [ -f "/opt/otel/opentelemetry-javaagent.jar" ]; then
        export OTEL_AGENT_HOME="/opt/otel"
        return 0
    else
        return 1
    fi
}

# 生成 OpenTelemetry JVM 选项
get_otel_opts() {
    local service_name=$1

    if check_otel_agent; then
        echo "-javaagent:${OTEL_AGENT_HOME}/opentelemetry-javaagent.jar \
            -Dotel.service.name=${service_name} \
            -Dotel.traces.exporter=otlp \
            -Dotel.metrics.exporter=otlp \
            -Dotel.logs.exporter=none \
            -Dotel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT} \
            -Dotel.traces.sampler=${OTEL_TRACES_SAMPLER} \
            -Dotel.traces.sampler.arg=${OTEL_TRACES_SAMPLER_ARG}"
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
