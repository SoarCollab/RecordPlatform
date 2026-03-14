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

COPY --from=build /workspace/platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar app.jar

RUN chown -R app:app /app
USER app

EXPOSE 8000

ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", \
  "-Xms512m", "-Xmx1024m", "-XX:+UseG1GC", \
  "-jar", "app.jar"]
