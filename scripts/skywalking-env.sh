#!/bin/bash

# RecordPlatform 服务的 SkyWalking Agent 配置
# 用法: 在启动服务前 source 此文件，或将 JAVA_OPTS 复制到你的部署配置中

# 动态获取项目根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 如果脚本在 bin/ 或 scripts/ 下，根目录在上级
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# SkyWalking OAP Server 地址
SW_OAP_ADDRESS=${SW_OAP_ADDRESS:-"127.0.0.1:11800"}

# SkyWalking Agent 路径
# 默认尝试在项目根目录下的 agent/skywalking-agent.jar
DEFAULT_AGENT_PATH="$PROJECT_ROOT/agent/skywalking-agent.jar"
# 如果找不到，尝试常见的系统路径，或者允许用户通过 SW_AGENT_PATH 环境变量覆盖
if [ -f "$DEFAULT_AGENT_PATH" ]; then
    SW_AGENT_PATH=${SW_AGENT_PATH:-"$DEFAULT_AGENT_PATH"}
else
    # 备选路径
    SW_AGENT_PATH=${SW_AGENT_PATH:-"/opt/skywalking-agent/skywalking-agent.jar"}
fi

# 检查 Agent 是否存在
if [ ! -f "$SW_AGENT_PATH" ]; then
    echo "警告: 未在 $SW_AGENT_PATH 找到 SkyWalking Agent。"
    echo "请确保已安装 SkyWalking 并设置正确路径，或者忽略此警告（如果不启用追踪）。"
fi

# 通用 SkyWalking 选项
SW_COMMON_OPTS="-Dskywalking.collector.backend_service=${SW_OAP_ADDRESS}"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.agent.sample_n_per_3_secs=50"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.logging.level=WARN"
# Dubbo 插件配置
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.plugin.dubbo.collect_consumer_arguments=true"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.plugin.dubbo.collect_provider_arguments=true"

# 后端 Web 服务 (Backend Web Service)
export BACKEND_JAVA_OPTS="-javaagent:${SW_AGENT_PATH} \
    -Dskywalking.agent.service_name=record-platform-backend \
    -Dskywalking.agent.instance_name=backend-\${HOSTNAME:-local} \
    ${SW_COMMON_OPTS}"

# 区块链服务 (FISCO Service)
export FISCO_JAVA_OPTS="-javaagent:${SW_AGENT_PATH} \
    -Dskywalking.agent.service_name=record-platform-fisco \
    -Dskywalking.agent.instance_name=fisco-\${HOSTNAME:-local} \
    ${SW_COMMON_OPTS}"

# 存储服务 (Storage Service)
export STORAGE_JAVA_OPTS="-javaagent:${SW_AGENT_PATH} \
    -Dskywalking.agent.service_name=record-platform-storage \
    -Dskywalking.agent.instance_name=storage-\${HOSTNAME:-local} \
    ${SW_COMMON_OPTS}"

echo "SkyWalking 配置已加载。"
echo "OAP Server: ${SW_OAP_ADDRESS}"
echo "Agent Path: ${SW_AGENT_PATH}"
echo ""
echo "使用以下命令启动服务:"
echo "  java \${BACKEND_JAVA_OPTS} -jar backend-web.jar --spring.profiles.active=prod"
echo "  java \${FISCO_JAVA_OPTS} -jar platform-fisco.jar --spring.profiles.active=prod"
echo "  java \${STORAGE_JAVA_OPTS} -jar platform-storage.jar --spring.profiles.active=prod"
