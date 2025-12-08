#!/bin/bash
# RecordPlatform 启动脚本
# 自动加载 .env 环境变量

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_ROOT/.env"

# 加载 .env 文件
if [ -f "$ENV_FILE" ]; then
    echo "加载环境变量: $ENV_FILE"
    set -a
    source "$ENV_FILE"
    set +a
else
    echo "警告: .env 文件不存在，请从 .env.example 复制并配置"
    echo "  cp .env.example .env"
    exit 1
fi

# 默认 profile
PROFILE=${SPRING_PROFILES_ACTIVE:-local}

# 服务选择
SERVICE=${1:-all}

start_backend() {
    echo "启动 Backend Web (端口 $SERVER_PORT)..."
    java -jar "$PROJECT_ROOT/platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar" \
        --spring.profiles.active="$PROFILE" &
}

start_fisco() {
    echo "启动 FISCO Service (Dubbo 端口 $DUBBO_FISCO_PORT)..."
    java -jar "$PROJECT_ROOT/platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar" \
        --spring.profiles.active="$PROFILE" &
}

start_minio() {
    echo "启动 MinIO Service (Dubbo 端口 $DUBBO_MINIO_PORT)..."
    java -jar "$PROJECT_ROOT/platform-minio/target/platform-minio-0.0.1-SNAPSHOT.jar" \
        --spring.profiles.active="$PROFILE" &
}

case "$SERVICE" in
    backend)
        start_backend
        ;;
    fisco)
        start_fisco
        ;;
    minio)
        start_minio
        ;;
    all)
        echo "按顺序启动所有服务 (Profile: $PROFILE)..."
        start_minio
        sleep 5
        start_fisco
        sleep 5
        start_backend
        ;;
    *)
        echo "用法: $0 {backend|fisco|minio|all}"
        exit 1
        ;;
esac

echo "服务启动中，使用 'jps' 查看进程状态"
