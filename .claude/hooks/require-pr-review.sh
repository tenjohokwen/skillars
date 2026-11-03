#!/usr/bin/env bash
# PreToolUse hook on Bash: refuse `gh pr create` unless /pr-review has written a record for HEAD.
#
# Reads the hook's JSON on stdin and looks at tool_input.command. Blocks (exit 2, message on
# stderr) when the command creates a PR and .pr-review/<HEAD sha>.md is missing. Fails CLOSED on
# the PR-creating path: if the command cannot be parsed or HEAD cannot be resolved, a command that
# looks like `gh pr create` is blocked with a message rather than waved through.
#
# Escapes, neither of which is a substring match on free text:
# - a docs-only branch: every file changed against origin/master is on the CI classifier's
#   non-code allowlist (the same regex as .github/scripts/classify-no-build.sh), decided from git,
#   not from the command's text;
# - an explicit skip: the command STARTS with `PR_REVIEW_SKIP=1 `, so it is visible in the
#   transcript and cannot be smuggled in via --title or --body.
set -u
input="$(cat)"
# An actual invocation: at the start of the command or after ; & | ( or a newline, optionally
# preceded by VAR=value assignments. The phrase inside a string, heredoc line or comment that is
# not at a command boundary does not count, so editing docs that mention it is not blocked.
looks_like_pr_create() {
  printf '%s\n' "$1" | grep -Eq '(^|[;&|(])[[:space:]]*([A-Za-z_][A-Za-z0-9_]*=[^[:space:]"'"'"']*[[:space:]]+)*gh[[:space:]]+pr[[:space:]]+create([[:space:]]|$)'
}
# Loose check for input we could not parse: fail closed if the phrase appears anywhere.
mentions_pr_create() { printf '%s' "$1" | grep -q 'gh pr create'; }

if command -v python3 >/dev/null 2>&1; then
  cmd="$(printf '%s' "$input" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("tool_input",{}).get("command",""))
except Exception: print("__PARSE_FAILED__")' 2>/dev/null || echo "__PARSE_FAILED__")"
else
  cmd="__PARSE_FAILED__"
fi

if [ "$cmd" = "__PARSE_FAILED__" ]; then
  if mentions_pr_create "$input"; then
    echo "Blocked: the PR-review gate could not parse this command (python3 missing or malformed hook input) and it looks like \`gh pr create\`. Fix the gate or prefix the command with PR_REVIEW_SKIP=1 to skip deliberately." >&2
    exit 2
  fi
  exit 0
fi

looks_like_pr_create "$cmd" || exit 0
case "$cmd" in "PR_REVIEW_SKIP=1 "*) exit 0 ;; esac

root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "Blocked: \`gh pr create\` outside a git checkout; the PR-review gate cannot resolve HEAD." >&2; exit 2; }
sha="$(git -C "$root" rev-parse HEAD 2>/dev/null)" || { echo "Blocked: the PR-review gate cannot resolve HEAD." >&2; exit 2; }
[ -f "$root/.pr-review/$sha.md" ] && exit 0

# Docs-only branch: nothing the build or tests read changed, so no code review is required.
ALLOWED='^(docs/|_bmad-output/|evals/|\.claude/|[^/]+\.md$)'
if git -C "$root" rev-parse --verify -q origin/master >/dev/null 2>&1; then
  changed="$(git -C "$root" -c core.quotePath=false diff --no-renames --name-only origin/master...HEAD 2>/dev/null)"
  if [ -n "$changed" ] && ! printf '%s\n' "$changed" | grep -Evq "$ALLOWED"; then
    exit 0
  fi
fi

cat >&2 <<MSG
Blocked: no /pr-review record for HEAD (${sha:0:12}) at .pr-review/${sha:0:12}….md.
Run /pr-review on this branch first (and again after every new commit; the record is keyed to the commit).
A branch that changes only docs/, _bmad-output/, evals/, .claude/ or root *.md passes automatically.
To skip deliberately, start the command with: PR_REVIEW_SKIP=1 gh pr create ...
MSG
exit 2
