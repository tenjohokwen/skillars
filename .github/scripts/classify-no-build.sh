#!/usr/bin/env bash
# Decides whether pr-build.yml may skip its build, docker-image and frontend-quality jobs for a
# pull request the author has marked as not a code change (the `no-build` label).
#
#   classify-no-build.sh <base-sha> <head-sha> <labels>
#
# <labels> is the PR's current label names, newline- or comma-separated. Writes `skip=true` or
# `skip=false` to $GITHUB_OUTPUT.
#
# The label is a request, not a bypass: skip=true only when the label is present AND every file
# the PR changes is on the non-code allowlist below. A labelled PR that touches anything else is
# built normally (with a warning annotation saying why). Every failure path answers skip=false, so a
# broken classifier costs a build, never a skipped one — unlike detect-frontend-changes.sh this IS a
# merge gate, because the skipped jobs are the required status checks.
#
# Same three-dot/merge-base diff, --no-renames and core.quotePath=false reasoning as
# detect-frontend-changes.sh: diff only this PR's own changes, report a rename out of a code path
# as a delete of that path, and keep non-ASCII paths matchable by the anchored greps.
set -uo pipefail

: "${GITHUB_OUTPUT:=/dev/stdout}"

BASE_SHA="${1:?usage: classify-no-build.sh <base-sha> <head-sha> <labels>}"
HEAD_SHA="${2:?usage: classify-no-build.sh <base-sha> <head-sha> <labels>}"
LABELS="${3-}"

if ! printf '%s\n' "$LABELS" | tr ',' '\n' | sed 's/^ *//; s/ *$//' | grep -qx 'no-build'; then
  echo "No 'no-build' label: running the full build."
  echo "skip=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

if ! CHANGED_FILES=$(git -c core.quotePath=false diff --no-renames --name-only "${BASE_SHA}...${HEAD_SHA}"); then
  echo "::warning::no-build label present but the diff could not be computed (base=${BASE_SHA} head=${HEAD_SHA}); running the full build."
  echo "skip=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

if [ -z "$CHANGED_FILES" ]; then
  echo "::warning::no-build label present but the diff is empty; running the full build."
  echo "skip=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

# Non-code allowlist: documentation that neither the build, the Docker image (.dockerignore drops
# docs/) nor any test reads.
#   docs/**         — except the files tests read, listed in NEEDS_BUILD
#   _bmad-output/** — planning and story artifacts
#   *.md at the repository root (README.md, CLAUDE.md, ...)
# NEEDS_BUILD: docs/deployment/monitoring.md is parsed by AlertDocumentationParityTest. Add any
# other doc a test starts reading here, or a docs-only edit to it could skip the test that guards it.
ALLOWED='^(docs/|_bmad-output/|[^/]+\.md$)'
NEEDS_BUILD='^docs/deployment/monitoring\.md$'

CODE_FILES=$(printf '%s\n' "$CHANGED_FILES" | grep -Ev "$ALLOWED")
TESTED_DOCS=$(printf '%s\n' "$CHANGED_FILES" | grep -E "$NEEDS_BUILD")

if [ -n "$CODE_FILES" ] || [ -n "$TESTED_DOCS" ]; then
  echo "::warning::no-build label ignored: this PR changes files the build or tests depend on. Running the full build."
  printf '%s\n' "$CODE_FILES" "$TESTED_DOCS" | grep -v '^$'
  echo "skip=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

echo "no-build label honoured: every changed file is documentation. Skipping build, docker-image and frontend-quality."
printf '%s\n' "$CHANGED_FILES"
echo "skip=true" >> "$GITHUB_OUTPUT"
