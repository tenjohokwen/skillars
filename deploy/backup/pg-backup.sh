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

# Read the object's ContentLength, retrying while it is still not visible (empty / None). If Hetzner
# Object Storage does not guarantee strong read-after-write consistency for a brand-new PUT (AWS S3
# now does; Hetzner's guarantee is undocumented), the first head-object can miss a good upload.
# 5 attempts, 3s apart => 4 sleeps => ~12s max wall time. A retrieved-but-WRONG size is a genuine
# truncation/corruption signal and is returned immediately — the caller fails fast, no retry.
# `local` is declared on its own line, then assigned separately: `local x=$(...)` masks the command
# substitution's exit status (shellcheck SC2155) and makes the `|| out=""` guard dead.
head_object_content_length() {  # $1 bucket, $2 key — echoes ContentLength, or empty on exhaustion
  local i out
  for i in 1 2 3 4 5; do
    out=$(AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
      aws s3api head-object --bucket "$1" --key "$2" --endpoint-url "${HOS_ENDPOINT}" \
      --query 'ContentLength' --output text) || out=""
    case "${out}" in
      ""|None) ;;                          # not visible yet — keep retrying
      *) printf '%s' "${out}"; return 0 ;; # got a value (right or wrong) — caller decides
    esac
    [ "${i}" -lt 5 ] && sleep 3
  done
  return 0   # still empty after ~12s — the caller's -z / None check + exit 1 handles it
}

# Verify the object actually landed intact before deleting the local copy. A silently-truncated
# or failed-but-exit-0 upload is otherwise undetectable until a restore fails.
# The ContentLength read goes through head_object_content_length()'s bounded ~12s retry so a brief
# read-after-write visibility lag on a good upload does not trip the failure path below. The capture
# is still `|| true`-guarded: under `set -euo pipefail` a head-object transport failure (or the
# helper's own `return 0` on exhaustion) must not abort before the diagnostic can run.
LOCAL_SIZE=$(stat -c %s "${DUMP_FILE}")
REMOTE_SIZE=$(head_object_content_length "${HOS_BUCKET}" "${OBJECT_KEY}") || true
if [ -z "${REMOTE_SIZE}" ] || [ "${REMOTE_SIZE}" = "None" ] || [ "${REMOTE_SIZE}" != "${LOCAL_SIZE}" ]; then
  echo "[pg-backup][error] upload verification failed: local ${LOCAL_SIZE} bytes, remote '${REMOTE_SIZE:-<none>}' — dump NOT confirmed in Object Storage (ContentLength read retried up to ~12s)" >&2
  exit 1
fi
echo "[pg-backup] Upload verified: ${LOCAL_SIZE} bytes (ContentLength match)."

# Best-effort ETag/MD5 check, secondary and ADVISORY ONLY. `aws s3 cp` (awscli v1) switches to
# multipart at multipart_threshold = 8 MB; a gzipped pg_dump of this app with real data exceeds
# that within months, so in production the ETag normally carries a `-N` suffix and is a
# hash-of-part-hashes, NOT a whole-object MD5 — the comparison is skipped in that case. It only
# runs for a single-part (no `-`) ETag, and even then a mismatch is a WARNING, not a failure: a
# single-part ETag equals the raw object MD5 only for an unencrypted bucket with a plain ETag
# scheme, so SSE-KMS/SSE-C or a provider-specific ETag scheme produces a legitimate mismatch on an
# intact upload. The `ContentLength == local size` check above is the SOLE hard gate on upload
# integrity. (skillars-deferred-85 AC4 made a single-part mismatch `exit 1`; skillars-deferred-87
# AC1 deliberately reverses that.)
# No retry loop here — the ContentLength read above already confirmed the object is visible.
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
      echo "[pg-backup][warn] single-part ETag ${REMOTE_ETAG} != local MD5 ${LOCAL_MD5}. Upload already confirmed by the ContentLength match above (the authoritative leg); a single-part ETag equals the raw object MD5 only for an unencrypted bucket with a plain ETag scheme, so SSE-KMS/SSE-C or a provider-specific ETag is the expected cause here. Continuing." >&2
    else
      echo "[pg-backup] Upload verified: single-part ETag matches local MD5."
    fi ;;
esac

echo "[pg-backup] Done. $(date -u)"
