#!/bin/bash
# RecordPlatform 启动脚本 (无 SkyWalking)
# 用法: ./start.sh {backend|fisco|storage|all}

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")" # 定位到 bin/ 或 scripts/ 的上级目录
ENV_FILE="$PROJECT_ROOT/.env"

# 加载 .env
if [ -f "$ENV_FILE" ]; then
    echo "正在加载环境变量: $ENV_FILE"
    set -a
    source "$ENV_FILE"
    set +a
else
    echo "警告: 未找到 .env 文件: $ENV_FILE"
fi

# 默认 Profile
PROFILE=${SPRING_PROFILES_ACTIVE:-local}

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

SERVICE=${1:-all}

start_jar() {
    local name=$1
    local jar=$2
    echo "正在启动 $name..."
    if [ ! -f "$jar" ]; then
        echo "错误: 未找到 JAR 文件: $jar"
        return 1
    fi
    java -jar "$jar" --spring.profiles.active="$PROFILE" &
}

case "$SERVICE" in
    storage)
        start_jar "存储服务 (Storage Service)" "$STORAGE_JAR"
        ;;
    fisco)
        start_jar "区块链服务 (FISCO Service)" "$FISCO_JAR"
        ;;
    backend)
        start_jar "后端 Web 服务 (Backend Web)" "$BACKEND_JAR"
        ;;
    all)
        echo "正在按顺序启动所有服务 (Profile: $PROFILE)..."
        start_jar "存储服务 (Storage Service)" "$STORAGE_JAR"
        sleep 5
        start_jar "区块链服务 (FISCO Service)" "$FISCO_JAR"
        sleep 5
        start_jar "后端 Web 服务 (Backend Web)" "$BACKEND_JAR"
        ;;
    *)
        echo "用法: $0 {backend|fisco|storage|all}"
        exit 1
        ;;
esac

echo "服务启动流程已执行。"
