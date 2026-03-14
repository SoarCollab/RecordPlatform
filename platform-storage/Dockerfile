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

COPY --from=build /workspace/platform-storage/target/platform-storage-0.0.1-SNAPSHOT.jar app.jar

RUN chown -R app:app /app
USER app

EXPOSE 8092

ENV SPRING_PROFILES_ACTIVE=prod

ENTRYPOINT ["java", \
  "-Xms512m", "-Xmx1024m", "-XX:+UseG1GC", \
  "-jar", "app.jar"]
