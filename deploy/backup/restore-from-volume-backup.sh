#!/usr/bin/env bash
# Restores /opt/skillars/data (excluding postgres/, which is not archived by volume-backup.sh —
# restore the database separately via restore-from-dump.sh) from the latest, or an explicitly
# named, file-level volume backup in Object Storage. Replaces restore-from-snapshot.sh, which
# restored from Hetzner Cloud volume snapshots that were never actually created — see
# deferred-work.md, skillars-uat-3 D1.
set -euo pipefail

log() { echo "[restore-from-volume-backup] $*"; }
err() { echo "[restore-from-volume-backup][error] $*" >&2; }

GUARD_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
if [ -d "$GUARD_PATH" ]; then
  echo "[restore-from-volume-backup][error] ${GUARD_PATH} is a directory, not a file — cannot load credential guard" >&2
  exit 1
fi
if [ ! -r "$GUARD_PATH" ]; then
  echo "[restore-from-volume-backup][error] cannot read ${GUARD_PATH} — required for credential loading" >&2
  exit 1
fi
# shellcheck source=env-guard.sh
. "$GUARD_PATH"
require_env_vars "restore-from-volume-backup" "restore" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT

COMPOSE_FILE="/opt/skillars/docker-compose.yml"
DATA_DIR="/opt/skillars/data"
PREFIX="${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}"
PREFIX="${PREFIX%/}/"
KEY="${1:-}"   # optional: exact object key to restore; default = most recently modified
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)

# The non-postgres top-level directories volume-backup.sh archives — same set the ownership
# restoration below applies to. postgres/ is never touched by this script.
VOLUME_SUBDIRS="redis prometheus loki tempo grafana traefik"

echo "This will OVERWRITE non-postgres data under ${DATA_DIR}. Press ENTER to continue, Ctrl+C to abort."
read -r _

if [ -z "$KEY" ]; then
  # Selected by the embedded skillars-volume-<stamp>.tar.gz filename timestamp (lexical sort ==
  # chronological order for this fixed-width format), NOT by S3 LastModified — consistent with
  # prune_volume_backups() in prune-backups.sh, which deliberately distrusts object mtime.
  keys=$(AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
    aws s3api list-objects-v2 --bucket "${HOS_BUCKET}" --prefix "${PREFIX}" \
    --endpoint-url "${HOS_ENDPOINT}" --query 'Contents[].Key' --output text)
  KEY=$(printf '%s' "$keys" | tr '\t' '\n' | sed '/^$/d' | sort | tail -n1)
fi
if [ -z "$KEY" ] || [ "$KEY" = "None" ]; then
  err "no volume backup found under ${PREFIX}"
  exit 1
fi

log "Restoring ${KEY}..."
docker compose -f "${COMPOSE_FILE}" down

# From here on, any failure must not leave services stopped indefinitely — restart with
# whatever data is currently on disk (pre-restore, or partially restored) rather than leaving an
# incident silent.
restore_failed() {
  err "restore step failed — restarting services with the data currently on disk so the app does not stay down"
  docker compose -f "${COMPOSE_FILE}" up -d
}
trap restore_failed ERR

ARCHIVE_FILE="/tmp/$(basename "$KEY")"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "s3://${HOS_BUCKET}/${KEY}" "${ARCHIVE_FILE}" --endpoint-url "${HOS_ENDPOINT}"

# Pre-restore safety net: move the directories this restore is about to overwrite aside instead
# of extracting straight over them, so a bad archive (wrong key, corrupt file) can be manually
# rolled back. postgres/ is never moved — it is not in the archive and must stay untouched.
PRERESTORE_DIR="${DATA_DIR}.pre-restore-${TIMESTAMP}"
mkdir -p "${PRERESTORE_DIR}"
for d in $VOLUME_SUBDIRS; do
  if [ -d "${DATA_DIR}/${d}" ]; then
    mv "${DATA_DIR}/${d}" "${PRERESTORE_DIR}/${d}"
  fi
done
log "Pre-restore data moved aside to ${PRERESTORE_DIR} — delete manually once the restore is confirmed good."

tar -xzf "${ARCHIVE_FILE}" -C "${DATA_DIR}"
rm -f "${ARCHIVE_FILE}"

# Ownership restoration — same values provision.sh sections 7/7.5 set on first provisioning.
# restore-from-snapshot.sh (the script this replaces) omitted redis/traefik; do not repeat that.
# Only directories that actually extracted from this archive are fixed up — an archive taken
# before a service was added to VOLUME_SUBDIRS, or a service that was never provisioned, is a
# legitimate absence, not a failure.
for d in $VOLUME_SUBDIRS; do
  if [ ! -d "${DATA_DIR}/${d}" ]; then
    log "skipping ownership fix for ${d} — not present in this archive"
    continue
  fi
  case "$d" in
    redis)      chown -R 999:1000 "${DATA_DIR}/${d}" ;;
    prometheus) chown -R 65534:65534 "${DATA_DIR}/${d}" ;;
    loki)       chown -R 10001:10001 "${DATA_DIR}/${d}" ;;
    tempo)      chown -R 10001:10001 "${DATA_DIR}/${d}" ;;
    grafana)    chown -R 472:472 "${DATA_DIR}/${d}" ;;
    traefik)
      chmod 700 "${DATA_DIR}/${d}"
      if [ -f "${DATA_DIR}/${d}/acme.json" ]; then
        chmod 600 "${DATA_DIR}/${d}/acme.json"
      else
        log "skipping ownership fix for ${d}/acme.json — not present in this archive"
      fi
      ;;
    *)
      log "no ownership fix defined for ${d} — skipping (VOLUME_SUBDIRS added a subdir with no matching case arm)"
      ;;
  esac
done

docker compose -f "${COMPOSE_FILE}" up -d
trap - ERR

APP_CID=$(docker compose -f "${COMPOSE_FILE}" ps -q app 2>/dev/null | head -1)
log "Waiting for app health (up to 120s)..."
DEADLINE=$(($(date +%s) + 120))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "${APP_CID}" 2>/dev/null)" = "healthy" ]; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    err "App did not become healthy within 120s."
    err "Check logs: docker compose -f ${COMPOSE_FILE} logs app --tail=50"
    exit 1
  fi
  sleep 5
done

log "Restore complete. App is healthy. Postgres was NOT restored by this script — use restore-from-dump.sh separately if needed. $(date -u)"
