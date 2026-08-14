#!/usr/bin/env bash
# Archives everything under /opt/skillars/data EXCEPT postgres/ (pg-backup.sh's pg_dump is the
# database's backup; a live tar of a running postgres data directory is not a valid restore
# source) to Hetzner Object Storage. Replaces volume-snapshot.sh, which called a Hetzner Cloud
# API endpoint that does not exist — see deferred-work.md, skillars-uat-3 D1. Cron runs this
# daily at 02:00 UTC via install-crons.sh, the same slot volume-snapshot.sh held.
set -euo pipefail

# shellcheck source=env-guard.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
require_env_vars "volume-backup" "backup" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT

DATA_DIR="/opt/skillars/data"
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
ARCHIVE_FILE="/tmp/skillars-volume-${TIMESTAMP}.tar.gz"
PREFIX="${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}"
PREFIX="${PREFIX%/}/"

# Always clean up the temp archive, whether the upload below succeeds or fails — otherwise a
# failed upload leaves it orphaned in /tmp forever.
cleanup() { rm -f "${ARCHIVE_FILE}"; }
trap cleanup EXIT

if [ ! -d "$DATA_DIR" ]; then
  echo "[volume-backup][error] ${DATA_DIR} does not exist" >&2
  exit 1
fi

echo "[volume-backup] Archiving ${DATA_DIR} (excluding postgres/, covered by pg-backup.sh)..."
# tar exits 1 for the harmless "file changed as we read it" warning, which is expected when
# archiving a live, actively-written data directory — only exit codes >= 2 are real failures.
tar --exclude='./postgres' -czf "${ARCHIVE_FILE}" -C "${DATA_DIR}" . && TAR_STATUS=0 || TAR_STATUS=$?
if [ "$TAR_STATUS" -ge 2 ]; then
  echo "[volume-backup][error] tar failed with exit code ${TAR_STATUS}" >&2
  exit "$TAR_STATUS"
elif [ "$TAR_STATUS" -eq 1 ]; then
  echo "[volume-backup][warn] tar reported changed files while archiving a live directory (exit 1) — continuing" >&2
fi

if [ ! -s "${ARCHIVE_FILE}" ]; then
  echo "[volume-backup][error] archive file is empty or missing — aborting upload" >&2
  exit 1
fi

echo "[volume-backup] Uploading to s3://${HOS_BUCKET}/${PREFIX}skillars-volume-${TIMESTAMP}.tar.gz"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "${ARCHIVE_FILE}" \
  "s3://${HOS_BUCKET}/${PREFIX}skillars-volume-${TIMESTAMP}.tar.gz" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

echo "[volume-backup] Done. $(date -u)"
