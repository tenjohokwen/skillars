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
# Writes to BOTH stderr (terminal-red visibility, stream-aware tooling) and stdout, so an operator
# running `provision.sh > provision.log` with no `2>&1` still keeps every error line. Dual `echo`
# rather than `| tee /dev/stderr` deliberately: a pipeline whose `tee` fails (stderr closed,
# ENOSPC) would abort mid-message under `set -o pipefail`, and the unconfigured-alert block chains
# three `err` calls before its `exit 1`. Known cosmetic cost: a caller that DOES redirect `2>&1`
# sees each error line twice.
err() { echo "[provision][error] $*" >&2; echo "[provision][error] $*"; }

# Idempotent chown: skips the recursive chown entirely when the directory's current owner already
# matches, so a rerun against a live system can't interrupt an in-progress container write. Safe on
# first provision too — a freshly-created directory never matches, so it always chowns then.
#
# skillars-deferred-87 AC3 — partial-completion second tier: a run killed *mid-`chown -R`* (SIGKILL,
# OOM, power loss during the exact chown) can leave the top-level dir already owned correctly while
# its children are only half-changed; the top-level check alone would then skip on re-run and leave
# mixed ownership under the data mount for good. So when the top-level owner matches, also scan the
# dir and its immediate children (`-maxdepth 2`) for a uid OR gid mismatch — `chown -R` sets both,
# so an interruption between them leaves one right and one wrong — and fall through to `chown -R` on
# a hit. GNU `chown -R` traverses pre-order (each directory is chowned before its contents), so an
# interruption always leaves the top level done and some descendants not; `-maxdepth 2` catches the
# common early-interruption case (top level + its immediate children) without a full metadata scan
# of a Volume carrying real observability retention on every idempotent re-run. LIMITATION: a
# mismatch buried more than two levels deep is NOT caught here — docs/deployment/first-time-setup.md
# documents the manual `find … \( \! -uid N -o \! -gid N \) -print -quit` + `chown -R` remediation.
chown_if_needed() {
  local owner="$1" dir="$2"
  local uid="${owner%%:*}" gid="${owner##*:}"
  if [ "$(stat -c '%u:%g' "$dir" 2>/dev/null)" != "$owner" ]; then
    chown -R "$owner" "$dir"
  elif [ -n "$(find "$dir" -maxdepth 2 \( \! -uid "$uid" -o \! -gid "$gid" \) -print -quit 2>/dev/null)" ]; then
    chown -R "$owner" "$dir"
  fi
}

# ──────────────────────────────────────────────────
# 1. System packages
# ──────────────────────────────────────────────────
log "Installing system packages..."
apt-get update -qq
# rsync: used by section 7 to stage/migrate a pre-Volume data/ tree onto the Hetzner Volume.
apt-get install -y curl git unzip jq rsync fail2ban ufw ca-certificates gnupg lsb-release awscli

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
bantime  = 86400
findtime = 600
EOF
  systemctl enable fail2ban
  systemctl restart fail2ban
  log "fail2ban configured and restarted."
else
  log "fail2ban sshd jail already configured — skipping."
fi

# ──────────────────────────────────────────────────
# 5. Host firewall (ufw)
# ──────────────────────────────────────────────────
log "Configuring ufw..."
# Allow SSH first — CRITICAL: must happen before 'ufw enable' or the SSH session may terminate
ufw allow 22/tcp comment 'SSH'
# Traefik exposes 80 and 443 to the host
ufw allow 80/tcp comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'
# Default policies
ufw default deny incoming
ufw default allow outgoing
# Enable (--force skips the interactive confirmation prompt; idempotent if already enabled)
ufw --force enable
log "ufw status:"
ufw status verbose

# ──────────────────────────────────────────────────
# 6. Directory structure
# ──────────────────────────────────────────────────
log "Creating deployment directory structure..."
mkdir -p \
  "${DEPLOY_ROOT}/data/postgres" \
  "${DEPLOY_ROOT}/lgtm"

log "Deployment directories created (or already exist)."

# ──────────────────────────────────────────────────
# 6.5 Security file permissions
# ──────────────────────────────────────────────────
#
# NOTE: the acme.json block used to live here. It now lives AFTER section 7, because acme.json
# moved onto the Hetzner Volume at ${DEPLOY_ROOT}/data/traefik. Creating it from this position
# would write it to the ROOT DISK and section 7's mount would then hide it — Traefik would start
# against an empty file and silently reissue every certificate. Do not move it back.

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
# 7. Hetzner Volume mount (resolved via /dev/disk/by-id → /opt/skillars/data)
# ──────────────────────────────────────────────────
# Device (skillars-deferred-87 AC4): resolve the Volume by its stable per-Volume symlink
#   /dev/disk/by-id/scsi-0HC_Volume_<id> — /dev/sdX ordering is NOT guaranteed once a server has
#   more than one Volume. Prefer an exact HETZNER_VOLUME_ID match when the environment sets it;
#   otherwise take the first such symlink (single-Volume is the current topology). Fall back to
#   /dev/sdb only when no by-id symlink exists. /etc/fstab always carries the stable by-id path,
#   never the resolved /dev/sdX.
# Pre-Volume data (skillars-deferred-87 AC5): if this host was provisioned once with no Volume
#   attached, section 7.5 wrote acme.json / redis/ / LGTM dirs to the ROOT DISK under ${MOUNT_POINT}.
#   On the re-run where the Volume first appears, that tree is staged to ${STAGING} (a sibling of
#   ${MOUNT_POINT}, never under the mount, so it survives `mount` and a `mountpoint -q` re-run
#   short-circuit), the Volume is mounted, and the tree is copied onto it and verified — UNLESS the
#   Volume already carries data (a re-attach), in which case the staged copy is left in ${STAGING}
#   for manual reconciliation rather than clobbering the Volume.
MOUNT_POINT="${DEPLOY_ROOT}/data"
STAGING="${DEPLOY_ROOT}/.pre-volume-migration"

VOLUME_LINK=""
if [ -n "${HETZNER_VOLUME_ID:-}" ]; then
  if [ -e "/dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID}" ]; then
    VOLUME_LINK="/dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID}"
  else
    err "HETZNER_VOLUME_ID=${HETZNER_VOLUME_ID} is set but /dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID} does not exist (typo, stale id, or the Volume is not attached) — falling back to first-match / /dev/sdb, which may resolve to the WRONG Volume on a multi-Volume host."
  fi
fi
if [ -z "${VOLUME_LINK}" ]; then
  for _link in /dev/disk/by-id/scsi-0HC_Volume_*; do
    if [ -e "${_link}" ]; then
      VOLUME_LINK="${_link}"
      break
    fi
  done
fi

if [ -n "${VOLUME_LINK}" ]; then
  VOLUME_DEVICE="$(readlink -f "${VOLUME_LINK}")"
  log "Hetzner Volume resolved via ${VOLUME_LINK} -> ${VOLUME_DEVICE}."
else
  VOLUME_LINK="/dev/sdb"
  VOLUME_DEVICE="/dev/sdb"
  log "No /dev/disk/by-id/scsi-0HC_Volume_* symlink present — falling back to ${VOLUME_DEVICE}."
fi

# fstab uses the stable identifier (VOLUME_LINK), never the resolved /dev/sdX which can reorder.
FSTAB_ENTRY="${VOLUME_LINK} ${MOUNT_POINT} ext4 defaults,nofail 0 2"

# True if $1 holds a real pre-Volume payload — Traefik's acme.json, or a non-empty redis/ or LGTM
# data dir. Section 6 always leaves an (empty) postgres/ under the root-disk mount point, so a bare
# "directory not empty" test is useless as a trigger; this checks for concrete artifacts only.
pre_volume_payload_present() {
  local base="$1" d
  [ -f "${base}/traefik/acme.json" ] && return 0
  for d in redis grafana loki tempo prometheus; do
    if [ -d "${base}/${d}" ] && [ -n "$(find "${base}/${d}" -mindepth 1 -print -quit 2>/dev/null)" ]; then
      return 0
    fi
  done
  return 1
}

# Copy the staged pre-Volume tree onto the now-mounted Volume, re-check with a dry-run rsync that
# nothing is still pending, verify every armed path landed with an identical mode+owner, then remove
# ${STAGING}. Idempotent — rsync without --delete only adds/updates, so re-running after an
# interrupted migration is safe. Never rm a path under a mountpoint.
migrate_pre_volume_data() {
  local ok=1 d src_meta dst_meta pending
  log "Migrating pre-Volume data from ${STAGING} onto ${MOUNT_POINT}..."
  rsync -aHAX --numeric-ids "${STAGING}/" "${MOUNT_POINT}/"

  # Post-copy re-check. A torn rsync (SIGKILL, Volume ENOSPC mid-transfer) can leave the top-level
  # dirs present with the right mode+owner but missing contents — which the per-path stat checks
  # below would still pass. A dry-run rsync re-run must report nothing left to transfer; `^\.d`
  # (a directory that already exists, at most an attribute restat) is the only benign line.
  pending="$(rsync -aHAXni --numeric-ids "${STAGING}/" "${MOUNT_POINT}/" 2>/dev/null \
    | grep -Ev '^\.d|^$' || true)"
  if [ -n "${pending}" ]; then
    err "Pre-Volume data migration is INCOMPLETE — a dry-run rsync still reports pending transfers:"
    printf '%s\n' "${pending}" | head -n 20 | while IFS= read -r _l; do err "  ${_l}"; done
    err "Leaving ${STAGING} in place for manual reconciliation."
    exit 1
  fi

  if [ -f "${STAGING}/traefik/acme.json" ]; then
    src_meta="$(stat -c '%a %u:%g' "${STAGING}/traefik/acme.json")"
    dst_meta="$(stat -c '%a %u:%g' "${MOUNT_POINT}/traefik/acme.json" 2>/dev/null || echo MISSING)"
    [ "${src_meta}" = "${dst_meta}" ] || { err "migration verify: traefik/acme.json ${src_meta} != ${dst_meta}"; ok=0; }
  fi
  for d in redis grafana loki tempo prometheus; do
    [ -d "${STAGING}/${d}" ] && [ -n "$(find "${STAGING}/${d}" -mindepth 1 -print -quit 2>/dev/null)" ] || continue
    src_meta="$(stat -c '%a %u:%g' "${STAGING}/${d}")"
    dst_meta="$(stat -c '%a %u:%g' "${MOUNT_POINT}/${d}" 2>/dev/null || echo MISSING)"
    [ "${src_meta}" = "${dst_meta}" ] || { err "migration verify: ${d}/ ${src_meta} != ${dst_meta}"; ok=0; }
  done
  if [ "${ok}" -ne 1 ]; then
    err "Pre-Volume data migration verification FAILED — leaving ${STAGING} in place for manual reconciliation."
    exit 1
  fi

  # rm guard: never touch a path under, or equal to, the mount point, and never an actual mountpoint.
  case "${STAGING}" in
    "${MOUNT_POINT}"|"${MOUNT_POINT}"/*) err "refusing to rm ${STAGING}: at or under the mount point"; exit 1 ;;
  esac
  if [ -d "${STAGING}" ] && ! mountpoint -q "${STAGING}"; then
    rm -rf "${STAGING}"
    log "Pre-Volume data migrated and verified; removed ${STAGING}."
  fi
  log "NOTE: the original pre-Volume copy still sits on the ROOT DISK, hidden under ${MOUNT_POINT}."
  log "      Reclaim it manually if needed: unmount ${MOUNT_POINT}, rm -rf its root-disk contents, remount."
}

# Called after the mount block (whether or not the mount was already done) so it is also the resume
# path for a run interrupted between `mount` and the ${STAGING} cleanup. No-op when nothing is
# staged. Decision (skillars-deferred-87 AC5 step 4): staged data + empty Volume (only lost+found)
# -> migrate; staged data + Volume already has data -> do NOT clobber, keep ${STAGING}, warn.
settle_pre_volume_migration() {
  if [ ! -d "${STAGING}" ] || [ -z "$(find "${STAGING}" -mindepth 1 -print -quit 2>/dev/null)" ]; then
    return 0
  fi
  mountpoint -q "${MOUNT_POINT}" || return 0

  local vol_entries
  # A fresh mkfs.ext4 volume always contains lost+found, so a plain emptiness test is wrong.
  vol_entries="$(find "${MOUNT_POINT}" -mindepth 1 -maxdepth 1 \! -name lost+found -print -quit 2>/dev/null || true)"
  if [ -n "${vol_entries}" ]; then
    log "⚠️  ${MOUNT_POINT} already contains data and a staged pre-Volume copy exists at ${STAGING}."
    log "    NOT overwriting the Volume. If you just interrupted a migration, confirm the staged tree"
    log "    is already present under ${MOUNT_POINT}, then 'rm -rf ${STAGING}'. Otherwise this is a"
    log "    re-attached Volume with its own data — reconcile ${STAGING} into ${MOUNT_POINT} by hand."
    return 0
  fi
  migrate_pre_volume_data
}

if [ -b "${VOLUME_DEVICE}" ]; then
  if mountpoint -q "${MOUNT_POINT}"; then
    log "Volume ${VOLUME_DEVICE} already mounted at ${MOUNT_POINT} — skipping mount."
  else
    # Stage any pre-Volume payload on the root disk BEFORE the mount hides it. ${STAGING} is a
    # sibling of ${MOUNT_POINT}, never under it, so it survives the mount and a later re-run.
    if pre_volume_payload_present "${MOUNT_POINT}"; then
      log "Pre-Volume data present on the root disk at ${MOUNT_POINT}; staging to ${STAGING} before mount."
      mkdir -p "${STAGING}"
      rsync -aHAX --numeric-ids "${MOUNT_POINT}/" "${STAGING}/"
    fi

    # Format only if no filesystem present
    if ! blkid "${VOLUME_DEVICE}" &>/dev/null; then
      log "Formatting ${VOLUME_DEVICE} as ext4..."
      mkfs.ext4 "${VOLUME_DEVICE}"
    else
      log "Volume ${VOLUME_DEVICE} already has a filesystem — skipping format."
    fi

    log "Mounting ${VOLUME_DEVICE} at ${MOUNT_POINT}..."
    mount "${VOLUME_DEVICE}" "${MOUNT_POINT}"
  fi

  # /etc/fstab maintenance — OUTSIDE the mount if/else so it also runs on the steady-state re-run of
  # an already-mounted node. A node first provisioned by the old script carries a
  # `/dev/sdb ${MOUNT_POINT} ext4 defaults,nofail 0 2` line and stays mounted across reboots via it;
  # now that FSTAB_ENTRY uses the by-id path the `grep -qF` add-guard below no longer matches that
  # line and would append a SECOND entry for the same mount point (double `mount -a` on next
  # reboot). So purge any non-matching line for this mount point first (mirrors install-crons.sh's
  # stale-cron purge), then add the by-id entry if absent.
  if grep -vFx "${FSTAB_ENTRY}" /etc/fstab | grep -qE "[[:space:]]${MOUNT_POINT}[[:space:]]"; then
    sed -i "\#[[:space:]]${MOUNT_POINT}[[:space:]]#d" /etc/fstab
    log "Removed a stale /etc/fstab entry for ${MOUNT_POINT} (Volume is now referenced by its stable by-id path)."
  fi
  if ! grep -qF "${FSTAB_ENTRY}" /etc/fstab; then
    # A hand-edited /etc/fstab may lack a trailing newline; without this the append would fuse onto
    # the previous line and defeat this entry's `nofail`. $(…) strips the trailing \n, so this adds
    # one only when the file does not already end with a newline.
    if [ -n "$(tail -c1 /etc/fstab)" ]; then echo >> /etc/fstab; fi
    echo "${FSTAB_ENTRY}" >> /etc/fstab
    log "Added ${VOLUME_LINK} to /etc/fstab for persistent mount."
  fi

  # Migrate a staged pre-Volume tree onto the Volume. Placed after the mount if/else (not inside
  # it) so it also runs on a re-run whose `mountpoint -q` short-circuited the mount steps above —
  # the resume path for a run killed after `mount` but before the ${STAGING} cleanup.
  settle_pre_volume_migration

  # Recreate sub-directories on mounted volume
  mkdir -p "${MOUNT_POINT}/postgres"
  mkdir -p "${MOUNT_POINT}/prometheus"
  chown_if_needed 65534:65534 "${MOUNT_POINT}/prometheus"
  mkdir -p "${MOUNT_POINT}/loki"
  chown_if_needed 10001:10001 "${MOUNT_POINT}/loki"
  mkdir -p "${MOUNT_POINT}/tempo"
  chown_if_needed 10001:10001 "${MOUNT_POINT}/tempo"
  mkdir -p "${MOUNT_POINT}/grafana"
  chown_if_needed 472:472 "${MOUNT_POINT}/grafana"
else
  log "⚠️  Hetzner Volume device not found (no /dev/disk/by-id/scsi-0HC_Volume_* symlink and no ${VOLUME_DEVICE})."
  log "    Attach the Volume to this server in the Hetzner Cloud Console, then re-run this script."
  log "    Until then everything under ${MOUNT_POINT} — PostgreSQL, Redis and the LGTM stack's data,"
  log "    and Traefik's acme.json (created below) — stays on the ROOT DISK and a server rebuild loses"
  log "    it, including every TLS certificate. The re-run after attaching the Volume MIGRATES this"
  log "    pre-Volume data onto it (staged via ${STAGING}); it is no longer left stranded under the mount."
fi

# ──────────────────────────────────────────────────
# 7.5 Data directories and permissions that must exist with or without the Volume
# ──────────────────────────────────────────────────
#
# Deliberately AFTER the `fi` above, so it covers BOTH branches: on a host with no Volume attached
# section 7 only warns and falls through, and these two must still exist rather than be created by
# Docker as root-owned on first `up`. Relocated here from section 6.5 — see the note there.
#
# Redis, unlike the LGTM directories in section 7, is here rather than there because it fails HARD
# on a wrong owner: the image drops to uid 999 and cannot write an AOF into a root-owned directory,
# so a no-Volume host would end up with a crash-looping redis instead of degraded durability.
# uid/gid verified from the image itself: `docker run --rm redis:7-alpine id redis`
# -> uid=999(redis) gid=1000(redis). Do not guess it.
mkdir -p "${MOUNT_POINT}/redis"
chown_if_needed 999:1000 "${MOUNT_POINT}/redis"

# acme.json — Traefik refuses to start if this file is missing or has wrong permissions.
# 700 on the directory matches the manual fallback documented in deploy/traefik/README.md; the
# automated and documented paths must not diverge.
mkdir -p "${MOUNT_POINT}/traefik"
chmod 700 "${MOUNT_POINT}/traefik"
ACME_JSON="${MOUNT_POINT}/traefik/acme.json"
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

# ──────────────────────────────────────────────────
# 8. Alert-routing sanity check
# ──────────────────────────────────────────────────
#
# Deliberately AFTER section 7.5: an alert-routing misconfiguration must never block the Volume
# mount or the acme.json / data-dir setup above. Grafana provisions the `notify-ops` contact
# point from grafana-alerts.yml regardless of whether these vars are set, so if BOTH are blank
# every firing alert routes to nowhere, silently.
#
# Read one value from .env, normalised so a functionally-blank channel cannot pass the guards below
# and a genuinely-set one cannot read as blank: tolerate an optional `export ` prefix, strip a
# trailing CR (CRLF .env), strip surrounding matching quotes (`KEY=""` -> empty), and trim
# whitespace. The `|| true` is REQUIRED — under `set -euo pipefail` grep exits 1 when the key is
# absent (common, these read as optional), which would otherwise abort the script with no message.
env_val() {
  local key="$1" v
  v=$(grep -E "^([[:space:]]*export[[:space:]]+)?${key}=" "${ENV_FILE}" | tail -1 || true)
  v=${v#*"${key}="}
  v=${v%$'\r'}
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  case "$v" in
    \"*\") v=${v#\"}; v=${v%\"} ;;
    \'*\') v=${v#\'}; v=${v%\'} ;;
  esac
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  printf '%s' "$v"
}

# Only runs when .env is present — section 6.5 already tells the operator to place .env and re-run,
# and this check fires on that re-run.
if [ -f "${ENV_FILE}" ]; then
  GF_EMAIL=$(env_val GF_ALERT_NOTIFY_EMAIL)
  GF_SLACK=$(env_val GF_SLACK_WEBHOOK_URL)
  GF_SMTP=$(env_val GF_SMTP_ENABLED)

  if [ -z "${GF_EMAIL}" ] && [ -z "${GF_SLACK}" ]; then
    err "Alert routing is unconfigured: both GF_ALERT_NOTIFY_EMAIL and GF_SLACK_WEBHOOK_URL are"
    err "blank in ${ENV_FILE}. Grafana still provisions the notify-ops contact point, so every"
    err "alert would route to a dead address. Set at least one and re-run this script."
    exit 1
  fi

  if [ -n "${GF_EMAIL}" ] && [ -z "${GF_SLACK}" ]; then
    log "⚠️  GF_SLACK_WEBHOOK_URL is blank — the notify-ops Slack receiver will provision with an"
    log "    empty URL and silently no-op on every alert until it is set."
  fi
  if [ -z "${GF_EMAIL}" ] && [ -n "${GF_SLACK}" ]; then
    log "⚠️  GF_ALERT_NOTIFY_EMAIL is blank — the notify-ops email receiver will provision with an"
    log "    empty address and silently no-op on every alert until it is set."
  fi

  if [ -n "${GF_EMAIL}" ] && [ "${GF_SMTP}" != "true" ]; then
    log "⚠️  GF_ALERT_NOTIFY_EMAIL is set but GF_SMTP_ENABLED is not 'true' — Grafana email routing"
    log "    also needs GF_SMTP_ENABLED=true and the GF_SMTP_* block, or email alerts silently fail."
  fi
fi

log ""
log "✅ Provisioning complete."
log "   Next steps:"
log "   1. Place ${DEPLOY_ROOT}/.env (re-run this script to auto-enforce mode 600)"
log "   2. Run deploy/firewall/apply-firewall.sh from your local machine"
log "   3. Deploy services: cd ${DEPLOY_ROOT} && docker compose up -d"
log "   4. (After placing .env) Install backup crons: bash ${DEPLOY_ROOT}/deploy/backup/install-crons.sh"
