#!/usr/bin/env bash
# Samples running container images while the test suite runs, then asserts the peak
# concurrent count per image never exceeded a ceiling.
#
# Story deferred-19, AC1: "at most one postgres, one redis and one minio container is
# present (plus Testcontainers' own ryuk reaper), for the entire run". That criterion is
# not something a human should be watching a terminal for -- this is the automated form,
# and it runs in CI as easily as locally.
#
#   start:   container-sampler.sh start  <samples-file>
#   assert:  container-sampler.sh assert <samples-file> [ceiling]
#
# `start` backgrounds a sampling loop and prints its PID. `assert` stops the sampler and
# exits non-zero if any watched image exceeded the ceiling (default 1).

set -uo pipefail

MODE="${1:?usage: container-sampler.sh start|assert <samples-file> [ceiling]}"
SAMPLES="${2:?samples file required}"
CEILING="${3:-1}"

# Images this story constrains. ryuk is Testcontainers' own reaper and is explicitly
# excluded by AC1; it is not a per-context container.
WATCHED_RE='postgres|redis|minio'

case "$MODE" in
  start)
    : > "$SAMPLES"
    (
      while true; do
        # One line per sample point: a count per image seen at this instant.
        docker ps --format '{{.Image}}' 2>/dev/null \
          | grep -Ei "$WATCHED_RE" \
          | grep -vi 'ryuk' \
          | sort | uniq -c \
          | awk '{print $2" "$1}' >> "$SAMPLES"
        echo "---" >> "$SAMPLES"
        sleep 2
      done
    ) &
    echo $! > "${SAMPLES}.pid"
    echo "sampler started (pid $(cat "${SAMPLES}.pid")) -> $SAMPLES"
    ;;

  assert)
    if [ -f "${SAMPLES}.pid" ]; then
      kill "$(cat "${SAMPLES}.pid")" 2>/dev/null || true
      rm -f "${SAMPLES}.pid"
    fi

    if [ ! -s "$SAMPLES" ]; then
      echo "container-sampler: no samples recorded in $SAMPLES" >&2
      exit 1
    fi

    echo "=== peak concurrent containers by image (ceiling: $CEILING) ==="
    # For each image, the maximum count observed at any single sample point.
    peaks=$(grep -v '^---$' "$SAMPLES" \
      | awk '{ if ($2 > max[$1]) max[$1] = $2 } END { for (i in max) print i, max[i] }' \
      | sort)

    if [ -z "$peaks" ]; then
      echo "  (no watched containers ever observed)"
      exit 0
    fi

    echo "$peaks" | sed 's/^/  /'

    breached=$(echo "$peaks" | awk -v c="$CEILING" '$2 > c { print }')
    if [ -n "$breached" ]; then
      echo
      echo "FAIL: peak concurrent container count exceeded $CEILING:" >&2
      echo "$breached" | sed 's/^/  /' >&2
      echo "AC1 requires at most one postgres, one redis and one minio per test JVM." >&2
      exit 1
    fi

    echo
    echo "OK: no watched image exceeded $CEILING concurrent container(s)."
    ;;

  *)
    echo "unknown mode: $MODE" >&2
    exit 2
    ;;
esac
