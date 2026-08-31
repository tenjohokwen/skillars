#!/usr/bin/env bash
# Restore PostgreSQL from a pg_dump archive stored in Hetzner Object Storage.
# Usage: ./restore-from-dump.sh <s3-object-key | latest>
#   Example: ./restore-from-dump.sh pg-backups/skillars-20260603T060000Z.sql.gz
#   Example: ./restore-from-dump.sh latest
set -euo pipefail

log() { echo "[restore-dump] $*"; }
err() { echo "[restore-dump][error] $*" >&2; }

# Fail loud on an empty OR non-numeric capture. Without this, `[ "$v" -lt N ]` on a non-numeric
# value (a psql NOTICE line, a partial capture) exits the `[` builtin with status 2 — and because
# every such test sits inside an `if … || …` condition, `set -e` is suppressed there, the `||`
# chain evaluates false, the `err …; exit 1` branch is skipped, and a corrupt restore is accepted.
assert_numeric() {
  case "$2" in
    ''|*[!0-9]*)
      err "Integrity check failed: $1 returned a non-numeric or empty value ('${2:-<none>}')."
      exit 1
      ;;
  esac
}

DUMP_KEY="${1:-}"
if [ -z "$DUMP_KEY" ]; then
  echo "Usage: $0 <s3-object-key | latest>" >&2
  exit 1
fi

GUARD_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
if [ -d "$GUARD_PATH" ]; then
  err "${GUARD_PATH} is a directory, not a file — cannot load credential guard"
  exit 1
fi
if [ ! -r "$GUARD_PATH" ]; then
  err "cannot read ${GUARD_PATH} — required for credential loading"
  exit 1
fi
# shellcheck source=env-guard.sh
. "$GUARD_PATH"
require_env_vars "restore-dump" "restore" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT POSTGRES_PASSWORD

PREFIX="${HOS_BACKUP_PREFIX:-pg-backups/}"
PREFIX="${PREFIX%/}/"

if [ "$DUMP_KEY" = "latest" ]; then
  log "Discovering latest dump in s3://${HOS_BUCKET}/${PREFIX}..."
  FILENAME=$(
    AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
    AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
    aws s3 ls "s3://${HOS_BUCKET}/${PREFIX}" \
      --endpoint-url "${HOS_ENDPOINT}" \
    | awk 'NF==4' | sort | tail -1 | awk '{print $4}'
  )
  if [ -z "$FILENAME" ]; then
    err "No dump files found in s3://${HOS_BUCKET}/${PREFIX}"
    exit 1
  fi
  DUMP_KEY="${PREFIX}${FILENAME}"
  log "Latest dump: ${DUMP_KEY}"
fi

DUMP_SIZE_BYTES=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 ls "s3://${HOS_BUCKET}/${DUMP_KEY}" \
    --endpoint-url "${HOS_ENDPOINT}" \
  | awk '{print $3}'
)
# Only run the /tmp free-space preflight when the size came back as a single plain integer. The
# `*[!0-9]*` arm rejects an empty value, a non-numeric line, AND a multi-line result (DUMP_KEY
# given as a prefix that matches an object plus a directory marker) — any of which would otherwise
# make the `$(( ))` below a bare `set -e` abort with no diagnostic.
case "${DUMP_SIZE_BYTES}" in
  ''|*[!0-9]*)
    log "Skipping /tmp free-space preflight — could not read a single numeric object size." ;;
  *)
    AVAIL_KB=$(df -k /tmp | awk 'NR==2 {print $4}')
    NEEDED_KB=$(( (DUMP_SIZE_BYTES + 1023) / 1024 ))
    if [ "${NEEDED_KB}" -gt "${AVAIL_KB}" ]; then
      err "Insufficient space in /tmp: need ${NEEDED_KB} KB, ${AVAIL_KB} KB available."
      exit 1
    fi ;;
esac

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
LOCAL_DUMP="/tmp/skillars-restore-${TIMESTAMP}.sql.gz"

log "Downloading s3://${HOS_BUCKET}/${DUMP_KEY}..."
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "s3://${HOS_BUCKET}/${DUMP_KEY}" "${LOCAL_DUMP}" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

if [ ! -s "${LOCAL_DUMP}" ]; then
  err "Downloaded file is empty or missing."
  exit 1
fi

log "Validating dump integrity..."
if ! gunzip -t "${LOCAL_DUMP}" 2>/dev/null; then
  err "Dump file failed gzip integrity check — aborting before database drop."
  exit 1
fi

log "Stopping app service..."
docker compose -f /opt/skillars/docker-compose.yml stop app

# From here on, any failure must not leave the app stopped indefinitely — restart it against
# whatever database is currently on disk rather than leaving an incident silent. Same intent and
# body as restore-from-volume-backup.sh's `trap restore_failed ERR`, but wired to EXIT with a
# success sentinel instead of ERR: the integrity checks below use explicit `err ...; exit 1`
# guards (so a defensively-captured psql failure still prints a diagnostic), and a bare `exit`
# does not fire an ERR trap — only an EXIT trap catches every failure path uniformly. Note the
# failure mode addressed is "app left stopped", NOT "database left half-restored": the
# drop+recreate below means a failure mid-psql leaves an empty/partial DB that the next restore
# run overwrites cleanly.
RESTORE_OK=0
restore_failed() {
  [ "${RESTORE_OK}" -eq 1 ] && return 0
  err "restore did not complete — restarting the app service with the database currently on disk so it does not stay down"
  docker compose -f /opt/skillars/docker-compose.yml start app || true
}
trap restore_failed EXIT

CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  err "postgres container not running."
  exit 1
fi

log "Dropping and recreating database ${POSTGRES_DB:-skillars}..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "DROP DATABASE IF EXISTS \"${POSTGRES_DB:-skillars}\";"
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "CREATE DATABASE \"${POSTGRES_DB:-skillars}\" OWNER \"${POSTGRES_USER:-postgres}\";"

log "Restoring dump (this may take several minutes)..."
gunzip -c "${LOCAL_DUMP}" | \
  docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  --set ON_ERROR_STOP=1

# Integrity check. Every capture below is wrapped `|| true` on the command substitution: under
# `set -euo pipefail` a bare `psql` failure would abort the script BEFORE the diagnostic could
# print (and before the EXIT trap's app-restart could run cleanly). `assert_numeric` then rejects
# an empty OR non-numeric capture with the intended "integrity check failed" message + exit 1.
run_psql() {
  docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
    psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
    -t -c "$1" 2>/dev/null | tr -d ' \n'
}

# Count user tables across ALL non-system schemas. This app puts almost every table in a named
# schema (main, booking, payment, session, development, marketplace, messaging, admin), so the
# old public-only count was near-meaningless. 80 is a conservative floor — the app has 117+
# Flyway migrations creating well over that many tables; only lower it if it ever false-fails on
# a legitimately smaller schema.
TABLE_COUNT=$(run_psql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema') AND table_type='BASE TABLE';" || true)
assert_numeric "user table count" "${TABLE_COUNT}"
if [ "${TABLE_COUNT}" -lt 80 ]; then
  err "Integrity check failed: expected >= 80 user tables across all schemas, found '${TABLE_COUNT}'."
  exit 1
fi
log "Integrity check: ${TABLE_COUNT} user tables found."

# flyway_schema_history must have restored intact: present, populated, and with no failed rows.
# Do NOT assert an expected max version — the image's migration set changes every release.
FLYWAY_PRESENT=$(run_psql "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='flyway_schema_history';" || true)
assert_numeric "flyway_schema_history presence check" "${FLYWAY_PRESENT}"
if [ "${FLYWAY_PRESENT}" -lt 1 ]; then
  err "Integrity check failed: flyway_schema_history table not found after restore."
  exit 1
fi

FLYWAY_ROWS=$(run_psql "SELECT COUNT(*) FROM flyway_schema_history;" || true)
assert_numeric "flyway_schema_history row count" "${FLYWAY_ROWS}"
if [ "${FLYWAY_ROWS}" -lt 100 ]; then
  err "Integrity check failed: flyway_schema_history has '${FLYWAY_ROWS}' rows, expected >= 100."
  exit 1
fi

FLYWAY_FAILED=$(run_psql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false;" || true)
assert_numeric "flyway_schema_history failed-migration count" "${FLYWAY_FAILED}"
if [ "${FLYWAY_FAILED}" -ne 0 ]; then
  err "Integrity check failed: flyway_schema_history has '${FLYWAY_FAILED}' failed migration(s), expected 0."
  exit 1
fi
log "Integrity check: flyway_schema_history intact (${FLYWAY_ROWS} rows, 0 failed)."

log "Starting app service..."
docker compose -f /opt/skillars/docker-compose.yml start app

APP_CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q app 2>/dev/null | head -1)
if [ -z "${APP_CID}" ]; then
  err "app container not found after 'docker compose start app' — cannot wait for health, failing fast instead of burning the 90s timeout."
  exit 1
fi
log "Waiting for app health (up to 90s)..."
DEADLINE=$(($(date +%s) + 90))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "${APP_CID}" 2>/dev/null)" = "healthy" ]; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    err "App did not become healthy within 90s."
    exit 1
  fi
  sleep 3
done

RESTORE_OK=1
rm -f "${LOCAL_DUMP}"
log "Restore complete. $(date -u)"
