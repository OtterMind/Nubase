# Multi-stage build for nubase backend (Memory · Database · Storage · Auth).

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source code
COPY src ./src

# Fail closed if a local configuration file bypasses .dockerignore.
RUN ! find src -type f \( \
      -name '.env' -o -name '.env.*' -o \
      -name 'application-dev.yml' -o -name 'application-local.yml' -o \
      -name 'application-prod.yml' -o -name 'application-secrets.yml' -o \
      -name '*.pem' -o -name '*.key' -o -name '*.p12' -o -name '*.pfx' -o \
      -name '*.jks' -o -name '*.keystore' \
    \) -print -quit | grep -q .

# Build the application
RUN mvn clean package -DskipTests

# Stage 2: Runtime. Jammy is available for both amd64 and arm64.
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Install the health-check client and create a non-root runtime user.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 1001 appgroup && \
    useradd --uid 1001 --gid appgroup --no-create-home --shell /usr/sbin/nologin appuser

# Copy the JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 9999

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -fsS -o /dev/null http://localhost:9999/auth/v1/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
