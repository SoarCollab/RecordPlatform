#!/usr/bin/env bash
# SkyWalking-enabled startup script for RecordPlatform services
# Usage: ./start-with-skywalking.sh <service> [profile]
# Services: web, minio, fisco
# Profiles: local, dev, prod (default: prod)

set -euo pipefail

SERVICE=${1:-web}
PROFILE=${2:-prod}

SW_AGENT_HOME=${SW_AGENT_HOME:-/opt/skywalking/agent}
SW_COLLECTOR=${SW_COLLECTOR:-127.0.0.1:11800}

# Service configuration
case $SERVICE in
    web)
        SERVICE_NAME="record-platform-web"
        JAR_PATH="platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar"
        ;;
    minio)
        SERVICE_NAME="record-platform-minio"
        JAR_PATH="platform-minio/target/platform-minio-0.0.1-SNAPSHOT.jar"
        ;;
    fisco)
        SERVICE_NAME="record-platform-fisco"
        JAR_PATH="platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar"
        ;;
    *)
        echo "Unknown service: $SERVICE"
        echo "Available services: web, minio, fisco"
        exit 1
        ;;
esac

# Check if JAR exists
if [[ ! -f "$JAR_PATH" ]]; then
    echo "JAR not found: $JAR_PATH"
    echo "Please build the project first: mvn -f platform-backend/pom.xml clean package -DskipTests"
    exit 1
fi

# SkyWalking agent configuration
JAVA_AGENT_OPTS=""
if [[ -f "${SW_AGENT_HOME}/skywalking-agent.jar" ]]; then
    JAVA_AGENT_OPTS="-javaagent:${SW_AGENT_HOME}/skywalking-agent.jar"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.agent.service_name=${SERVICE_NAME}"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.agent.instance_name=$(hostname -s)"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.collector.backend_service=${SW_COLLECTOR}"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.plugin.dubbo.trace.consumer=true"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.plugin.dubbo.trace.provider=true"
    JAVA_AGENT_OPTS="${JAVA_AGENT_OPTS} -Dskywalking.logging.dir=${SW_AGENT_HOME}/logs"
    echo "SkyWalking agent enabled: ${SERVICE_NAME}"
else
    echo "Warning: SkyWalking agent not found at ${SW_AGENT_HOME}/skywalking-agent.jar"
    echo "Running without distributed tracing..."
fi

# JVM options
JVM_OPTS="-Xms512m -Xmx1024m"
JVM_OPTS="${JVM_OPTS} -XX:+UseG1GC"

echo "Starting ${SERVICE_NAME} with profile: ${PROFILE}"
exec java ${JAVA_AGENT_OPTS} ${JVM_OPTS} -jar "${JAR_PATH}" --spring.profiles.active="${PROFILE}"
