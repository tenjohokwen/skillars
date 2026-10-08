# Stage 1: Build (Java + Quasar frontend via frontend-maven-plugin)
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Layer: Java dependencies (cached until pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true

# Layer: Full source (Java + frontend — node binary downloaded here by frontend-maven-plugin)
COPY src/ src/

# skillars-deferred-149 AC7: git-commit-id-maven-plugin's own git-based generation is skipped
# here (-Dmaven.gitcommitid.skip=true) because .git/ is no longer COPYed into the builder at all
# — the Dockerfile's own COPY instructions previously brought in only three paths (pom.xml, src/,
# .git/), and JGit's dirty-check compared that necessarily-partial working directory against the
# full tree it expected, found every OTHER tracked path "deleted", and stamped git.dirty=true
# into every build regardless of how clean the real commit was. This RUN instruction references
# no ARG, so it stays cacheable across builds whose SHA differs but whose src/ is byte-identical
# — see the GIT_COMMIT_SHA step below for why that matters.
RUN mvn package -Dmaven.test.skip=true -Dmaven.gitcommitid.skip=true -B

# Code review (2026-10-08): deliberately placed AFTER the expensive mvn package above, not
# before it. BuildKit folds an ARG's current value into the cache key of every instruction that
# references it, so if this ARG were declared/used ahead of `mvn package`, a GIT_COMMIT_SHA that
# changes between builds (e.g. a PR's merge-commit SHA shifts whenever its base branch advances,
# even with no new commits on the PR itself) would bust that layer's cache and every layer after
# it — including the full Maven + Quasar build this story's immediate predecessor
# (skillars-deferred-146) split into its own job specifically to stop re-paying. Patching the
# already-built jar in place here, using the full JDK's own `jar` tool (this stage is the
# `-eclipse-temurin` JDK image, not the slim JRE runtime stage), keeps the SHA-dependent step
# cheap and isolated to one small layer regardless of how the Maven build was cached.
#
# git.dirty is derived, not hardcoded: a real CI build always supplies a genuine SHA (both
# ci.yml and pr-build.yml pass github.sha explicitly), so it reports dirty=false. A bare local
# `docker build .` with no --build-arg falls back to GIT_COMMIT_SHA=local (this ARG's own
# default, matching docker-build/action.yml's `commit-sha` input default) and must NOT also claim
# dirty=false — that would assert a clean, attributable build of a commit that does not exist,
# regardless of whether the local working tree is actually clean.
ARG GIT_COMMIT_SHA=local
RUN JAR_DIRTY=false; \
    if [ "${GIT_COMMIT_SHA}" = "local" ]; then JAR_DIRTY=true; fi; \
    mkdir -p /tmp/gitprops/BOOT-INF/classes && \
    printf '#Generated at Docker build time (skillars-deferred-149 AC7)\ngit.commit.id.full=%s\ngit.commit.id.abbrev=%s\ngit.dirty=%s\n' \
      "${GIT_COMMIT_SHA}" "$(echo "${GIT_COMMIT_SHA}" | cut -c1-7)" "${JAR_DIRTY}" \
      > /tmp/gitprops/BOOT-INF/classes/git.properties && \
    JAR_FILE=$(ls target/skillars-*.jar) && \
    jar uf "${JAR_FILE}" -C /tmp/gitprops BOOT-INF/classes/git.properties

# Stage 2: Runtime (minimal JRE image)
FROM eclipse-temurin:17-jre-alpine
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
