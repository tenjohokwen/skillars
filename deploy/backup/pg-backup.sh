#!/usr/bin/env bash
# pg_dump every running postgres container to Hetzner Object Storage.
# Cron runs this every 6 hours via install-crons.sh.
set -euo pipefail

# shellcheck source=/dev/null
. /opt/skillars/.env

umask 077
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
DUMP_FILE="/tmp/skillars-${TIMESTAMP}.sql.gz"
PREFIX="${HOS_BACKUP_PREFIX:-pg-backups/}"
PREFIX="${PREFIX%/}/"

CID=$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  echo "[pg-backup][error] postgres container not running" >&2
  exit 1
fi

echo "[pg-backup] Running pg_dump..."
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  pg_dump -U "${POSTGRES_USER:-postgres}" "${POSTGRES_DB:-skillars}" \
  | gzip > "${DUMP_FILE}"

if [ ! -s "${DUMP_FILE}" ]; then
  echo "[pg-backup][error] dump file is empty or missing — aborting upload" >&2
  exit 1
fi

echo "[pg-backup] Uploading to s3://${HOS_BUCKET}/${PREFIX}skillars-${TIMESTAMP}.sql.gz"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "${DUMP_FILE}" \
  "s3://${HOS_BUCKET}/${PREFIX}skillars-${TIMESTAMP}.sql.gz" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

rm -f "${DUMP_FILE}"
echo "[pg-backup] Done. $(date -u)"
