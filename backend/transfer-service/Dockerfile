# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Retry transient TLS/network failures (e.g. "bad_record_mac") when talking to Maven Central.
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=60"
# Warm the dependency cache for faster rebuilds. This is only an optimization, so a
# partial/flaky failure here must not fail the build — `package` below fetches anything missing.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Run as a non-root user.
RUN useradd -r -u 1001 appuser
COPY --from=build /build/target/transfer-service.jar app.jar
USER appuser
EXPOSE 8080
# Container-aware JVM memory settings.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
