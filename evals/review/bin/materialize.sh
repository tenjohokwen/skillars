#!/usr/bin/env bash
# Materialise a review-eval case's input OUTSIDE the repository.
#
# The reviewer under test must not be able to see the fix. A git worktree inside the checkout
# would let it: the worktree shares the repo's object database (`git show <fix>` works), its
# parent folders hold the answer-sheet YAML and the HEAD checkout, and Claude Code loads
# `.claude/` settings and skills from parent folders. So each case is exported with
# `git archive` (no .git, no history) into a directory outside the repo, with the repo's own
# `.claude/`, `_bmad*/` and `evals/` stripped from the export.
#
#   introducing-diff   -> <work>/<id>/input.diff            (plus input.rev)
#   pre-fix-snapshot   -> <work>/<id>/tree/                 (plus input.rev, scope.txt)
#
# <work> defaults to ${XDG_CACHE_HOME:-$HOME/.cache}/skillars-review-evals; override with
# SKILLARS_REVIEW_EVALS_WORK. The reviewer is run FROM <work>/<id>/tree (snapshot) or
# <work>/<id> (diff), never from the repo. See evals/review/README.md.
#
# Usage: evals/review/bin/materialize.sh <case-id> [--clean]
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
id="${1:?case id}"
case_file="${root}/evals/review/cases/${id}.yaml"
[ -f "$case_file" ] || case_file="${root}/evals/review/cases/_drafts/${id}.yaml"
[ -f "$case_file" ] || { echo "no such case: ${id}" >&2; exit 1; }

work_root="${SKILLARS_REVIEW_EVALS_WORK:-${XDG_CACHE_HOME:-$HOME/.cache}/skillars-review-evals}"
case "$work_root" in
  "$root"|"$root"/*) echo "work dir must be outside the repository: $work_root" >&2; exit 1 ;;
esac
work="${work_root}/${id}"

if [ "${2:-}" = "--clean" ]; then
  rm -rf "$work"
  echo "cleaned $work"
  exit 0
fi

# Minimal YAML scalar reader; the schema keeps these keys top-level and single-line.
field() { sed -n "s/^${1}:[[:space:]]*\([^#]*\).*/\1/p" "$case_file" | head -1 | sed 's/[[:space:]]*$//'; }

kind="$(field kind)"
mkdir -p "$work"

case "$kind" in
  introducing-diff)
    m="$(field introducing_merge)"
    [ -n "$m" ] || { echo "introducing_merge missing" >&2; exit 1; }
    rev="$(git -C "$root" rev-parse --verify "${m}^{commit}")"
    git -C "$root" diff "${rev}^1" "$rev" > "${work}/input.diff"
    echo "$rev" > "${work}/input.rev"
    echo "wrote ${work}/input.diff ($(wc -l < "${work}/input.diff" | tr -d ' ') lines) at ${rev:0:8}"
    ;;
  pre-fix-snapshot)
    m="$(field fix_merge)"
    [ -n "$m" ] || { echo "fix_merge missing" >&2; exit 1; }
    rev="$(git -C "$root" rev-parse --verify "${m}^1^{commit}")"
    tree="${work}/tree"
    # Re-export if the case now points at a different commit than the existing export.
    if [ -d "$tree" ] && [ "$(cat "${work}/input.rev" 2>/dev/null)" != "$rev" ]; then
      echo "existing export is at $(cut -c1-8 "${work}/input.rev" 2>/dev/null || echo '?'), case wants ${rev:0:8}; re-exporting"
      rm -rf "$tree"
    fi
    if [ ! -d "$tree" ]; then
      mkdir -p "$tree"
      git -C "$root" archive --format=tar "$rev" | tar -x -C "$tree"
      rm -rf "${tree}/.claude" "${tree}/_bmad" "${tree}/_bmad-output" "${tree}/evals"
    fi
    echo "$rev" > "${work}/input.rev"
    awk '/^scope:/{f=1;next} f&&/^[^ -]/{f=0} f&&/^ *- /{sub(/^ *- */,"");print}' "$case_file" > "${work}/scope.txt"
    echo "exported ${tree} at ${rev:0:8}; scope: $(wc -l < "${work}/scope.txt" | tr -d ' ') paths"
    ;;
  *) echo "unknown kind: '$kind'" >&2; exit 1 ;;
esac
