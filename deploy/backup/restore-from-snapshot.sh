#!/usr/bin/env bash
# Restore Node from a Hetzner Volume snapshot.
# This script handles the on-Node steps. You must perform Hetzner Console steps manually at the prompt.
#
# Prerequisites:
#   1. SSH access to the Node as root
#   2. Repository cloned at /opt/skillars and /opt/skillars/.env in place
#
# Steps handled by this script:
#   A. Stop all Docker services
#   B. Unmount the current volume (PAUSE — you detach/attach in Hetzner Console)
#   C. Mount the new volume
#   D. Restore subdirectory ownership
#   E. Start all Docker services
#   F. Verify application health
set -euo pipefail

# shellcheck source=/dev/null
. /opt/skillars/.env

COMPOSE_FILE="/opt/skillars/docker-compose.yml"
DATA_DIR="/opt/skillars/data"
VOLUME_DEVICE="/dev/sdb"

log() { echo "[restore-snapshot] $*"; }
err() { echo "[restore-snapshot][error] $*" >&2; }

# ── A. Stop all services ──────────────────────────────────
log "Stopping all Docker services..."
docker compose -f "${COMPOSE_FILE}" down

# ── B. Unmount current volume ─────────────────────────────
if mountpoint -q "${DATA_DIR}"; then
  log "Unmounting ${DATA_DIR}..."
  umount "${DATA_DIR}"
else
  log "${DATA_DIR} is not currently mounted — skipping umount."
fi

# ── MANUAL HETZNER STEP ───────────────────────────────────
echo ""
echo "============================================================"
echo "  MANUAL STEP REQUIRED IN HETZNER CLOUD CONSOLE"
echo "============================================================"
echo "  1. Go to Hetzner Cloud Console → Volumes"
echo "  2. Detach the current volume from this server (if attached)"
echo "  3. Go to the snapshot you want to restore"
echo "     (Cloud Console → Volumes → Snapshots or via the volume's actions)"
echo "  4. Create a new Volume from that snapshot"
echo "  5. Attach the new Volume to this server as /dev/sdb"
echo "  6. Wait for Hetzner to confirm the attachment is complete"
echo "============================================================"
echo ""
read -r -p "Press ENTER when the new volume is attached as /dev/sdb..."

# ── C. Mount new volume ───────────────────────────────────
if ! [ -b "${VOLUME_DEVICE}" ]; then
  err "Device ${VOLUME_DEVICE} not found. Ensure the volume is attached in Hetzner Console."
  exit 1
fi

if mountpoint -q "${DATA_DIR}"; then
  log "${DATA_DIR} is already mounted — skipping mount (idempotent)."
else
  log "Mounting ${VOLUME_DEVICE} at ${DATA_DIR}..."
  mount "${VOLUME_DEVICE}" "${DATA_DIR}"
fi

# ── D. Restore subdirectory ownership ────────────────────
log "Restoring subdirectory ownership..."
mkdir -p "${DATA_DIR}/postgres"
mkdir -p "${DATA_DIR}/prometheus"
chown -R 65534:65534 "${DATA_DIR}/prometheus"
mkdir -p "${DATA_DIR}/loki"
chown -R 10001:10001 "${DATA_DIR}/loki"
mkdir -p "${DATA_DIR}/tempo"
chown -R 10001:10001 "${DATA_DIR}/tempo"
mkdir -p "${DATA_DIR}/grafana"
chown -R 472:472 "${DATA_DIR}/grafana"

# ── E. Start services ─────────────────────────────────────
log "Starting all Docker services..."
docker compose -f "${COMPOSE_FILE}" up -d

# ── F. Health check ───────────────────────────────────────
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

log "Restore complete. App is healthy. $(date -u)"
