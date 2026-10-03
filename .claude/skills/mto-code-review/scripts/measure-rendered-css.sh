#!/usr/bin/env bash
#
# measure-rendered-css.sh — render a standalone HTML harness in a real browser engine and
# print the measurements it computed.
#
# WHY THIS EXISTS
# ---------------
# skillars-deferred-141 shipped a CSS fix that sounded correct and was completely inert: the
# story asserted "a scoped attribute-selector rule reliably outranks a same-specificity global
# utility class", which is true and irrelevant — the actual suppressor was the Quasar gutter's
# NEGATIVE top margin on the sibling <q-form>, which collapsed the banner's +16px to a net 0px.
# Two review layers reasoned from the cascade and got it wrong in opposite directions. The only
# thing that settled it was measuring getBoundingClientRect() in a real engine. Margin
# collapsing, inline-flex vs block, and !important interactions are not reliably derivable by
# reading stylesheets — MEASURE THEM.
#
# USAGE
#   measure-rendered-css.sh <harness.html>
#
# The harness must set an element's textContent to "RESULTS=" followed by JSON. See
# harness-template.html next to this script. Any stylesheet the harness <link>s must sit beside
# it — copy the real quasar.css / app CSS in, never hand-write a guess at it.
#
# Exits non-zero with a clear message if no usable browser is found or the render fails, so the
# caller reports "could not measure" instead of silently falling back to reasoning.

set -uo pipefail

BUDGET_SECS=90

HARNESS="${1:-}"
if [[ -z "$HARNESS" ]]; then
  echo "usage: $(basename "$0") <harness.html>" >&2
  exit 2
fi
if [[ ! -f "$HARNESS" ]]; then
  echo "error: harness file not found: $HARNESS" >&2
  exit 2
fi

find_browser() {
  # Playwright's cached Chrome-for-Testing (macOS then Linux).
  local d c
  for d in "$HOME/Library/Caches/ms-playwright"/chromium-* "$HOME/.cache/ms-playwright"/chromium-*; do
    [[ -d "$d" ]] || continue
    for c in \
      "$d/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" \
      "$d/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing" \
      "$d/chrome-mac/Chromium.app/Contents/MacOS/Chromium" \
      "$d/chrome-linux/chrome"; do
      [[ -x "$c" ]] && { printf '%s' "$c"; return 0; }
    done
  done
  for c in \
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
    "/Applications/Chromium.app/Contents/MacOS/Chromium" \
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"; do
    [[ -x "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  local b
  for b in google-chrome google-chrome-stable chromium chromium-browser microsoft-edge; do
    command -v "$b" >/dev/null 2>&1 && { command -v "$b"; return 0; }
  done
  return 1
}

BROWSER="$(find_browser)" || {
  cat >&2 <<'EOF'
error: no Chromium-based browser found.

Looked in: Playwright's cache (~/Library/Caches/ms-playwright, ~/.cache/ms-playwright),
/Applications, and $PATH.

Install one of:
  npx playwright install chromium
  brew install --cask google-chrome

Do NOT fall back to reasoning about the cascade from stylesheet source and present the result as
verified — report that the measurement could not be taken.
EOF
  exit 3
}

# Absolute file:// URL so a relative <link href="quasar.css"> resolves beside the harness.
ABS="$(cd "$(dirname "$HARNESS")" && pwd)/$(basename "$HARNESS")"

# NOTE: deliberately NO --user-data-dir. Pointing Chrome at a fresh temp profile makes headless
# --dump-dom hang indefinitely (verified on Chrome-for-Testing 1223/macOS: SIGALRM at 30s, zero
# bytes out). The flag set below is the verified-working one; add flags only after re-testing.
# `timeout` is not present on stock macOS, so the hard cap uses perl's alarm, which is portable.
RAW="$(perl -e 'alarm shift; exec @ARGV' "$BUDGET_SECS" \
  "$BROWSER" \
  --headless \
  --disable-gpu \
  --no-sandbox \
  --allow-file-access-from-files \
  --virtual-time-budget=5000 \
  --dump-dom "file://$ABS" 2>/dev/null)"
STATUS=$?

if [[ $STATUS -eq 142 ]]; then
  echo "error: browser did not finish within ${BUDGET_SECS}s (SIGALRM). Harness may have an infinite loop, or a flag was added that breaks headless --dump-dom." >&2
  exit 5
fi
if [[ -z "$RAW" ]]; then
  echo "error: browser produced no output (exit $STATUS)." >&2
  exit 5
fi

printf '%s' "$RAW" | python3 -c '
import sys, re, html, json
s = sys.stdin.read()
# The payload lives in an element textContent, so it cannot contain a raw "<" ("<" is escaped
# as &lt;). Matching [^<]* avoids the non-greedy-bracket bug that truncated nested arrays.
m = re.search(r"RESULTS=([^<]*)", s)
if not m or not m.group(1).strip():
    sys.stderr.write(
        "error: harness produced no RESULTS= payload.\n"
        "The harness must set an element textContent to \"RESULTS=\" + JSON.stringify(...).\n"
        "Check for a JS error in the harness, and that any linked stylesheet exists beside it.\n")
    sys.exit(4)
payload = html.unescape(m.group(1)).strip()
try:
    print(json.dumps(json.loads(payload), indent=1))
except json.JSONDecodeError as e:
    sys.stderr.write("error: RESULTS payload is not valid JSON: %s\n%s\n" % (e, payload[:800]))
    sys.exit(4)
'
