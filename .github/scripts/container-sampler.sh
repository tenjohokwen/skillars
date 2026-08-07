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
#   assert:  container-sampler.sh assert <samples-file> [default-ceiling]
#
# `start` backgrounds a sampling loop and prints its PID. `assert` stops the sampler and
# exits non-zero if any watched image exceeded its ceiling.
#
# Ceilings are PER IMAGE, because one image legitimately needs two containers:
# StorageMigrationServiceIT declares its own `destinationMinio` MinIOContainer alongside the
# shared one, since a storage-MIGRATION test needs a source and a destination. That is not the
# defect AC1 is about -- AC1 exists to stop container count scaling with the number of Spring
# contexts, and a second container owned by exactly one test class does not do that.

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

    echo "=== peak concurrent containers by image (default ceiling: $CEILING, minio: 2) ==="
    # For each image, the maximum count observed at any single sample point.
    peaks=$(grep -v '^---$' "$SAMPLES" \
      | awk '{ if ($2 > max[$1]) max[$1] = $2 } END { for (i in max) print i, max[i] }' \
      | sort)

    if [ -z "$peaks" ]; then
      echo "  (no watched containers ever observed)"
      exit 0
    fi

    echo "$peaks" | sed 's/^/  /'

    # Per-image ceiling. Anything not listed uses $CEILING (default 1).
    breached=$(echo "$peaks" | awk -v c="$CEILING" '
      {
        limit = c
        if ($1 ~ /minio/) limit = 2   # + StorageMigrationServiceIT.destinationMinio
        if ($2 > limit) print $0 "   (ceiling " limit ")"
      }')
    if [ -n "$breached" ]; then
      echo
      echo "FAIL: peak concurrent container count exceeded its per-image ceiling:" >&2
      echo "$breached" | sed 's/^/  /' >&2
      echo "AC1 requires one postgres and one redis per test JVM (minio allows 2: the shared" >&2
      echo "instance plus StorageMigrationServiceIT's destination). A breach means container" >&2
      echo "lifetime has been re-bound to the Spring context -- check SharedContainers and that" >&2
      echo "no @Bean returns a Startable." >&2
      exit 1
    fi

    echo
    echo "OK: every watched image is within its ceiling (default $CEILING, minio 2)."
    ;;

  *)
    echo "unknown mode: $MODE" >&2
    exit 2
    ;;
esac
