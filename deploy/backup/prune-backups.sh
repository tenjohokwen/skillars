#!/usr/bin/env bash
# Enforces retention on both backup streams. Cron runs this daily at 03:30 UTC via install-crons.sh,
# clear of pg-backup.sh (0 */6) and volume-snapshot.sh (0 2).
#
# Two independent parts. A failure in one MUST NOT skip the other, and neither may silently no-op:
#   1. Postgres dumps in Hetzner Object Storage  — ${BACKUP_RETENTION_DAYS:-14} days
#   2. Hetzner Cloud snapshots                   — ${SNAPSHOT_RETENTION_DAYS:-7} days
#
# The failure mode of a pruner that mis-parses a listing is an emptied backup bucket, so every
# parse is checked, an unparseable API response is fatal, and the newest ${BACKUP_RETENTION_MIN_KEEP:-8}
# dumps are retained unconditionally regardless of age. Run with --dry-run first.
#
# Requires GNU date (-d) — the deploy target is Ubuntu; this will not run on macOS/BSD.
set -euo pipefail

ENV_FILE="/opt/skillars/.env"

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

# Checked explicitly: sourcing a missing or unreadable .env under `set -e` aborts with a bare shell
# message and no [prune-backups][error] prefix, which is invisible in the shared backup log.
if [ ! -r "$ENV_FILE" ]; then
  echo "[prune-backups][error] cannot read ${ENV_FILE} — retention cannot run without credentials" >&2
  exit 1
fi
# shellcheck source=/dev/null
. "$ENV_FILE"

BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_RETENTION_MIN_KEEP="${BACKUP_RETENTION_MIN_KEEP:-8}"
SNAPSHOT_RETENTION_DAYS="${SNAPSHOT_RETENTION_DAYS:-7}"

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
require_positive_int SNAPSHOT_RETENTION_DAYS "$SNAPSHOT_RETENTION_DAYS"

if [ "$DRY_RUN" = "1" ]; then
  echo "[prune-backups] DRY RUN — nothing will be deleted."
fi

# Tracked separately so part 2 still runs when part 1 fails, and the exit code still reflects both.
S3_STATUS=0
SNAP_STATUS=0

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

# ─── Part 2: Hetzner Cloud snapshots ─────────────────────────────────────────
#
# NOTE, verified against the Hetzner Cloud API on 2026-08-11: snapshots are Images, listed with
# GET /v1/images?type=snapshot and removed with DELETE /v1/images/{id}. There is NO volume snapshot
# in the Hetzner Cloud API — the only create_image action is on servers, and Hetzner server
# snapshots explicitly exclude attached volumes. volume-snapshot.sh therefore POSTs to an endpoint
# that does not exist and has never produced a snapshot; see its header and docs/deployment/runbook.md.
# This half is written against the real API so it is correct for any snapshot that does exist, and
# it reports a zero match loudly rather than exiting quietly, because zero is exactly what a broken
# creation script looks like.

prune_snapshots() {
  command -v jq >/dev/null 2>&1 \
    || { echo "[prune-backups][error] jq is required to parse the Hetzner API response" >&2; return 1; }

  echo "[prune-backups] Listing Hetzner snapshots (retain ${SNAPSHOT_RETENTION_DAYS}d)"

  local response http_code body
  response=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
    "https://api.hetzner.cloud/v1/images?type=snapshot&per_page=50") \
    || { echo "[prune-backups][error] curl failed (exit $?) — network or DNS issue" >&2; return 1; }

  http_code=$(echo "$response" | tail -n1)
  body=$(echo "$response" | head -n-1)

  # The status is checked for being a number FIRST, and separately. Writing this as
  # `[ "$http_code" -ne 200 ] 2>/dev/null` — the idiom volume-snapshot.sh uses — inverts the failure:
  # a non-numeric value (a curl transport error leaking into the last line, an empty response) makes
  # the arithmetic test error out, the suppressed error reads as "condition false", and the script
  # proceeds as though it had received a 200.
  case "$http_code" in
    ''|*[!0-9]*)
      echo "[prune-backups][error] images API returned no usable HTTP status ('${http_code}'), which" >&2
      echo "[prune-backups][error] means the request failed in transport: ${body}" >&2
      return 1
      ;;
  esac
  if [ "$http_code" -ne 200 ]; then
    echo "[prune-backups][error] images API returned HTTP ${http_code}: ${body}" >&2
    return 1
  fi

  # A response we cannot parse is an error, never "nothing to prune".
  if ! echo "$body" | jq -e 'has("images")' >/dev/null 2>&1; then
    echo "[prune-backups][error] unexpected images response shape: ${body}" >&2
    return 1
  fi

  local cutoff
  cutoff=$(date -u -d "${SNAPSHOT_RETENTION_DAYS} days ago" +%s)

  # Matched on BOTH the daily-YYYY-MM-DD description volume-snapshot.sh writes and a non-null
  # created timestamp. Never on description alone across an unrelated account resource.
  local matches
  matches=$(echo "$body" | jq -r '
    .images[]
    | select(.description != null)
    | select(.description | test("^daily-[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
    | select(.created != null)
    | "\(.id)\t\(.description)\t\(.created)"')

  if [ -z "$matches" ]; then
    echo "[prune-backups][warn] no snapshot matched 'daily-YYYY-MM-DD'. If volume-snapshot.sh is" >&2
    echo "[prune-backups][warn] installed in cron, this means it is NOT producing snapshots — see" >&2
    echo "[prune-backups][warn] the note in that script and docs/deployment/runbook.md." >&2
    return 0
  fi

  local deleted=0 id description created epoch del_code
  while IFS=$'\t' read -r id description created; do
    [ -z "$id" ] && continue
    epoch=$(date -u -d "$created" +%s) \
      || { echo "[prune-backups][error] unparseable created timestamp '${created}' on image ${id}" >&2; return 1; }
    if [ "$epoch" -ge "$cutoff" ]; then
      continue
    fi
    if [ "$DRY_RUN" = "1" ]; then
      echo "[prune-backups] would delete snapshot ${id} (${description})"
    else
      del_code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
        -H "Authorization: Bearer ${HCLOUD_TOKEN}" \
        "https://api.hetzner.cloud/v1/images/${id}") \
        || { echo "[prune-backups][error] curl failed deleting image ${id}" >&2; return 1; }
      # Same explicit numeric check as the list call above, for the same reason.
      case "$del_code" in
        ''|*[!0-9]*)
          echo "[prune-backups][error] deleting image ${id} returned no usable HTTP status ('${del_code}')" >&2
          return 1
          ;;
      esac
      if [ "$del_code" -ne 204 ]; then
        echo "[prune-backups][error] deleting image ${id} returned HTTP ${del_code}" >&2
        return 1
      fi
      echo "[prune-backups] deleted snapshot ${id} (${description})"
    fi
    deleted=$((deleted + 1))
  done <<< "$matches"

  echo "[prune-backups] snapshots: ${deleted} pruned."
}

prune_s3_dumps || S3_STATUS=$?
prune_snapshots || SNAP_STATUS=$?

if [ "$S3_STATUS" -ne 0 ] || [ "$SNAP_STATUS" -ne 0 ]; then
  echo "[prune-backups][error] finished with failures (dumps=${S3_STATUS} snapshots=${SNAP_STATUS})" >&2
  echo "[prune-backups] Done. $(date -u)"
  exit 1
fi

echo "[prune-backups] Done. $(date -u)"
