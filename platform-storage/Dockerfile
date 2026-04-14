# Build context: repository root (platform-api is a cross-module dependency)
# Usage: docker build -f platform-storage/Dockerfile -t recordplatform-storage .

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Install shared interfaces
COPY platform-api/ platform-api/
RUN mvn -f platform-api/pom.xml clean install -DskipTests -q

# Build S3-compatible storage service
COPY platform-storage/ platform-storage/
RUN mvn -f platform-storage/pom.xml clean package -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble AS runtime

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
WORKDIR /app

# Download OpenTelemetry Java Agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
RUN chmod 644 /opt/otel/opentelemetry-javaagent.jar

COPY --from=build /workspace/platform-storage/target/platform-storage-0.0.2-SNAPSHOT.jar app.jar

RUN chown -R app:app /app
USER app

EXPOSE 8092

ENV SPRING_PROFILES_ACTIVE=prod

# OTel Java Agent configuration
ENV OTEL_SERVICE_NAME=record-platform-storage
ENV OTEL_TRACES_EXPORTER=otlp
ENV OTEL_METRICS_EXPORTER=otlp
ENV OTEL_LOGS_EXPORTER=none
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
ENV OTEL_TRACES_SAMPLER=parentbased_traceidratio
ENV OTEL_TRACES_SAMPLER_ARG=0.1
ENV OTEL_JAVAAGENT_ENABLED=true

ENTRYPOINT ["sh", "-c", "if [ \"${OTEL_JAVAAGENT_ENABLED:-true}\" = \"true\" ]; then \
    OTEL_AGENT_OPT='-javaagent:/opt/otel/opentelemetry-javaagent.jar'; \
  else \
    OTEL_AGENT_OPT=''; \
  fi; \
  exec java -Xms512m -Xmx1024m -XX:+UseG1GC ${OTEL_AGENT_OPT} -jar app.jar"]
