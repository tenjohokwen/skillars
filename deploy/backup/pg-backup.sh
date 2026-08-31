#!/usr/bin/env bash
# pg_dump every running postgres container to Hetzner Object Storage.
# Cron runs this every 6 hours via install-crons.sh.
set -euo pipefail

GUARD_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
if [ -d "$GUARD_PATH" ]; then
  echo "[pg-backup][error] ${GUARD_PATH} is a directory, not a file — cannot load credential guard" >&2
  exit 1
fi
if [ ! -r "$GUARD_PATH" ]; then
  echo "[pg-backup][error] cannot read ${GUARD_PATH} — required for credential loading" >&2
  exit 1
fi
# shellcheck source=env-guard.sh
. "$GUARD_PATH"
require_env_vars "pg-backup" "backup" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT POSTGRES_PASSWORD

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

# Always remove the local dump on exit — every `exit 1` in the verification block below returns
# before the explicit `rm`, and a repeated cron failure would otherwise fill the node's disk with
# multi-GB dumps until pg_dump itself starts failing. Mirrors volume-backup.sh's `trap cleanup EXIT`.
trap 'rm -f "${DUMP_FILE}"' EXIT

if [ ! -s "${DUMP_FILE}" ]; then
  echo "[pg-backup][error] dump file is empty or missing — aborting upload" >&2
  exit 1
fi

OBJECT_KEY="${PREFIX}skillars-${TIMESTAMP}.sql.gz"

echo "[pg-backup] Uploading to s3://${HOS_BUCKET}/${OBJECT_KEY}"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "${DUMP_FILE}" \
  "s3://${HOS_BUCKET}/${OBJECT_KEY}" \
  --endpoint-url "${HOS_ENDPOINT}" \
  --no-progress

# Verify the object actually landed intact before deleting the local copy. A silently-truncated
# or failed-but-exit-0 upload is otherwise undetectable until a restore fails.
# Captures are wrapped `|| true`: under `set -euo pipefail` a head-object failure (object absent =>
# upload silently failed) would otherwise abort before the diagnostic below could run.
LOCAL_SIZE=$(stat -c %s "${DUMP_FILE}")
REMOTE_SIZE=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3api head-object --bucket "${HOS_BUCKET}" --key "${OBJECT_KEY}" \
    --endpoint-url "${HOS_ENDPOINT}" --query 'ContentLength' --output text
) || true
if [ -z "${REMOTE_SIZE}" ] || [ "${REMOTE_SIZE}" = "None" ] || [ "${REMOTE_SIZE}" != "${LOCAL_SIZE}" ]; then
  echo "[pg-backup][error] upload verification failed: local ${LOCAL_SIZE} bytes, remote '${REMOTE_SIZE:-<none>}' — dump NOT confirmed in Object Storage" >&2
  exit 1
fi
echo "[pg-backup] Upload verified: ${LOCAL_SIZE} bytes (ContentLength match)."

# Best-effort ETag/MD5 check, secondary. `aws s3 cp` (awscli v1) switches to multipart at
# multipart_threshold = 8 MB; a gzipped pg_dump of this app with real data exceeds that within
# months, so in production the ETag normally carries a `-N` suffix and is a hash-of-part-hashes,
# NOT a whole-object MD5 — the comparison is skipped in that case. It only runs for a single-part
# (no `-`) ETag. The size check above is the reliable leg and never blocks on this being skipped.
REMOTE_ETAG=$(
  AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3api head-object --bucket "${HOS_BUCKET}" --key "${OBJECT_KEY}" \
    --endpoint-url "${HOS_ENDPOINT}" --query 'ETag' --output text
) || true
REMOTE_ETAG=${REMOTE_ETAG//\"/}
case "${REMOTE_ETAG}" in
  ""|None)
    echo "[pg-backup] Upload ETag unavailable — size-only verification." ;;
  *-*)
    echo "[pg-backup] Upload ETag is multipart — size-only verification (expected for dumps > 8 MB)." ;;
  *)
    LOCAL_MD5=$(md5sum "${DUMP_FILE}" | cut -d' ' -f1)
    if [ "${REMOTE_ETAG}" != "${LOCAL_MD5}" ]; then
      echo "[pg-backup][error] upload verification failed: single-part ETag ${REMOTE_ETAG} != local MD5 ${LOCAL_MD5}" >&2
      exit 1
    fi
    echo "[pg-backup] Upload verified: single-part ETag matches local MD5." ;;
esac

echo "[pg-backup] Done. $(date -u)"
