#!/usr/bin/env bash
# 包含 SkyWalking 的单服务启动脚本
# 用法: ./start-with-skywalking.sh <service> [profile]
# 可选服务: web, storage, fisco

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")" # 定位到 bin/ 或 scripts/ 的上级目录
ENV_FILE="$PROJECT_ROOT/.env"

# 加载 .env
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

SERVICE=${1:-web}
PROFILE=${2:-prod}

SW_AGENT_HOME=${SW_AGENT_HOME:-$PROJECT_ROOT/agent}
SW_COLLECTOR=${SW_COLLECTOR:-127.0.0.1:11800}

# 确定 JAR 包位置 (部署模式 vs 源码模式)
if [ -d "$PROJECT_ROOT/jars" ]; then
    JAR_BASE="$PROJECT_ROOT/jars"
    STORAGE_JAR="$JAR_BASE/platform-storage-0.0.1-SNAPSHOT.jar"
    FISCO_JAR="$JAR_BASE/platform-fisco-0.0.1-SNAPSHOT.jar"
    BACKEND_JAR="$JAR_BASE/backend-web-0.0.1-SNAPSHOT.jar"
else
    STORAGE_JAR="$PROJECT_ROOT/platform-storage/target/platform-storage-0.0.1-SNAPSHOT.jar"
    FISCO_JAR="$PROJECT_ROOT/platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar"
    BACKEND_JAR="$PROJECT_ROOT/platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar"
fi

case $SERVICE in
    web|backend)
        SERVICE_NAME="record-platform-web"
        JAR_PATH="$BACKEND_JAR"
        ;;
    storage)
        SERVICE_NAME="record-platform-storage"
        JAR_PATH="$STORAGE_JAR"
        ;;
    fisco)
        SERVICE_NAME="record-platform-fisco"
        JAR_PATH="$FISCO_JAR"
        ;;
    *)
        echo "未知服务: $SERVICE"
        echo "可用服务: web (或 backend), storage, fisco"
        exit 1
        ;;
esac

# 检查 JAR 是否存在
if [[ ! -f "$JAR_PATH" ]]; then
    echo "错误: 未找到 JAR 文件: $JAR_PATH"
    echo "已检查根目录: $PROJECT_ROOT"
    exit 1
fi

# SkyWalking agent 配置
JAVA_AGENT_OPTS=""
if [[ -f "${SW_AGENT_HOME}/skywalking-agent.jar" ]]; then
    JAVA_AGENT_OPTS="-javaagent:${SW_AGENT_HOME}/skywalking-agent.jar"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.agent.service_name=${SERVICE_NAME}"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.agent.instance_name=$(hostname -s)"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.collector.backend_service=${SW_COLLECTOR}"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.plugin.dubbo.trace.consumer=true"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.plugin.dubbo.trace.provider=true"
    # 可选: 将日志输出到 agent 目录
    # JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.logging.dir=${SW_AGENT_HOME}/logs"
    echo "已启用 SkyWalking agent: ${SERVICE_NAME}"
else
    echo "警告: 未在 ${SW_AGENT_HOME} 找到 skywalking-agent.jar"
    echo "将在无分布式追踪的情况下运行..."
fi

# JVM 选项
JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

echo "正在启动 ${SERVICE_NAME} (Profile: ${PROFILE})..."
echo "JAR路径: $JAR_PATH"

exec java ${JAVA_AGENT_OPTS} ${JVM_OPTS} -jar "${JAR_PATH}" --spring.profiles.active="${PROFILE}"
