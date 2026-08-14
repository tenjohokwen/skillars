#!/usr/bin/env bash
# Enforces retention on both backup streams. Cron runs this daily at 03:30 UTC via install-crons.sh,
# clear of pg-backup.sh (0 */6) and volume-backup.sh (0 2).
#
# Two independent parts. A failure in one MUST NOT skip the other, and neither may silently no-op:
#   1. Postgres dumps in Hetzner Object Storage  — ${BACKUP_RETENTION_DAYS:-14} days
#   2. Volume backups in Hetzner Object Storage  — ${VOLUME_BACKUP_RETENTION_DAYS:-14} days
#
# The failure mode of a pruner that mis-parses a listing is an emptied backup bucket, so every
# parse is checked, an unparseable API response is fatal, and the newest ${BACKUP_RETENTION_MIN_KEEP:-8}
# dumps / ${VOLUME_BACKUP_RETENTION_MIN_KEEP:-4} volume backups are retained unconditionally
# regardless of age. Run with --dry-run first.
#
# Requires GNU date (-d) — the deploy target is Ubuntu; this will not run on macOS/BSD.
set -euo pipefail

# Arguments are parsed BEFORE anything can fail, and an unrecognised one is fatal. Silently
# ignoring it would turn a typo'd guard flag (--dry_run, -dry-run, --dryrun) into a live deletion
# run by an operator who believes they asked for a rehearsal.
DRY_RUN="${DRY_RUN:-0}"
case "${1:-}" in
  --dry-run) DRY_RUN=1 ;;
  "")        ;;
  *)
    echo "[prune-backups][error] unknown argument '${1}'. The only accepted flag is --dry-run" >&2
    echo "[prune-backups][error] (or DRY_RUN=1 in the environment). Refusing to run." >&2
    exit 2
    ;;
esac

# shellcheck source=env-guard.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
require_env_vars "prune-backups" "retention" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT

BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_RETENTION_MIN_KEEP="${BACKUP_RETENTION_MIN_KEEP:-8}"
VOLUME_BACKUP_RETENTION_DAYS="${VOLUME_BACKUP_RETENTION_DAYS:-14}"
VOLUME_BACKUP_RETENTION_MIN_KEEP="${VOLUME_BACKUP_RETENTION_MIN_KEEP:-4}"

# Validated before use: these are operator-set knobs that feed `date -d "$X days ago"` and an
# integer comparison. A typo must not reach either with unverified fallback behaviour — a garbage
# retention window is a deletion decision made on a value nobody checked.
require_positive_int() {
  case "$2" in
    ''|*[!0-9]*)
      echo "[prune-backups][error] ${1} must be a non-negative integer, got '${2}'" >&2
      return 1
      ;;
  esac
}
require_positive_int BACKUP_RETENTION_DAYS "$BACKUP_RETENTION_DAYS"
require_positive_int BACKUP_RETENTION_MIN_KEEP "$BACKUP_RETENTION_MIN_KEEP"
require_positive_int VOLUME_BACKUP_RETENTION_DAYS "$VOLUME_BACKUP_RETENTION_DAYS"
require_positive_int VOLUME_BACKUP_RETENTION_MIN_KEEP "$VOLUME_BACKUP_RETENTION_MIN_KEEP"

if [ "$DRY_RUN" = "1" ]; then
  echo "[prune-backups] DRY RUN — nothing will be deleted."
fi

# Tracked separately so part 2 still runs when part 1 fails, and the exit code still reflects both.
S3_STATUS=0
VOLUME_STATUS=0

# ─── Part 1: Postgres dumps in Object Storage ────────────────────────────────

prune_s3_dumps() {
  local prefix="${HOS_BACKUP_PREFIX:-pg-backups/}"
  prefix="${prefix%/}/"

  echo "[prune-backups] Listing s3://${HOS_BUCKET}/${prefix} (retain ${BACKUP_RETENTION_DAYS}d, keep >= ${BACKUP_RETENTION_MIN_KEEP})"

  local keys
  # list-objects-v2 paginates automatically in the AWS CLI, unlike a bare `aws s3 ls`.
  keys=$(AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
         AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
         aws s3api list-objects-v2 \
           --bucket "${HOS_BUCKET}" \
           --prefix "${prefix}" \
           --endpoint-url "${HOS_ENDPOINT}" \
           --query 'Contents[].Key' \
           --output text) \
    || { echo "[prune-backups][error] listing s3://${HOS_BUCKET}/${prefix} failed" >&2; return 1; }

  # An empty listing is reported, never treated as "nothing to prune" — a changed prefix and an
  # empty bucket look identical from here, and only one of them is benign.
  if [ -z "$keys" ] || [ "$keys" = "None" ]; then
    echo "[prune-backups][error] no objects found under ${prefix} — refusing to proceed. Either the" >&2
    echo "[prune-backups][error] prefix is wrong or no backup has ever run; both need a human." >&2
    return 1
  fi

  # The key stamp is %Y%m%dT%H%M%SZ (pg-backup.sh), which sorts lexically into chronological order.
  # Age comes from that stamp, deliberately NOT from the listing's printed date: the key is the
  # format this project controls, and an object re-uploaded or copied carries a misleading mtime.
  # --output text returns the keys tab-separated on one line; split explicitly rather than by
  # leaving the expansion unquoted, so a key containing a glob character cannot be expanded.
  local sorted
  sorted=$(printf '%s' "$keys" | tr '\t' '\n' | sed '/^$/d' | sort)

  local total
  total=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
  echo "[prune-backups] ${total} dump(s) found."

  if [ "$total" -le "$BACKUP_RETENTION_MIN_KEEP" ]; then
    echo "[prune-backups] ${total} <= minimum-keep ${BACKUP_RETENTION_MIN_KEEP} — nothing pruned."
    return 0
  fi

  # The newest BACKUP_RETENTION_MIN_KEEP are retained unconditionally. This is the rail that a clock
  # skew or a bad cutoff cannot get past: whatever the age computation decides, that many survive.
  local candidates
  candidates=$(printf '%s\n' "$sorted" | head -n "$((total - BACKUP_RETENTION_MIN_KEEP))")

  local cutoff
  cutoff=$(date -u -d "${BACKUP_RETENTION_DAYS} days ago" +%s) \
    || { echo "[prune-backups][error] could not compute the cutoff — GNU date required" >&2; return 1; }

  local deleted=0 skipped=0 key stamp epoch
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    # skillars-20260811T093000Z.sql.gz -> 20260811T093000Z
    stamp=$(printf '%s\n' "$key" | sed -n 's/.*skillars-\([0-9]\{8\}T[0-9]\{6\}Z\)\.sql\.gz$/\1/p')
    if [ -z "$stamp" ]; then
      # Never delete what we could not date. An unrecognised key is somebody else's object.
      echo "[prune-backups] skipping unrecognised key: ${key}"
      skipped=$((skipped + 1))
      continue
    fi
    epoch=$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}" +%s) \
      || { echo "[prune-backups][error] unparseable stamp in key ${key}" >&2; return 1; }
    if [ "$epoch" -ge "$cutoff" ]; then
      continue
    fi
    if [ "$DRY_RUN" = "1" ]; then
      echo "[prune-backups] would delete s3://${HOS_BUCKET}/${key}"
    else
      AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
      AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
        aws s3 rm "s3://${HOS_BUCKET}/${key}" --endpoint-url "${HOS_ENDPOINT}" --only-show-errors \
        || { echo "[prune-backups][error] failed to delete ${key}" >&2; return 1; }
      echo "[prune-backups] deleted ${key}"
    fi
    deleted=$((deleted + 1))
  done <<< "$candidates"

  echo "[prune-backups] dumps: ${deleted} pruned, ${skipped} unrecognised, $((total - deleted)) retained."
}

# ─── Part 2: Volume backups in Object Storage ────────────────────────────────

prune_volume_backups() {
  local prefix="${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}"
  prefix="${prefix%/}/"

  echo "[prune-backups] Listing s3://${HOS_BUCKET}/${prefix} (retain ${VOLUME_BACKUP_RETENTION_DAYS}d, keep >= ${VOLUME_BACKUP_RETENTION_MIN_KEEP})"

  local keys
  # list-objects-v2 paginates automatically in the AWS CLI, unlike a bare `aws s3 ls`.
  keys=$(AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
         AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
         aws s3api list-objects-v2 \
           --bucket "${HOS_BUCKET}" \
           --prefix "${prefix}" \
           --endpoint-url "${HOS_ENDPOINT}" \
           --query 'Contents[].Key' \
           --output text) \
    || { echo "[prune-backups][error] listing s3://${HOS_BUCKET}/${prefix} failed" >&2; return 1; }

  # An empty listing is reported, never treated as "nothing to prune" — a changed prefix and an
  # empty bucket look identical from here, and only one of them is benign.
  if [ -z "$keys" ] || [ "$keys" = "None" ]; then
    echo "[prune-backups][error] no objects found under ${prefix} — refusing to proceed. Either the" >&2
    echo "[prune-backups][error] prefix is wrong or no backup has ever run; both need a human." >&2
    return 1
  fi

  # The key stamp is %Y%m%dT%H%M%SZ (volume-backup.sh), which sorts lexically into chronological
  # order. Age comes from that stamp, deliberately NOT from the listing's printed date: the key is
  # the format this project controls, and an object re-uploaded or copied carries a misleading mtime.
  # --output text returns the keys tab-separated on one line; split explicitly rather than by
  # leaving the expansion unquoted, so a key containing a glob character cannot be expanded.
  local sorted
  sorted=$(printf '%s' "$keys" | tr '\t' '\n' | sed '/^$/d' | sort)

  local total
  total=$(printf '%s\n' "$sorted" | wc -l | tr -d ' ')
  echo "[prune-backups] ${total} volume backup(s) found."

  if [ "$total" -le "$VOLUME_BACKUP_RETENTION_MIN_KEEP" ]; then
    echo "[prune-backups] ${total} <= minimum-keep ${VOLUME_BACKUP_RETENTION_MIN_KEEP} — nothing pruned."
    return 0
  fi

  # The newest VOLUME_BACKUP_RETENTION_MIN_KEEP are retained unconditionally. This is the rail that
  # a clock skew or a bad cutoff cannot get past: whatever the age computation decides, that many
  # survive.
  local candidates
  candidates=$(printf '%s\n' "$sorted" | head -n "$((total - VOLUME_BACKUP_RETENTION_MIN_KEEP))")

  local cutoff
  cutoff=$(date -u -d "${VOLUME_BACKUP_RETENTION_DAYS} days ago" +%s) \
    || { echo "[prune-backups][error] could not compute the cutoff — GNU date required" >&2; return 1; }

  local deleted=0 skipped=0 key stamp epoch
  while IFS= read -r key; do
    [ -z "$key" ] && continue
    # skillars-volume-20260811T093000Z.tar.gz -> 20260811T093000Z
    stamp=$(printf '%s\n' "$key" | sed -n 's/.*skillars-volume-\([0-9]\{8\}T[0-9]\{6\}Z\)\.tar\.gz$/\1/p')
    if [ -z "$stamp" ]; then
      # Never delete what we could not date. An unrecognised key is somebody else's object.
      echo "[prune-backups] skipping unrecognised key: ${key}"
      skipped=$((skipped + 1))
      continue
    fi
    epoch=$(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}" +%s) \
      || { echo "[prune-backups][error] unparseable stamp in key ${key}" >&2; return 1; }
    if [ "$epoch" -ge "$cutoff" ]; then
      continue
    fi
    if [ "$DRY_RUN" = "1" ]; then
      echo "[prune-backups] would delete s3://${HOS_BUCKET}/${key}"
    else
      AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
      AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
        aws s3 rm "s3://${HOS_BUCKET}/${key}" --endpoint-url "${HOS_ENDPOINT}" --only-show-errors \
        || { echo "[prune-backups][error] failed to delete ${key}" >&2; return 1; }
      echo "[prune-backups] deleted ${key}"
    fi
    deleted=$((deleted + 1))
  done <<< "$candidates"

  echo "[prune-backups] volume backups: ${deleted} pruned, ${skipped} unrecognised, $((total - deleted)) retained."
}

prune_s3_dumps || S3_STATUS=$?
prune_volume_backups || VOLUME_STATUS=$?

if [ "$S3_STATUS" -ne 0 ] || [ "$VOLUME_STATUS" -ne 0 ]; then
  echo "[prune-backups][error] finished with failures (dumps=${S3_STATUS} volume=${VOLUME_STATUS})" >&2
  echo "[prune-backups] Done. $(date -u)"
  exit 1
fi

echo "[prune-backups] Done. $(date -u)"
