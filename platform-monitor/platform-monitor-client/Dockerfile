# Multi-stage build for Monitor Client
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

# Install required packages for system monitoring
RUN apt-get update && apt-get install -y \
    curl \
    procps \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built jar
COPY --from=builder /app/target/platform-monitor-client-*.jar app.jar

# Create directories for client configuration and certificates
RUN mkdir -p /app/config /app/certs
RUN chown -R monitor:monitor /app
USER monitor

# Expose port for client management
EXPOSE 8086

# JVM optimization for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]