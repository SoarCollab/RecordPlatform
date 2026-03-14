# Build context: repository root (platform-api is a cross-module dependency)
# Usage: docker build -f platform-fisco/Dockerfile -t recordplatform-fisco .
#
# TLS certs (ca.crt, sdk.crt, sdk.key, etc.) are NOT bundled into the image.
# Mount them at runtime: -v /host/certs:/app/conf:ro

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Install shared interfaces
COPY platform-api/ platform-api/
RUN mvn -f platform-api/pom.xml clean install -DskipTests -q

# Build FISCO blockchain service
COPY platform-fisco/ platform-fisco/
RUN mvn -f platform-fisco/pom.xml clean package -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble AS runtime

# FISCO Java SDK unpacks native JNI libs to ${user.home}/.fisco on startup.
# -d /app ensures user home is /app so the SDK can write there.
RUN groupadd -r app && useradd -r -g app -d /app -m -s /sbin/nologin app
WORKDIR /app

COPY --from=build /workspace/platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar app.jar

# Directory for TLS certificate files (mount at runtime)
RUN mkdir -p /app/conf && chown -R app:app /app
USER app

EXPOSE 8091

ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", \
  "-Xms512m", "-Xmx1024m", "-XX:+UseG1GC", \
  "-jar", "app.jar"]
