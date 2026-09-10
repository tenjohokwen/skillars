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

# skillars-deferred-102 AC6: checkout moved to /opt/skillars/app; .env stays at /opt/skillars/.env.
COMPOSE_FILE="/opt/skillars/app/docker-compose.yml"
DC="docker compose --env-file /opt/skillars/.env -f ${COMPOSE_FILE}"
DATA_DIR="/opt/skillars/data"

# skillars-deferred-107 AC8 (+ code review): echo the numeric uid the given compose service's image
# declares (its Config.User), or "" on any failure — same probe as provision.sh's image_runtime_uid.
# `docker inspect .Config.User`, not `docker run … id -u`: images that drop privileges in their
# entrypoint (redis: root → gosu 999) report the pre-drop uid from `id -u`. Names are resolved
# inside the image; "" / 0 / unresolvable all yield "".
image_runtime_uid() {
  local svc="$1" img user uid
  img=$(${DC} config --format json 2>/dev/null \
        | jq -r --arg s "$svc" '.services[$s].image // empty' 2>/dev/null) || return 0
  [ -n "${img:-}" ] || return 0
  docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img" >/dev/null 2>&1 || return 0
  user=$(docker image inspect --format '{{.Config.User}}' "$img" 2>/dev/null) || return 0
  user="${user%%:*}"
  [ -n "$user" ] || return 0
  case "$user" in
    ''|*[!0-9]*) uid=$(docker run --rm --entrypoint sh "$img" -c "id -u '$user'" 2>/dev/null) || return 0 ;;
    *) uid="$user" ;;
  esac
  case "${uid:-}" in ''|*[!0-9]*|0) return 0 ;; esac
  printf '%s' "$uid"
}

# chown -R $dir to the probed image uid, keeping the hardcoded $3's GID pinned (the data dir group is
# deliberate and independent of the image's declared group — redis wants gid 1000 while its process
# is gid 999; grafana's image declares gid 0). Falls back to $3 silently when the probe can't
# determine a non-root uid (normal for redis / root-group images); WARNs only on a real drift — a
# probed non-zero uid that differs from the constant. skillars-deferred-107 AC8 + code review.
chown_probed() {
  local svc="$1" dir="$2" fallback="$3"
  local fb_uid="${fallback%%:*}" fb_gid="${fallback##*:}" probed_uid
  probed_uid="$(image_runtime_uid "$svc")"
  if [ -z "$probed_uid" ]; then
    chown -R "$fallback" "$dir"
  elif [ "$probed_uid" != "$fb_uid" ]; then
    log "⚠️  ${svc}: image now runs as uid ${probed_uid}, hardcoded constant is ${fb_uid} — using ${probed_uid}:${fb_gid} for ${dir}; update the constant in provision.sh and restore-from-volume-backup.sh"
    chown -R "${probed_uid}:${fb_gid}" "$dir"
  else
    chown -R "$fallback" "$dir"
  fi
}
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
${DC} down

# From here on, any failure must not leave services stopped indefinitely — restart with
# whatever data is currently on disk (pre-restore, or partially restored) rather than leaving an
# incident silent.
restore_failed() {
  err "restore step failed — restarting services with the data currently on disk so the app does not stay down"
  ${DC} up -d
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
# skillars-deferred-107 AC8: the numeric uid:gid below are FALLBACKS — chown_probed reads the
# runtime uid from each service's image first (image_runtime_uid) and only uses these if the probe
# can't determine a non-root uid. The GID is always the pinned constant.
for d in $VOLUME_SUBDIRS; do
  if [ ! -d "${DATA_DIR}/${d}" ]; then
    log "skipping ownership fix for ${d} — not present in this archive"
    continue
  fi
  case "$d" in
    redis)      chown_probed redis      "${DATA_DIR}/${d}" 999:1000 ;;
    prometheus) chown_probed prometheus "${DATA_DIR}/${d}" 65534:65534 ;;
    loki)       chown_probed loki       "${DATA_DIR}/${d}" 10001:10001 ;;
    tempo)      chown_probed tempo      "${DATA_DIR}/${d}" 10001:10001 ;;
    grafana)    chown_probed grafana    "${DATA_DIR}/${d}" 472:472 ;;
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

${DC} up -d
trap - ERR

APP_CID=$(${DC} ps -q app 2>/dev/null | head -1)
log "Waiting for app health (up to 120s)..."
DEADLINE=$(($(date +%s) + 120))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "${APP_CID}" 2>/dev/null)" = "healthy" ]; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    err "App did not become healthy within 120s."
    err "Check logs: ${DC} logs app --tail=50"
    exit 1
  fi
  sleep 5
done

log "Restore complete. App is healthy. Postgres was NOT restored by this script — use restore-from-dump.sh separately if needed. $(date -u)"
