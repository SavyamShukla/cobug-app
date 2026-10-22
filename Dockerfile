# -------------------------------------------------------------
# Stage 1: Build JAR with Maven & Eclipse Temurin JDK 21
# -------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Limit Maven memory so builds do not exceed Render's 512MB limit
ENV MAVEN_OPTS="-Xmx384m -XX:+UseSerialGC"

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests -B

# -------------------------------------------------------------
# Stage 2: Minimal, secure JRE 21 runtime
# -------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as non-root user
RUN groupadd -r cobug && useradd -r -g cobug cobug

COPY --from=builder /build/target/cobug-api-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/storage/main /app/storage/workspace /app/storage/project-files /app/storage/source-artifacts && chown -R cobug:cobug /app

USER cobug

ENV PORT=8080
EXPOSE ${PORT}

# Optimized JVM runtime flags for container environments with memory limits
ENTRYPOINT ["sh", "-c", "exec java -Xmx384m -XX:+UseContainerSupport -Dserver.port=${PORT} -jar app.jar"]
