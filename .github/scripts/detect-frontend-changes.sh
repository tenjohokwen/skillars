#!/usr/bin/env bash
# skillars-deferred-129 AC2: computes whether a pull request's diff touches src/frontend/**, so
# frontend-unit-tests.yml's frontend-unit job can auto-trigger without depending on a human
# remembering to add the frontend-tests label.
#
#   detect-frontend-changes.sh <base-sha> <head-sha>
#
# Writes `changed=true`/`changed=false` to $GITHUB_OUTPUT — call this from a workflow step with
# `id: <step-id>` and read `steps.<step-id>.outputs.changed`.
#
# Three-dot/merge-base diff (git diff --name-only BASE...HEAD), NOT two-dot (git diff BASE HEAD):
# two-dot includes every change that has landed on the base branch since this PR's own branch
# point, which would false-positive a backend-only PR opened before someone else merged an
# unrelated frontend change onto the base branch (story-review.md M8.1). The caller must check
# out with enough history for these two SHAs' merge-base to actually be resolvable (a shallow,
# single-commit checkout will not do) — see frontend-unit-tests.yml's own checkout step.
set -uo pipefail

# code review 2026-09-23: default to stdout so a local/manual invocation (outside a workflow step)
# prints the result instead of dying under `set -u` on an unset $GITHUB_OUTPUT, after already having
# done the (potentially slow) git diff work above this point.
: "${GITHUB_OUTPUT:=/dev/stdout}"

BASE_SHA="${1:?usage: detect-frontend-changes.sh <base-sha> <head-sha>}"
HEAD_SHA="${2:?usage: detect-frontend-changes.sh <base-sha> <head-sha>}"

# --no-renames: a file renamed OUT of src/frontend/ would otherwise be reported only under its NEW
# (non-frontend) path — `git diff --name-only` shows just the post-image path for a detected rename
# — silently hiding that a frontend file changed. Disabling rename detection reports it as a
# straight delete of the old src/frontend/... path instead, which the grep below does catch.
# -c core.quotePath=false: without this, a non-ASCII path is C-quoted (wrapped in double quotes with
# octal escapes), so it no longer starts with "src/frontend/" as far as the anchored grep below is
# concerned.
#
# code review 2026-09-23: fail OPEN (changed=true), not closed, if the diff itself cannot be
# computed — this is only a cost-saving skip for the (non-gating, opt-in) frontend suite, not a
# security boundary; a spurious extra ~10-minute run is a far cheaper mistake than silently skipping
# real frontend coverage because of a transient git/checkout error.
if ! CHANGED_FILES=$(git -c core.quotePath=false diff --no-renames --name-only "${BASE_SHA}...${HEAD_SHA}"); then
  echo "git diff failed (base=${BASE_SHA} head=${HEAD_SHA}) — failing open, treating as changed."
  echo "changed=true" >> "$GITHUB_OUTPUT"
  exit 0
fi

if echo "$CHANGED_FILES" | grep -q '^src/frontend/'; then
  echo "Frontend paths changed:"
  echo "$CHANGED_FILES" | grep '^src/frontend/'
  echo "changed=true" >> "$GITHUB_OUTPUT"
else
  echo "No src/frontend/** changes detected."
  echo "changed=false" >> "$GITHUB_OUTPUT"
fi
