#!/usr/bin/env bash
# Archives everything under /opt/skillars/data EXCEPT postgres/ (pg-backup.sh's pg_dump is the
# database's backup; a live tar of a running postgres data directory is not a valid restore
# source) to Hetzner Object Storage. Replaces volume-snapshot.sh, which called a Hetzner Cloud
# API endpoint that does not exist — see deferred-work.md, skillars-uat-3 D1. Cron runs this
# daily at 02:00 UTC via install-crons.sh, the same slot volume-snapshot.sh held.
set -euo pipefail

GUARD_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
if [ -d "$GUARD_PATH" ]; then
  echo "[volume-backup][error] ${GUARD_PATH} is a directory, not a file — cannot load credential guard" >&2
  exit 1
fi
if [ ! -r "$GUARD_PATH" ]; then
  echo "[volume-backup][error] cannot read ${GUARD_PATH} — required for credential loading" >&2
  exit 1
fi
# shellcheck source=env-guard.sh
. "$GUARD_PATH"
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

OBJECT_KEY="${PREFIX}skillars-volume-${TIMESTAMP}.tar.gz"

echo "[volume-backup] Uploading to s3://${HOS_BUCKET}/${OBJECT_KEY}"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "${ARCHIVE_FILE}" \
  "s3://${HOS_BUCKET}/${OBJECT_KEY}" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

# Verify the object landed intact. Same unverified-upload gap as pg-backup.sh — a truncated or
# failed-but-exit-0 upload is otherwise undetectable until a restore fails. Captures wrapped
# `|| true` so a head-object failure yields the diagnostic, not a bare `set -euo pipefail` abort.
LOCAL_SIZE=$(stat -c %s "${ARCHIVE_FILE}")
REMOTE_SIZE=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3api head-object --bucket "${HOS_BUCKET}" --key "${OBJECT_KEY}" \
    --endpoint-url "${HOS_ENDPOINT}" --query 'ContentLength' --output text
) || true
if [ -z "${REMOTE_SIZE}" ] || [ "${REMOTE_SIZE}" = "None" ] || [ "${REMOTE_SIZE}" != "${LOCAL_SIZE}" ]; then
  echo "[volume-backup][error] upload verification failed: local ${LOCAL_SIZE} bytes, remote '${REMOTE_SIZE:-<none>}' — archive NOT confirmed in Object Storage" >&2
  exit 1
fi
echo "[volume-backup] Upload verified: ${LOCAL_SIZE} bytes (ContentLength match)."

# Best-effort ETag/MD5 check, secondary — only meaningful for a single-part (no `-`) ETag.
# A volume tar of this app's data dir will normally exceed awscli v1's 8 MB multipart_threshold,
# so the multipart branch (size-only) is the expected path in production.
REMOTE_ETAG=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3api head-object --bucket "${HOS_BUCKET}" --key "${OBJECT_KEY}" \
    --endpoint-url "${HOS_ENDPOINT}" --query 'ETag' --output text
) || true
REMOTE_ETAG=${REMOTE_ETAG//\"/}
case "${REMOTE_ETAG}" in
  ""|None)
    echo "[volume-backup] Upload ETag unavailable — size-only verification." ;;
  *-*)
    echo "[volume-backup] Upload ETag is multipart — size-only verification (expected for archives > 8 MB)." ;;
  *)
    LOCAL_MD5=$(md5sum "${ARCHIVE_FILE}" | cut -d' ' -f1)
    if [ "${REMOTE_ETAG}" != "${LOCAL_MD5}" ]; then
      echo "[volume-backup][error] upload verification failed: single-part ETag ${REMOTE_ETAG} != local MD5 ${LOCAL_MD5}" >&2
      exit 1
    fi
    echo "[volume-backup] Upload verified: single-part ETag matches local MD5." ;;
esac

echo "[volume-backup] Done. $(date -u)"
