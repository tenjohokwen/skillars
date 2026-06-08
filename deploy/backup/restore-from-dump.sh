#!/usr/bin/env bash
# Restore PostgreSQL from a pg_dump archive stored in Hetzner Object Storage.
# Usage: ./restore-from-dump.sh <s3-object-key | latest>
#   Example: ./restore-from-dump.sh pg-backups/skillars-20260603T060000Z.sql.gz
#   Example: ./restore-from-dump.sh latest
set -euo pipefail

DUMP_KEY="${1:-}"
if [ -z "$DUMP_KEY" ]; then
  echo "Usage: $0 <s3-object-key | latest>" >&2
  exit 1
fi

# shellcheck source=/dev/null
. /opt/skillars/.env

PREFIX="${HOS_BACKUP_PREFIX:-pg-backups/}"
PREFIX="${PREFIX%/}/"

if [ "$DUMP_KEY" = "latest" ]; then
  echo "[restore-dump] Discovering latest dump in s3://${HOS_BUCKET}/${PREFIX}..."
  FILENAME=$(
    AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
    AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
    aws s3 ls "s3://${HOS_BUCKET}/${PREFIX}" \
      --endpoint-url "${HOS_ENDPOINT}" \
    | awk 'NF==4' | sort | tail -1 | awk '{print $4}'
  )
  if [ -z "$FILENAME" ]; then
    echo "[restore-dump][error] No dump files found in s3://${HOS_BUCKET}/${PREFIX}" >&2
    exit 1
  fi
  DUMP_KEY="${PREFIX}${FILENAME}"
  echo "[restore-dump] Latest dump: ${DUMP_KEY}"
fi

DUMP_SIZE_BYTES=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 ls "s3://${HOS_BUCKET}/${DUMP_KEY}" \
    --endpoint-url "${HOS_ENDPOINT}" \
  | awk '{print $3}'
)
if [ -n "${DUMP_SIZE_BYTES}" ]; then
  AVAIL_KB=$(df -k /tmp | awk 'NR==2 {print $4}')
  NEEDED_KB=$(( (DUMP_SIZE_BYTES + 1023) / 1024 ))
  if [ "${NEEDED_KB}" -gt "${AVAIL_KB}" ]; then
    echo "[restore-dump][error] Insufficient space in /tmp: need ${NEEDED_KB} KB, ${AVAIL_KB} KB available." >&2
    exit 1
  fi
fi

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
LOCAL_DUMP="/tmp/skillars-restore-${TIMESTAMP}.sql.gz"

echo "[restore-dump] Downloading s3://${HOS_BUCKET}/${DUMP_KEY}..."
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "s3://${HOS_BUCKET}/${DUMP_KEY}" "${LOCAL_DUMP}" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

if [ ! -s "${LOCAL_DUMP}" ]; then
  echo "[restore-dump][error] Downloaded file is empty or missing." >&2
  exit 1
fi

echo "[restore-dump] Validating dump integrity..."
if ! gunzip -t "${LOCAL_DUMP}" 2>/dev/null; then
  echo "[restore-dump][error] Dump file failed gzip integrity check — aborting before database drop." >&2
  exit 1
fi

echo "[restore-dump] Stopping app service..."
docker compose -f /opt/skillars/docker-compose.yml stop app

CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  echo "[restore-dump][error] postgres container not running." >&2
  exit 1
fi

echo "[restore-dump] Dropping and recreating database ${POSTGRES_DB:-skillars}..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "DROP DATABASE IF EXISTS \"${POSTGRES_DB:-skillars}\";"
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d postgres \
  -c "CREATE DATABASE \"${POSTGRES_DB:-skillars}\" OWNER \"${POSTGRES_USER:-postgres}\";"

echo "[restore-dump] Restoring dump (this may take several minutes)..."
gunzip -c "${LOCAL_DUMP}" | \
  docker exec -i -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  --set ON_ERROR_STOP=1

TABLE_COUNT=$(docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  -t -c "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE schemaname='public';" \
  2>/dev/null | tr -d ' \n')

if [ -z "${TABLE_COUNT}" ] || [ "${TABLE_COUNT}" -lt 1 ]; then
  echo "[restore-dump][error] Integrity check failed: no public tables found." >&2
  exit 1
fi
echo "[restore-dump] Integrity check: ${TABLE_COUNT} public tables found."

echo "[restore-dump] Starting app service..."
docker compose -f /opt/skillars/docker-compose.yml start app

APP_CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q app 2>/dev/null | head -1)
echo "[restore-dump] Waiting for app health (up to 90s)..."
DEADLINE=$(($(date +%s) + 90))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "${APP_CID}" 2>/dev/null)" = "healthy" ]; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    echo "[restore-dump][error] App did not become healthy within 90s." >&2
    exit 1
  fi
  sleep 3
done

rm -f "${LOCAL_DUMP}"
echo "[restore-dump] Restore complete. $(date -u)"
