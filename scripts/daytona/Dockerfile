# Daytona Test Environment with Docker-in-Docker
# Supports: Java 21, Maven, Node.js 20, pnpm 10
#
# This image is designed to run RecordPlatform tests in Daytona sandbox
# with Testcontainers support (MySQL, Redis, RabbitMQ)

FROM docker:28.3.3-dind

# Install build tools and runtime dependencies
RUN apk add --no-cache \
    openjdk21 \
    maven \
    nodejs \
    npm \
    git \
    bash \
    curl \
    procps \
    coreutils

# Install pnpm 10 (matches project requirement)
RUN npm install -g pnpm@10

# Configure Java environment
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"
ENV MAVEN_OPTS="-Xmx1g"

# Create Maven repository directory for caching
RUN mkdir -p /root/.m2

# Set working directory
WORKDIR /workspace

# Copy test runner script
COPY run-tests.sh /workspace/
RUN chmod +x /workspace/run-tests.sh

# Default entrypoint: start Docker daemon
ENTRYPOINT ["dockerd-entrypoint.sh"]
