# Build context: repository root (platform-api is a cross-module dependency)
# Usage: docker build -f platform-backend/Dockerfile -t recordplatform-backend .

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Install platform-api (shared interface definitions required by backend)
COPY platform-api/ platform-api/
RUN mvn -f platform-api/pom.xml clean install -DskipTests -q

# Build backend-web and its module dependencies
COPY platform-backend/ platform-backend/
RUN mvn -f platform-backend/pom.xml clean package \
      -pl backend-web -am \
      -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble AS runtime

# Non-root user for security
RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app
WORKDIR /app

# Download OpenTelemetry Java Agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
RUN chmod 644 /opt/otel/opentelemetry-javaagent.jar

COPY --from=build /workspace/platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar app.jar

RUN chown -R app:app /app
USER app

EXPOSE 8000

ENV SPRING_PROFILES_ACTIVE=prod

# OTel Java Agent configuration
ENV OTEL_SERVICE_NAME=record-platform-web
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
