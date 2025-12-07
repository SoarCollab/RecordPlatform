#!/bin/bash

# SkyWalking Agent Configuration for RecordPlatform Services
# Usage: source this file before starting services, or copy JAVA_OPTS to your deployment config

# SkyWalking OAP Server address (modify according to your environment)
SW_OAP_ADDRESS=${SW_OAP_ADDRESS:-"127.0.0.1:11800"}

# SkyWalking Agent path (download from https://skywalking.apache.org/downloads/)
SW_AGENT_PATH=${SW_AGENT_PATH:-"/opt/skywalking-agent/skywalking-agent.jar"}

# Common SkyWalking options
SW_COMMON_OPTS="-Dskywalking.collector.backend_service=${SW_OAP_ADDRESS}"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.agent.sample_n_per_3_secs=50"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.logging.level=WARN"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.plugin.dubbo.collect_consumer_arguments=true"
SW_COMMON_OPTS="${SW_COMMON_OPTS} -Dskywalking.plugin.dubbo.collect_provider_arguments=true"

# Backend Web Service
export BACKEND_JAVA_OPTS="-javaagent:${SW_AGENT_PATH} \
    -Dskywalking.agent.service_name=record-platform-backend \
    -Dskywalking.agent.instance_name=backend-\${HOSTNAME:-local} \
    ${SW_COMMON_OPTS}"

# FISCO Service (Blockchain)
export FISCO_JAVA_OPTS="-javaagent:${SW_AGENT_PATH} \
    -Dskywalking.agent.service_name=record-platform-fisco \
    -Dskywalking.agent.instance_name=fisco-\${HOSTNAME:-local} \
    ${SW_COMMON_OPTS}"

# MinIO Service (Storage)
export MINIO_JAVA_OPTS="-javaagent:${SW_AGENT_PATH} \
    -Dskywalking.agent.service_name=record-platform-minio \
    -Dskywalking.agent.instance_name=minio-\${HOSTNAME:-local} \
    ${SW_COMMON_OPTS}"

echo "SkyWalking configuration loaded."
echo "OAP Server: ${SW_OAP_ADDRESS}"
echo ""
echo "Start services with:"
echo "  java \${BACKEND_JAVA_OPTS} -jar backend-web.jar --spring.profiles.active=prod"
echo "  java \${FISCO_JAVA_OPTS} -jar platform-fisco.jar --spring.profiles.active=prod"
echo "  java \${MINIO_JAVA_OPTS} -jar platform-minio.jar --spring.profiles.active=prod"
