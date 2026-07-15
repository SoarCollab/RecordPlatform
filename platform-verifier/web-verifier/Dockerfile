# Build context: repository root
# Usage: docker build -f platform-verifier/web-verifier/Dockerfile -t recordplatform-verifier-web .

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY platform-verifier/ platform-verifier/
RUN mvn -f platform-verifier/pom.xml -pl web-verifier -am clean package -DskipTests -q

FROM eclipse-temurin:21-jre-noble AS runtime

RUN groupadd -r verifier \
    && useradd -r -g verifier -d /app -m -s /sbin/nologin verifier \
    && mkdir -p /tmp/record-platform-verifier \
    && chown verifier:verifier /tmp/record-platform-verifier

WORKDIR /app
COPY --from=build /workspace/platform-verifier/web-verifier/target/platform-verifier-web.jar app.jar

USER verifier
EXPOSE 8093

ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp/record-platform-verifier", "-XX:+UseG1GC", "-jar", "app.jar"]
