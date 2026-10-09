#!/usr/bin/env bash
# Fails the build when the Docker image's embedded git.properties does not prove it was
# built from a real, clean, attributable commit. Story skillars-deferred-150, AC6.
#
#   assert-git-provenance.sh <image-tag> [expected-commit-sha]
#
# expected-commit-sha is optional (omit it to only check dirty=false/non-local, as callers
# that don't yet know their own commit SHA may do), but every real CI caller should pass it --
# code review 2026-10-09: without it, this gate only proves "some non-local SHA was embedded,"
# not that it is THIS run's own commit. A caching/ARG-propagation bug that embedded a stale
# SHA from an unrelated build would still satisfy the non-empty/non-local checks alone.
#
# skillars-deferred-149 AC7 fixed a permanent git.dirty=true false positive by reordering
# the Dockerfile and patching git.properties into the built jar post-`mvn package` (see
# Dockerfile's own comment above that RUN instruction). Nothing in CI read the result back
# until this script -- a future git-commit-id-maven-plugin upgrade with a renamed/changed
# skip property, or a bound execution overriding the patch step's output, could silently
# reintroduce exactly that bug with CI reporting green throughout.
#
# git.properties lives INSIDE the jar, not as a loose file on the image filesystem --
# Dockerfile:40-47 patches it into BOOT-INF/classes/git.properties inside the already-built
# jar via `jar uf`; the runtime stage's only COPY brings in app.jar itself, nothing else.
# So this must create a container, copy the jar OUT, then unzip the entry from inside it --
# a bare `docker cp <ctr>:/app/BOOT-INF/classes/git.properties` does not exist on the
# container filesystem and fails immediately.

set -uo pipefail

IMAGE_TAG="${1:?usage: assert-git-provenance.sh <image-tag> [expected-commit-sha]}"
EXPECTED_SHA="${2:-}"

# mktemp failure (disk full, TMPDIR unwritable) must abort here under `set -u` alone (no `-e`
# in this script, by design -- see the FAILED-accumulator pattern below) -- code review
# 2026-10-09: an unguarded mktemp failure would leave WORKDIR empty and every subsequent
# ${WORKDIR}/... path would silently resolve to a bare relative filename instead of failing
# loudly here.
WORKDIR="$(mktemp -d)" || { echo "FAIL: mktemp -d failed -- cannot create a scratch directory."; exit 1; }
CONTAINER_ID=""

cleanup() {
  if [ -n "$CONTAINER_ID" ]; then
    docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

CONTAINER_ID=$(docker create "$IMAGE_TAG") || {
  echo "FAIL: could not create a container from image '$IMAGE_TAG'."
  exit 1
}

# stderr intentionally surfaced (not discarded) on failure -- code review 2026-10-09: silencing
# it made every failure here look identical ("could not copy"), hiding the real Docker/unzip
# error (permission denied, no space left, corrupt archive, etc.) that would explain why.
if ! DOCKER_CP_ERR="$(docker cp "${CONTAINER_ID}:/app/app.jar" "${WORKDIR}/app.jar" 2>&1)"; then
  echo "FAIL: could not copy /app/app.jar out of the built image -- the runtime stage's"
  echo "WORKDIR/jar name may have changed; see Dockerfile's runtime COPY instruction."
  echo "docker cp error: ${DOCKER_CP_ERR}"
  exit 1
fi

if ! GIT_PROPERTIES="$(unzip -p "${WORKDIR}/app.jar" BOOT-INF/classes/git.properties 2>&1)"; then
  echo "FAIL: BOOT-INF/classes/git.properties was not found inside app.jar. Either the"
  echo "Dockerfile's post-package patch step did not run, or it patched the wrong jar."
  echo "unzip error: ${GIT_PROPERTIES}"
  exit 1
fi
if [ -z "$GIT_PROPERTIES" ]; then
  echo "FAIL: BOOT-INF/classes/git.properties was empty inside app.jar."
  exit 1
fi

echo "--- git.properties ---"
echo "$GIT_PROPERTIES"
echo "----------------------"

# `.` escaped in both patterns -- code review 2026-10-09: an unescaped `.` is "any character"
# in a regex, not a literal dot. Harmless today (no other key shares this shape), but this
# script's whole purpose is detecting a renamed/changed key, so its own patterns should not be
# sloppy about exact matches.
GIT_DIRTY="$(echo "$GIT_PROPERTIES" | grep -m1 '^git\.dirty=' | cut -d= -f2)"
COMMIT_SHA="$(echo "$GIT_PROPERTIES" | grep -m1 '^git\.commit\.id\.full=' | cut -d= -f2)"

FAILED=0

if [ "$GIT_DIRTY" != "false" ]; then
  echo "FAIL: git.dirty='${GIT_DIRTY:-<missing>}', expected 'false'. A real CI build always"
  echo "supplies a genuine commit SHA (ci.yml/pr-build.yml both pass github.sha explicitly)"
  echo "and must report a clean, attributable build."
  FAILED=1
fi

if [ -z "$COMMIT_SHA" ] || [ "$COMMIT_SHA" = "local" ]; then
  echo "FAIL: git.commit.id.full='${COMMIT_SHA:-<missing>}', expected a real, non-empty"
  echo "commit SHA. 'local' is the Dockerfile ARG's own fallback for a build with no"
  echo "--build-arg GIT_COMMIT_SHA supplied -- a real CI build must never fall back to it."
  FAILED=1
fi

if [ -n "$EXPECTED_SHA" ] && [ "$COMMIT_SHA" != "$EXPECTED_SHA" ]; then
  echo "FAIL: git.commit.id.full='${COMMIT_SHA}' does not match this run's own commit"
  echo "'${EXPECTED_SHA}'. A non-empty, non-local SHA alone only proves SOME real build"
  echo "produced this jar -- not that it was built from the commit this run actually checked"
  echo "out, which a stale layer-cache or ARG-propagation bug could otherwise mask."
  FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  exit 1
fi

echo "OK: image reports a clean build (git.dirty=false) from commit ${COMMIT_SHA}."
