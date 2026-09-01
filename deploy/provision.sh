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

# skillars-deferred-89 AC8 — optional. When set, section 5 scopes the host firewall's port-22 rule
# to this single source IP for the window between this script finishing and
# deploy/firewall/apply-firewall.sh (first-time-setup.md Step 4) applying the Hetzner Cloud
# firewall. Must be a bare IPv4 address — validated with apply-firewall.sh's exact regex, /32
# appended by this script. A malformed value, or one that does not match the live SSH session's
# client IP, fails OPEN to the internet-wide `ufw allow 22/tcp` with a warning; it never bricks the
# session. Unset by default. See docs/deployment/first-time-setup.md, "SSH exposure window".
#
# NOTE: set it to YOUR workstation's public egress IP (the same value Step 4 uses — run
# `curl -s ifconfig.me` on your LOCAL machine, NOT on the node), and if you invoke this script via
# `sudo` you MUST pass `sudo -E` (or add SSH_ALLOWLIST_IP / SSH_CLIENT to sudoers `env_keep`) —
# plain `sudo` strips both under the default `env_reset`, so scoping silently does not happen.
SSH_ALLOWLIST_IP="${SSH_ALLOWLIST_IP:-}"

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
# directory, its children and its grandchildren (`find … -maxdepth 2` from $dir: depth 0 = $dir,
# 1 = children, 2 = grandchildren) for a uid OR gid mismatch — `chown -R` sets both, so an
# interruption between them leaves one right and one wrong — and fall through to `chown -R` on a
# hit. GNU `chown -R` traverses pre-order (each directory is chowned before its contents), so an
# interruption always leaves the top level done and some descendants not; going two levels deep
# (not one) catches the case where a single-child directory — e.g. `loki/` → `chunks/`,
# `prometheus/` → its data dir — was descended into before the interruption, which `-maxdepth 1`
# would miss entirely. Still bounded: no full metadata scan of a Volume carrying real observability
# retention on every idempotent re-run. LIMITATION: a mismatch buried more than two levels deep is
# NOT caught here — docs/deployment/first-time-setup.md documents the manual
# `find … \( \! -uid N -o \! -gid N \) -print -quit` + `chown -R` remediation.
chown_if_needed() {
  local owner="$1" dir="$2"
  local uid="${owner%%:*}" gid="${owner##*:}"
  if [ "$(stat -c '%u:%g' "$dir" 2>/dev/null)" != "$owner" ]; then
    chown -R "$owner" "$dir"
  elif [ -n "$(find "$dir" -maxdepth 2 \( \! -uid "$uid" -o \! -gid "$gid" \) -print -quit 2>/dev/null)" ]; then
    chown -R "$owner" "$dir"
  fi
}

# skillars-deferred-89 AC8 (code review). Delete every `SSH (allowlisted)` ufw rule whose source is
# NOT $1. Without this, an operator whose egress IP changed between runs (dynamic ISP / VPN) would
# accumulate a permanent SSH grant for each reassigned address. Rule numbers shift on every delete,
# so re-query and remove one at a time. `ufw status numbered` lines look like
#   [ 3] 22/tcp    ALLOW IN    203.0.113.10    # SSH (allowlisted)
prune_stale_allowlisted_ssh_rules() {
  local keep_ip="$1" keep_re line num
  keep_re="[[:space:]]${keep_ip//./\\.}[[:space:]]"
  while :; do
    line="$(ufw status numbered 2>/dev/null | grep 'SSH (allowlisted)' | grep -vE "${keep_re}" | tail -n1 || true)"
    [ -n "${line}" ] || break
    num="$(printf '%s' "${line}" | sed -n 's/^\[[[:space:]]*\([0-9]\{1,\}\)\].*/\1/p')"
    [ -n "${num}" ] || break
    log "Removing a stale scoped SSH rule (#${num}, source no longer ${keep_ip}): ${line#*] }"
    ufw --force delete "${num}"
  done
}

# ──────────────────────────────────────────────────
# 0. Concurrency guard — whole-script exclusive lock (skillars-deferred-88 AC3)
# ──────────────────────────────────────────────────
# provision.sh is idempotent and re-run-safe, but two runs OVERLAPPING (two operators, or a manual
# run racing a scheduled one) can still corrupt shared state — e.g. one run's `rm -rf "${STAGING}"`
# landing mid-`rsync` of the other, or a double `mkfs.ext4` / `mount` of the same device. This is a
# coarse WHOLE-SCRIPT lock (idempotent re-runs included), deliberately not per-section: concurrent
# provision.sh is not a supported scenario and the lock exists to make that explicit, not to enable
# parallelism.
#
# The self-re-exec idiom below only works when the script is invoked from a FILE
# (`bash /opt/skillars/deploy/provision.sh` — the only documented invocation, per
# docs/deployment/first-time-setup.md) — NOT under `curl … | bash`, where $0 is `bash` / `-bash`
# with no path. Every documented invocation path is file-based.
LOCK_FILE="/var/lock/skillars-provision.lock"
if [ "${_PROVISION_LOCKED:-}" != "1" ]; then
  if ! command -v flock >/dev/null 2>&1; then
    err "flock not found — it ships with util-linux and is always present on the Ubuntu base."
    err "Cannot guard against a concurrent provision.sh run; install util-linux and re-run."
    exit 1
  fi
  [ -e "${LOCK_FILE}" ] || install -m 0644 /dev/null "${LOCK_FILE}"
  # flock runs `bash "$0"` again with _PROVISION_LOCKED=1 while holding the lock, so the real body
  # runs exactly once under the lock. --nonblock + --conflict-exit-code 99 makes a second concurrent
  # invocation fail fast and distinguishably (99) instead of hanging; any other non-zero code is the
  # re-exec'd child's own failure and must propagate. Invoked as `bash "$0"` (not bare "$0") so a
  # checkout without the execute bit still works — the only documented invocation is
  # `bash /opt/skillars/deploy/provision.sh` anyway.
  #
  # Capture flock's status DIRECTLY with `|| _flock_rc=$?` — `if cmd; then …; fi` followed by `$?`
  # yields the *if-construct's* status (0 when no branch ran), never cmd's.
  _flock_rc=0
  env _PROVISION_LOCKED=1 flock --exclusive --nonblock --conflict-exit-code 99 \
    "${LOCK_FILE}" bash "$0" "$@" || _flock_rc=$?
  if [ "${_flock_rc}" -eq 99 ]; then
    err "another provision.sh is already running (holds ${LOCK_FILE}); refusing to run concurrently."
    exit 1
  fi
  exit "${_flock_rc}"
fi

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
# Allow SSH first — CRITICAL: must happen before 'ufw enable' or the SSH session may terminate.
#
# skillars-deferred-89 AC8: optionally scope the port-22 rule to $SSH_ALLOWLIST_IP for the window
# between this script finishing and deploy/firewall/apply-firewall.sh (Step 4) applying the Hetzner
# Cloud firewall. Safeguards so this can never lock the operator out mid-run:
#   1. validate with apply-firewall.sh's exact IPv4 regex — a malformed value falls open to
#      `ufw allow 22/tcp` with a warning.
#   2. cross-check against the live SSH session ($SSH_CLIENT / $SSH_CONNECTION) — a mismatch, or no
#      SSH session at all, falls open with a loud warning naming both IPs. Guards against a
#      valid-but-wrong IP (operator behind NAT/VPN, dynamic IP, IPv6 session) locking the session
#      out when `ufw --force enable` runs seconds later.
#   3. ADD the desired rule BEFORE deleting any other port-22 rule (code review P1). On a re-run ufw
#      is already enabled with default-deny-incoming; if an `allow` failed under `set -euo pipefail`
#      AFTER a delete, the script would abort with 22 having no accept rule and only console
#      recovery. Add-then-delete leaves 22 continuously covered.
#   4. re-scope cleanly (code review P9): on the scoped path, prune every prior `SSH (allowlisted)`
#      rule for a DIFFERENT source IP, so an operator whose egress IP changed between runs does not
#      accumulate permanent SSH grants for reassigned addresses.
# IPv6 (code review P10): the scoped rule is IPv4-only. `ufw delete allow 22/tcp` removes the v4 AND
# v6 broad rules, so while a scoped rule is active SSH over IPv6 on port 22 is closed. The Hetzner
# Cloud firewall from Step 4 is dual-stack and remains the real perimeter; an operator who needs
# IPv6 SSH in the meantime should not set SSH_ALLOWLIST_IP.
# ASYMMETRY: a later run with SSH_ALLOWLIST_IP unset does NOT auto-widen a previously-scoped rule
# (a scoped `from <ip>/32` rule is not generically targetable here) — it only warns. Re-open 22 to
# all by hand: `ufw delete allow from <ip>/32 to any port 22 proto tcp`.
ssh_client_ip="${SSH_CLIENT:-}"; ssh_client_ip="${ssh_client_ip%% *}"
if [ -z "${ssh_client_ip}" ]; then
  ssh_client_ip="${SSH_CONNECTION:-}"; ssh_client_ip="${ssh_client_ip%% *}"
fi
if [ -n "${SSH_ALLOWLIST_IP}" ] \
   && [[ "${SSH_ALLOWLIST_IP}" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] \
   && [ -n "${ssh_client_ip}" ] && [ "${SSH_ALLOWLIST_IP}" = "${ssh_client_ip}" ]; then
  log "Scoping the ufw SSH rule to ${SSH_ALLOWLIST_IP}/32 (matches this SSH session's client IP)."
  log "  NOTE: the scoped rule is IPv4-only — SSH over IPv6 on port 22 is closed while it is active."
  # Add first (P1), then remove the broad rule and any stale scoped rule for another IP (P9).
  ufw allow from "${SSH_ALLOWLIST_IP}/32" to any port 22 proto tcp comment 'SSH (allowlisted)'
  ufw delete allow 22/tcp 2>/dev/null || true
  prune_stale_allowlisted_ssh_rules "${SSH_ALLOWLIST_IP}"
else
  if [ -n "${SSH_ALLOWLIST_IP}" ]; then
    if ! [[ "${SSH_ALLOWLIST_IP}" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
      err "SSH_ALLOWLIST_IP='${SSH_ALLOWLIST_IP}' is not a bare IPv4 address — malformed, ignoring; opening 22/tcp to all."
    else
      err "SSH_ALLOWLIST_IP='${SSH_ALLOWLIST_IP}' does not match this SSH session's client IP ('${ssh_client_ip:-none}') — ignoring; opening 22/tcp to all."
    fi
  else
    # code review P3: no signal at all is wrong when `sudo` (without -E) has silently stripped the
    # var, or the operator simply forgot it. One informational line — 22 is about to open wide.
    log "SSH_ALLOWLIST_IP not set — port 22/tcp opens to ALL sources until Step 4 (apply-firewall.sh)."
    log "  To scope it: export SSH_ALLOWLIST_IP=<your-workstation-public-IP> and re-run (with 'sudo -E' if using sudo)."
  fi
  ufw allow 22/tcp comment 'SSH'
  # Asymmetry (skillars-deferred-89 AC8): a scoped `allow from <ip>/32` rule from a prior run is not
  # generically targetable, so it is NOT auto-removed here. Only warn when one is actually present —
  # a plain re-run with SSH_ALLOWLIST_IP never set must stay quiet.
  if ufw status 2>/dev/null | grep -q "SSH (allowlisted)"; then
    err "A previously-scoped 'SSH (allowlisted)' ufw rule is still present and was NOT auto-widened."
    err "Port 22 is open to all via the rule just added; drop the scoped one by hand for a clean state:"
    err "  ufw delete allow from <ip>/32 to any port 22 proto tcp"
  fi
fi
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

# Enumerate the attached Hetzner Volume by-id symlinks once (skillars-deferred-88 AC5). A glob with
# no match yields the literal pattern, so -e each.
_vol_links=()
for _link in /dev/disk/by-id/scsi-0HC_Volume_*; do
  [ -e "${_link}" ] && _vol_links+=("${_link}")
done
_vol_count="${#_vol_links[@]}"

VOLUME_LINK=""
if [ -n "${HETZNER_VOLUME_ID:-}" ]; then
  if [ -e "/dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID}" ]; then
    VOLUME_LINK="/dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID}"
  elif [ "${_vol_count}" -gt 1 ]; then
    # id set but unresolvable AND more than one Volume attached — the dangerous case: an operator
    # who believes they pinned the device. Do NOT fall back to a guess (which today would readlink
    # the lexically-first symlink and mkfs.ext4 it if unformatted).
    err "HETZNER_VOLUME_ID=${HETZNER_VOLUME_ID} does not resolve to an attached Volume"
    err "(/dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID} is absent) and ${_vol_count} Volumes are"
    err "attached — refusing to fall back to a guess. Set HETZNER_VOLUME_ID to the digits after"
    err "scsi-0HC_Volume_ for the intended Volume and re-run."
    exit 1
  else
    # Exactly one (or zero) Volume attached — unambiguous, keep the warn-then-fall-back behaviour.
    err "HETZNER_VOLUME_ID=${HETZNER_VOLUME_ID} is set but /dev/disk/by-id/scsi-0HC_Volume_${HETZNER_VOLUME_ID} does not exist (typo, stale id, or the Volume is not attached) — falling back to the single attached Volume / /dev/sdb."
  fi
elif [ "${_vol_count}" -gt 1 ]; then
  err "${_vol_count} Hetzner Volumes are attached and HETZNER_VOLUME_ID is not set — refusing to guess"
  err "which one holds ${MOUNT_POINT}. Export HETZNER_VOLUME_ID=<id> (the digits after"
  err "scsi-0HC_Volume_) and re-run."
  exit 1
fi
if [ -z "${VOLUME_LINK}" ] && [ "${_vol_count}" -ge 1 ]; then
  VOLUME_LINK="${_vol_links[0]}"
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
    # skillars-deferred-89 AC9(a): the staging branch below only runs when provision.sh performs the
    # mount itself. If the Volume was mounted OUTSIDE this script (manual mount, or an /etc/fstab
    # entry) before provisioning ever ran, any pre-Volume data/ tree is now shadowed under it and was
    # never staged. (A prior provision.sh run instead leaves a verified duplicate safely on the
    # Volume — not data loss.) findmnt/lsblk cannot see content beneath a mount; only an unmount can.
    log "  If this host was NOT first provisioned by provision.sh before the Volume was attached, verify no pre-Volume data is shadowed under ${MOUNT_POINT} — see docs/deployment/first-time-setup.md, 'Reclaim shadowed pre-Volume data'."
  else
    # Stage any pre-Volume payload on the root disk BEFORE the mount hides it. ${STAGING} is a
    # sibling of ${MOUNT_POINT}, never under it, so it survives the mount and a later re-run.
    if pre_volume_payload_present "${MOUNT_POINT}"; then
      log "Pre-Volume data present on the root disk at ${MOUNT_POINT}; staging to ${STAGING} before mount."
      # Free-space guard (skillars-deferred-88 AC5): the staging copy lands on the SAME root
      # filesystem as ${MOUNT_POINT}. Refuse to start it if the root disk cannot hold a second copy
      # plus rsync temp files (2x headroom) — otherwise ENOSPC aborts mid-rsync and every re-run
      # loops on the same failure. Fail-OPEN on an unreadable du/df (|| true per the set -euo
      # pipefail convention, skillars-deferred-85 AC3/AC6 precedent): the guard is best-effort; a
      # genuine ENOSPC during the rsync still surfaces.
      _payload_kb="$(du -sk "${MOUNT_POINT}" 2>/dev/null | awk '{print $1}' || true)"
      _avail_kb="$(df -Pk "${DEPLOY_ROOT}" 2>/dev/null | awk 'NR==2 {print $4}' || true)"
      # Numeric-validate before the arithmetic — a non-empty-but-non-numeric reading would make the
      # $(( )) below error out under `set -euo pipefail` and abort BEFORE mount (the exact re-run
      # loop this guard exists to prevent). Blank it out so the fail-OPEN branch handles it.
      case "${_payload_kb}" in ''|*[!0-9]*) _payload_kb="" ;; esac
      case "${_avail_kb}" in ''|*[!0-9]*) _avail_kb="" ;; esac
      if [ -n "${_payload_kb}" ] && [ -n "${_avail_kb}" ]; then
        if [ "$(( _payload_kb * 2 ))" -gt "${_avail_kb}" ]; then
          err "Refusing to stage the pre-Volume tree: it is ${_payload_kb} KiB and only ${_avail_kb} KiB"
          err "is free on the filesystem holding ${DEPLOY_ROOT} (need ~2x for the copy + rsync temp"
          err "files). Free space on the root disk, or attach the Volume before the tree grows, then re-run."
          exit 1
        fi
      else
        log "⚠️  Could not read du/df for the free-space pre-check — skipping it; a genuine ENOSPC"
        log "    during the staging rsync will still abort the run."
      fi
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
  # Match ONLY an active, non-comment line for ${MOUNT_POINT} whose device field is a real token
  # (first non-whitespace char is not `#`): delete an operator's stale non-canonical mount line,
  # never their commented-out alternate entry (skillars-deferred-88 AC4). One regex, used
  # identically in the guard and the sed address so they cannot diverge. `,` is the sed address
  # delimiter — `#` can't be (it appears inside the bracket expression) and `/` can't be (it is in
  # ${MOUNT_POINT}). The sed also deletes the canonical FSTAB_ENTRY if present; the add-block just
  # below re-adds it, so the net effect stays idempotent.
  _fstab_stale_re="^[[:space:]]*[^#[:space:]][^[:space:]]*[[:space:]]+${MOUNT_POINT}[[:space:]]"
  if grep -vFx "${FSTAB_ENTRY}" /etc/fstab | grep -qE "${_fstab_stale_re}"; then
    # Back up /etc/fstab ONLY on the run that actually mutates it (inside this `if`, not before it)
    # so an idempotent steady-state re-run does not drop a fresh /etc/fstab.bak.<ts> every time.
    _fstab_bak="/etc/fstab.bak.$(date +%Y%m%d%H%M%S)"
    cp -p /etc/fstab "${_fstab_bak}"
    log "Backed up /etc/fstab to ${_fstab_bak} before editing it."
    sed -i -E "\\,${_fstab_stale_re},d" /etc/fstab
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
# skillars-deferred-88 AC7: after the sanity checks below, render_grafana_contact_points rewrites
# the marked `contactPoints:` region of grafana-alerts.yml so it carries ONLY the receivers whose
# channel is actually configured in .env — a single-channel deployment no longer also provisions a
# second receiver with an empty target that silently drops every alert routed to it.
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

# skillars-deferred-88 AC7 — rewrite the `# >>> BEGIN … >>>` / `# <<< END … <<<` region of
# grafana-alerts.yml so `notify-ops` carries only the receivers whose channel is set in .env.
# Preconditions: at least one of GF_EMAIL / GF_SLACK is non-empty (the both-blank case has already
# exited 1 above). The ${GF_*} placeholders are emitted verbatim (single-quoted echo / quoted
# heredoc) — Grafana expands them from its own env; the real secrets never touch this file. Slack's
# Go-template title/text lines are copied byte-for-byte from the committed default via a quoted
# heredoc so the shell never touches `{{ … }}`. Idempotent: an unchanged region is a no-op with no
# backup; a changed region gets a timestamped .bak first (AC4's spirit).
render_grafana_contact_points() {
  local file="${DEPLOY_ROOT}/deploy/lgtm/grafana-alerts.yml"
  local begin='# >>> BEGIN provision.sh-managed contactPoints'
  local end='# <<< END provision.sh-managed contactPoints'

  if [ ! -f "${file}" ]; then
    log "⚠️  ${file} not found — skipping contactPoints rendering."
    return 0
  fi
  # Anchor at column 1 — the awk splice below only matches `index($0, …) == 1`, so an indented or
  # duplicated marker that a substring grep would accept must NOT pass this guard.
  if ! grep -qE '^# >>> BEGIN provision\.sh-managed contactPoints' "${file}" \
     || ! grep -qE '^# <<< END provision\.sh-managed contactPoints' "${file}"; then
    log "⚠️  ${file} has no column-1 provision.sh-managed contactPoints markers — leaving it untouched."
    return 0
  fi

  local region_file new_file
  region_file="$(mktemp)"
  new_file="$(mktemp)"
  # The single-quoted ${GF_*} / {{ … }} strings below are literal ON PURPOSE — Grafana, not the
  # shell, expands them. SC2016 would flag every one.
  # shellcheck disable=SC2016
  {
    echo "${begin} (skillars-deferred-88 AC7) >>>"
    echo '# Do NOT hand-edit between these markers — provision.sh rewrites this region from the alert'
    echo '# channels set in /opt/skillars/.env on every run. ${GF_*} placeholders are kept verbatim;'
    echo '# Grafana expands them from its own container env, so no secret is ever written here.'
    echo 'contactPoints:'
    echo '  - orgId: 1'
    echo '    name: notify-ops'
    echo '    receivers:'
    if [ -n "${GF_EMAIL}" ]; then
      echo '      - uid: notify-ops-email'
      echo '        type: email'
      echo '        settings:'
      echo '          addresses: "${GF_ALERT_NOTIFY_EMAIL}"'
      echo '          singleEmail: false'
    fi
    if [ -n "${GF_SLACK}" ]; then
      cat <<'SLACK_RECEIVER'
      - uid: notify-ops-slack
        type: slack
        settings:
          url: "${GF_SLACK_WEBHOOK_URL}"
          username: "Skillars Alerts"
          icon_emoji: ":rotating_light:"
          title: "{{ len .Alerts.Firing }} alert(s) firing"
          text: "{{ range .Alerts.Firing }}*{{ .Labels.alertname }}* — {{ .Annotations.summary }}\n{{ end }}"
SLACK_RECEIVER
    fi
    echo "${end} <<<"
  } > "${region_file}"

  # Splice: replace everything between the BEGIN and END marker lines (inclusive) with region_file,
  # which itself carries the marker lines.
  awk -v rf="${region_file}" '
    BEGIN { region = ""; while ((getline line < rf) > 0) region = region line "\n" }
    index($0, "# >>> BEGIN provision.sh-managed contactPoints") == 1 { printf "%s", region; inblock = 1; next }
    index($0, "# <<< END provision.sh-managed contactPoints") == 1 { inblock = 0; next }
    !inblock { print }
  ' "${file}" > "${new_file}"

  # Splice sanity: the output must carry exactly one BEGIN and one END marker line (column 1). A
  # column-1-mangled END would otherwise let the splice swallow everything to EOF (the policies:
  # block); bail without touching the live file if that happened.
  if [ "$(grep -cE '^# >>> BEGIN provision\.sh-managed contactPoints' "${new_file}")" != "1" ] \
     || [ "$(grep -cE '^# <<< END provision\.sh-managed contactPoints' "${new_file}")" != "1" ]; then
    err "grafana-alerts.yml contactPoints splice produced an unexpected marker count — leaving the file untouched."
    rm -f "${region_file}" "${new_file}"
    return 0
  fi

  if cmp -s "${file}" "${new_file}"; then
    log "grafana-alerts.yml contactPoints already match the configured channels — no change."
  else
    local bak
    bak="${file}.bak.$(date +%Y%m%d%H%M%S)"
    cp -p "${file}" "${bak}"
    cat "${new_file}" > "${file}"   # cat > keeps the file's inode + permissions
    log "Rewrote grafana-alerts.yml contactPoints from the configured channels (backup: ${bak})."
    log "    Grafana reads this file only at container start — run 'docker compose up -d --force-recreate grafana' for it to take effect."
  fi
  rm -f "${region_file}" "${new_file}"
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
    log "ℹ️  GF_SLACK_WEBHOOK_URL is blank — provisioning notify-ops with the email receiver only"
    log "    (the Slack receiver is omitted, not provisioned empty)."
  fi
  if [ -z "${GF_EMAIL}" ] && [ -n "${GF_SLACK}" ]; then
    log "ℹ️  GF_ALERT_NOTIFY_EMAIL is blank — provisioning notify-ops with the Slack receiver only"
    log "    (the email receiver is omitted, not provisioned empty)."
  fi

  if [ -n "${GF_EMAIL}" ] && [ "${GF_SMTP}" != "true" ]; then
    log "⚠️  GF_ALERT_NOTIFY_EMAIL is set but GF_SMTP_ENABLED is not 'true' — Grafana email routing"
    log "    also needs GF_SMTP_ENABLED=true and the GF_SMTP_* block, or email alerts silently fail."
  fi

  render_grafana_contact_points
fi

log ""
log "✅ Provisioning complete."
log "   Next steps:"
log "   1. Place ${DEPLOY_ROOT}/.env (re-run this script to auto-enforce mode 600)"
log "   2. Run deploy/firewall/apply-firewall.sh from your local machine"
log "   3. Deploy services: cd ${DEPLOY_ROOT} && docker compose up -d"
log "   4. (After placing .env) Install backup crons: bash ${DEPLOY_ROOT}/deploy/backup/install-crons.sh"
