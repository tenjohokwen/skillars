# Stage 1: Build (Java + Quasar frontend via frontend-maven-plugin)
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Layer: Java dependencies (cached until pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true

# Layer: Full source (Java + frontend — node binary downloaded here by frontend-maven-plugin)
COPY src/ src/
RUN mvn package -Dmaven.test.skip=true -B

# Stage 2: Runtime (minimal JRE image)
FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

COPY --chown=appuser:appgroup --from=builder /app/target/skillars-*.jar app.jar
EXPOSE 9990 8367
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8367/manage/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
