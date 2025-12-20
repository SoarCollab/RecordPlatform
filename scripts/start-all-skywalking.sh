#!/bin/bash
# 包含 SkyWalking 的全量启动脚本
# 用法: ./start-all-skywalking.sh [profile]

set -e

# 配置
PROFILE=${1:-prod}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")" # 定位到 bin/ 或 scripts/ 的上级目录
ENV_FILE="$PROJECT_ROOT/.env"

# 加载环境变量
if [ -f "$ENV_FILE" ]; then
    echo "正在加载环境变量: $ENV_FILE"
    set -a
    source "$ENV_FILE"
    set +a
fi

SW_AGENT_HOME=${SW_AGENT_HOME:-$PROJECT_ROOT/agent}
SW_COLLECTOR=${SW_COLLECTOR:-127.0.0.1:11800}

# 确定 JAR 包位置 (部署模式 vs 源码模式)
if [ -d "$PROJECT_ROOT/jars" ]; then
    echo "检测到部署目录结构 (发现 jars/ 目录)"
    JAR_BASE="$PROJECT_ROOT/jars"
    STORAGE_JAR="$JAR_BASE/platform-storage-0.0.1-SNAPSHOT.jar"
    FISCO_JAR="$JAR_BASE/platform-fisco-0.0.1-SNAPSHOT.jar"
    BACKEND_JAR="$JAR_BASE/backend-web-0.0.1-SNAPSHOT.jar"
else
    echo "检测到源码目录结构 (使用 target/ 目录)"
    STORAGE_JAR="$PROJECT_ROOT/platform-storage/target/platform-storage-0.0.1-SNAPSHOT.jar"
    FISCO_JAR="$PROJECT_ROOT/platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar"
    BACKEND_JAR="$PROJECT_ROOT/platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar"
fi

# 启动服务函数
start_service() {
    local service_name=$1
    local jar_path=$2
    local sw_service_name=$3
    local port_desc=$4

    echo "正在启动 $service_name ($port_desc)..."
    
    if [ ! -f "$jar_path" ]; then
        echo "错误: 未找到 JAR 文件: $jar_path"
        return 1
    fi

    local java_opts="-Xms512m -Xmx1024m -XX:+UseG1GC"
    local agent_opts=""

    if [ -f "${SW_AGENT_HOME}/skywalking-agent.jar" ]; then
        agent_opts="-javaagent:${SW_AGENT_HOME}/skywalking-agent.jar \
        -Dskywalking.agent.service_name=${sw_service_name} \
        -Dskywalking.agent.instance_name=$(hostname -s) \
        -Dskywalking.collector.backend_service=${SW_COLLECTOR} \
        -Dskywalking.plugin.dubbo.trace.consumer=true \
        -Dskywalking.plugin.dubbo.trace.provider=true"
    else
        echo "警告: 未在 ${SW_AGENT_HOME} 找到 SkyWalking agent。"
        echo "将不加载链路追踪探针启动。"
    fi

    # 后台启动
    nohup java $java_opts $agent_opts -jar "$jar_path" \
        --spring.profiles.active="$PROFILE" > /dev/null 2>&1 &
    
    echo "已启动，PID: $!"
}

echo "=== 正在启动 RecordPlatform (集成 SkyWalking, Profile: $PROFILE) ==="
echo "项目根目录: $PROJECT_ROOT"

# 1. 启动存储服务
start_service "存储服务 (Storage Service)" \
    "$STORAGE_JAR" \
    "record-platform-storage" \
    "Dubbo 端口: ${DUBBO_MINIO_PORT:-20881}"

sleep 10

# 2. 启动区块链服务
start_service "区块链服务 (FISCO Service)" \
    "$FISCO_JAR" \
    "record-platform-fisco" \
    "Dubbo 端口: ${DUBBO_FISCO_PORT:-20880}"

sleep 10

# 3. 启动后端 Web 服务
start_service "后端 Web 服务 (Backend Web)" \
    "$BACKEND_JAR" \
    "record-platform-web" \
    "Web 端口: ${SERVER_PORT:-8080}"

echo "=== 所有服务启动流程已执行 ==="
echo "请检查 $PROJECT_ROOT/logs/ 下的日志，或使用 'jps' 命令确认进程状态。"
