# Stage 1: Build (Java + Quasar frontend via frontend-maven-plugin)
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Layer: Java dependencies (cached until pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true

# Layer: Full source (Java + frontend — node binary downloaded here by frontend-maven-plugin)
COPY src/ src/
COPY .git/ .git/
RUN mvn package -Dmaven.test.skip=true -B

# Stage 2: Runtime (minimal JRE image)
FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
WORKDIR /app

# Patch OS packages before dropping privileges. The eclipse-temurin:17-jre-alpine tag
# lags Alpine's package index, so Trivy flags packages the base layer ships stale --
# e.g. libexpat (CVE-2026-66046 / CVE-2026-76641, fixed in 2.8.4-r0). `apk upgrade`
# pulls the fixed builds from the same Alpine release the base image is pinned to.
#
# APK_UPGRADE_CACHE_BUST busts the build cache for this layer on every CI build (the
# workflow feeds it a per-run value). Without it, BuildKit keys the `apk upgrade` layer
# only on the command string, so a cached layer from days ago keeps shipping the stale
# packages even after Alpine publishes the fix -- which is exactly how PR #136's Trivy
# gate started failing on an already-patched CVE.
#
# This trades a little build reproducibility for a clean scan: two builds of the same
# commit on different days can pick up different package builds. That is the accepted
# tradeoff while the pr-build Trivy gate runs with exit-code 1 on HIGH.
ARG APK_UPGRADE_CACHE_BUST=local
RUN echo "apk upgrade cache-bust: ${APK_UPGRADE_CACHE_BUST}" && apk upgrade --no-cache

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"

COPY --chown=appuser:appgroup --from=builder /app/target/skillars-*.jar app.jar
EXPOSE 9990 8367
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8367/manage/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
