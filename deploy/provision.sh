#!/usr/bin/env bash
# Idempotent provisioning script for Hetzner CX32 (Ubuntu 22.04 LTS)
# Run as root (or via sudo) on a fresh server.
# Safe to re-run — all steps check whether work is already done.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Error: This script must be run as root."
  exit 1
fi

DEPLOY_ROOT="/opt/skillars"

log() { echo "[provision] $*"; }
err() { echo "[provision][error] $*" >&2; }

# ──────────────────────────────────────────────────
# 1. System packages
# ──────────────────────────────────────────────────
log "Installing system packages..."
apt-get update -qq
apt-get install -y curl git unzip fail2ban ufw ca-certificates gnupg lsb-release awscli

# ──────────────────────────────────────────────────
# 2. Docker Engine (official Docker APT repo)
# ──────────────────────────────────────────────────
if ! command -v docker &>/dev/null; then
  log "Installing Docker Engine..."
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg

  echo \
    "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -qq
  apt-get install -y \
    docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
else
  log "Docker already installed — skipping."
fi

log "Docker Compose version: $(docker compose version)"

# ──────────────────────────────────────────────────
# 3. SSH hardening
# ──────────────────────────────────────────────────
SSH_DROP_IN="/etc/ssh/sshd_config.d/99-skillars-hardening.conf"
if [ ! -f "${SSH_DROP_IN}" ]; then
  log "Applying SSH hardening..."
  cat > "${SSH_DROP_IN}" <<'EOF'
PasswordAuthentication no
PermitRootLogin prohibit-password
EOF
  systemctl reload ssh
  log "SSH hardening applied and sshd reloaded."
else
  log "SSH hardening already applied — skipping."
fi

# ──────────────────────────────────────────────────
# 4. fail2ban — sshd jail
# ──────────────────────────────────────────────────
FAIL2BAN_CONF="/etc/fail2ban/jail.d/sshd-skillars.conf"
if [ ! -f "${FAIL2BAN_CONF}" ]; then
  log "Configuring fail2ban sshd jail..."
  cat > "${FAIL2BAN_CONF}" <<'EOF'
[sshd]
enabled  = true
port     = ssh
filter   = sshd
maxretry = 5
bantime  = 3600
findtime = 600
EOF
  systemctl enable fail2ban
  systemctl restart fail2ban
  log "fail2ban configured and restarted."
else
  log "fail2ban sshd jail already configured — skipping."
fi

# ──────────────────────────────────────────────────
# 5. Directory structure
# ──────────────────────────────────────────────────
log "Creating deployment directory structure..."
mkdir -p \
  "${DEPLOY_ROOT}/data/postgres" \
  "${DEPLOY_ROOT}/lgtm" \
  "${DEPLOY_ROOT}/traefik"

log "Deployment directories created (or already exist)."

# ──────────────────────────────────────────────────
# 5.5 Security file permissions
# ──────────────────────────────────────────────────

# acme.json — Traefik refuses to start if this file is missing or has wrong permissions.
ACME_JSON="${DEPLOY_ROOT}/traefik/acme.json"
if [ -L "${ACME_JSON}" ]; then
  err "${ACME_JSON} is a symlink, refusing to chmod."
  exit 1
fi
if [ ! -f "${ACME_JSON}" ]; then
  log "Creating ${ACME_JSON} with mode 600..."
  touch "${ACME_JSON}"
  if ! chmod 600 "${ACME_JSON}"; then
    err "Failed to set permissions on ${ACME_JSON}"
    exit 1
  fi
  log "${ACME_JSON} created with mode 600."
else
  if ! chmod 600 "${ACME_JSON}"; then
    err "Failed to set permissions on ${ACME_JSON}"
    exit 1
  fi
  log "${ACME_JSON} permissions enforced (mode 600)."
fi

# .env — enforce mode 600 if present; warn but continue if absent
ENV_FILE="${DEPLOY_ROOT}/.env"
if [ -f "${ENV_FILE}" ]; then
  if [ -L "${ENV_FILE}" ]; then
    err "${ENV_FILE} is a symlink, refusing to chmod."
    exit 1
  fi
  if ! chmod 600 "${ENV_FILE}"; then
    err "Failed to set permissions on ${ENV_FILE}"
    exit 1
  fi
  log "${ENV_FILE} permissions enforced (mode 600)."
else
  log "⚠️  ${ENV_FILE} not found."
  log "    Place it before running 'docker compose up -d', then re-run this script"
  log "    (or manually: chmod 600 ${ENV_FILE})."
fi

# ──────────────────────────────────────────────────
# 6. Hetzner Volume mount (/dev/sdb → /opt/skillars/data)
# ──────────────────────────────────────────────────
VOLUME_DEVICE="/dev/sdb"
MOUNT_POINT="${DEPLOY_ROOT}/data"
FSTAB_ENTRY="${VOLUME_DEVICE} ${MOUNT_POINT} ext4 defaults,nofail 0 2"

if [ -b "${VOLUME_DEVICE}" ]; then
  # Check if already mounted
  if mountpoint -q "${MOUNT_POINT}"; then
    log "Volume ${VOLUME_DEVICE} already mounted at ${MOUNT_POINT} — skipping."
  else
    # Format only if no filesystem present
    if ! blkid "${VOLUME_DEVICE}" &>/dev/null; then
      log "Formatting ${VOLUME_DEVICE} as ext4..."
      mkfs.ext4 "${VOLUME_DEVICE}"
    else
      log "Volume ${VOLUME_DEVICE} already has a filesystem — skipping format."
    fi

    log "Mounting ${VOLUME_DEVICE} at ${MOUNT_POINT}..."
    mount "${VOLUME_DEVICE}" "${MOUNT_POINT}"

    # Add to fstab if not already present
    if ! grep -qF "${FSTAB_ENTRY}" /etc/fstab; then
      echo "${FSTAB_ENTRY}" >> /etc/fstab
      log "Added ${VOLUME_DEVICE} to /etc/fstab for persistent mount."
    fi
  fi

  # Recreate sub-directories on mounted volume
  mkdir -p "${MOUNT_POINT}/postgres"
  mkdir -p "${MOUNT_POINT}/prometheus"
  chown -R 65534:65534 "${MOUNT_POINT}/prometheus"
  mkdir -p "${MOUNT_POINT}/loki"
  chown -R 10001:10001 "${MOUNT_POINT}/loki"
  mkdir -p "${MOUNT_POINT}/tempo"
  chown -R 10001:10001 "${MOUNT_POINT}/tempo"
  mkdir -p "${MOUNT_POINT}/grafana"
  chown -R 472:472 "${MOUNT_POINT}/grafana"
else
  log "⚠️  Hetzner Volume device ${VOLUME_DEVICE} not found."
  log "    Attach the Volume to this server in the Hetzner Cloud Console, then re-run this script."
  log "    PostgreSQL data will NOT be on a persistent volume until this is done."
fi

log ""
log "✅ Provisioning complete."
log "   Next steps:"
log "   1. Place ${DEPLOY_ROOT}/.env (re-run this script to auto-enforce mode 600)"
log "   2. Run deploy/firewall/apply-firewall.sh from your local machine"
log "   3. Deploy services: cd ${DEPLOY_ROOT} && docker compose up -d"
log "   4. (After placing .env) Install backup crons: bash ${DEPLOY_ROOT}/deploy/backup/install-crons.sh"
