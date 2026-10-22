# -------------------------------------------------------------
# Stage 1: Build JAR with Maven & Eclipse Temurin JDK 21
# -------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy Maven descriptor and fetch dependencies (layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and package application jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# -------------------------------------------------------------
# Stage 2: Minimal, secure JRE 21 runtime
# -------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user
RUN addgroup -S cobug && adduser -S cobug -G cobug

COPY --from=builder /build/target/*.jar app.jar
RUN chown -R cobug:cobug /app

USER cobug

ENV PORT=8080
EXPOSE ${PORT}

# Optimized JVM flags for container environments with memory limits
ENTRYPOINT ["sh", "-c", "exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dserver.port=${PORT} -jar app.jar"]
