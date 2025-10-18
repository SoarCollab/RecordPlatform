# Multi-stage build for API Gateway
FROM openjdk:21-jdk-slim as builder

WORKDIR /app
COPY pom.xml .
COPY ../pom.xml ../pom.xml
COPY ../monitor-common ../monitor-common
COPY src src

# Build the application
RUN apt-get update && apt-get install -y maven
RUN mvn clean package -DskipTests

FROM openjdk:21-jre-slim

# Create non-root user for security
RUN groupadd -r monitor && useradd -r -g monitor monitor

# Install required packages
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built jar
COPY --from=builder /app/target/monitor-api-gateway-*.jar app.jar

# Change ownership to non-root user
RUN chown -R monitor:monitor /app
USER monitor

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# JVM optimization for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]