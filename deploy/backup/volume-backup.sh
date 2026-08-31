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

# Verify the object landed intact. Same unverified-upload gap as pg-backup.sh — a truncated or
# failed-but-exit-0 upload is otherwise undetectable until a restore fails. The ContentLength read
# goes through head_object_content_length()'s bounded ~12s retry so a brief read-after-write
# visibility lag on a good upload does not trip the failure path. The capture is still `|| true`-
# guarded so a head-object failure (or the helper's `return 0` on exhaustion) yields the diagnostic,
# not a bare `set -euo pipefail` abort.
LOCAL_SIZE=$(stat -c %s "${ARCHIVE_FILE}")
REMOTE_SIZE=$(head_object_content_length "${HOS_BUCKET}" "${OBJECT_KEY}") || true
if [ -z "${REMOTE_SIZE}" ] || [ "${REMOTE_SIZE}" = "None" ] || [ "${REMOTE_SIZE}" != "${LOCAL_SIZE}" ]; then
  echo "[volume-backup][error] upload verification failed: local ${LOCAL_SIZE} bytes, remote '${REMOTE_SIZE:-<none>}' — archive NOT confirmed in Object Storage (ContentLength read retried up to ~12s)" >&2
  exit 1
fi
echo "[volume-backup] Upload verified: ${LOCAL_SIZE} bytes (ContentLength match)."

# Best-effort ETag/MD5 check, secondary and ADVISORY ONLY — only meaningful for a single-part
# (no `-`) ETag. A volume tar of this app's data dir will normally exceed awscli v1's 8 MB
# multipart_threshold, so the multipart branch (size-only) is the expected path in production.
# Even for a single-part ETag a mismatch is a WARNING, not a failure: the ETag equals the raw
# object MD5 only for an unencrypted bucket with a plain ETag scheme, so SSE-KMS/SSE-C or a
# provider-specific scheme produces a legitimate mismatch on an intact archive. The
# `ContentLength == local size` check above is the SOLE hard gate on upload integrity.
# (skillars-deferred-85 AC4 made a single-part mismatch `exit 1`; skillars-deferred-87 AC1
# deliberately reverses that.)
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
    echo "[volume-backup] Upload ETag unavailable — size-only verification." ;;
  *-*)
    echo "[volume-backup] Upload ETag is multipart — size-only verification (expected for archives > 8 MB)." ;;
  *)
    LOCAL_MD5=$(md5sum "${ARCHIVE_FILE}" | cut -d' ' -f1)
    if [ "${REMOTE_ETAG}" != "${LOCAL_MD5}" ]; then
      echo "[volume-backup][warn] single-part ETag ${REMOTE_ETAG} != local MD5 ${LOCAL_MD5}. Upload already confirmed by the ContentLength match above (the authoritative leg); a single-part ETag equals the raw object MD5 only for an unencrypted bucket with a plain ETag scheme, so SSE-KMS/SSE-C or a provider-specific ETag is the expected cause here. Continuing." >&2
    else
      echo "[volume-backup] Upload verified: single-part ETag matches local MD5."
    fi ;;
esac

echo "[volume-backup] Done. $(date -u)"
