#!/usr/bin/env bash
# Apply Hetzner Cloud Firewall for skillars-prod via hcloud CLI.
# Run from your LOCAL machine (not the server) with HCLOUD_TOKEN set.
#
# Required env vars:
#   HCLOUD_TOKEN        — Hetzner Cloud API token
#   SSH_ALLOWLIST_IP    — Your IP address (CIDR /32 appended automatically)
#
# Optional env vars:
#   HCLOUD_SERVER_NAME  — Server name to attach firewall to (default: skillars-prod)
#   FIREWALL_NAME       — Firewall name (default: skillars-prod-fw)
set -euo pipefail

FIREWALL_NAME="${FIREWALL_NAME:-skillars-prod-fw}"
SERVER_NAME="${HCLOUD_SERVER_NAME:-skillars-prod}"

# ── Validate required inputs ──────────────────────
if [ -z "${HCLOUD_TOKEN:-}" ]; then
  echo "ERROR: HCLOUD_TOKEN is not set." >&2
  exit 1
fi

if [ -z "${SSH_ALLOWLIST_IP:-}" ]; then
  echo "ERROR: SSH_ALLOWLIST_IP is not set." >&2
  echo "  Example: SSH_ALLOWLIST_IP=203.0.113.10 $0" >&2
  exit 1
fi

if ! command -v hcloud &>/dev/null; then
  echo "ERROR: hcloud CLI not found. Install with: brew install hcloud" >&2
  exit 1
fi

SSH_CIDR="${SSH_ALLOWLIST_IP}/32"
echo "[firewall] SSH access will be restricted to: ${SSH_CIDR}"

# ── Create or update firewall ─────────────────────
if hcloud firewall list -o columns=name | grep -qx "${FIREWALL_NAME}"; then
  echo "[firewall] Firewall '${FIREWALL_NAME}' already exists — updating rules..."

  # Delete all existing rules first to prevent duplicates
  hcloud firewall delete-rule "${FIREWALL_NAME}" \
    --direction in --protocol tcp --port 80  --source-ips 0.0.0.0/0 --source-ips ::/0 2>/dev/null || true
  hcloud firewall delete-rule "${FIREWALL_NAME}" \
    --direction in --protocol tcp --port 443 --source-ips 0.0.0.0/0 --source-ips ::/0 2>/dev/null || true
  hcloud firewall delete-rule "${FIREWALL_NAME}" \
    --direction in --protocol tcp --port 22  --source-ips 0.0.0.0/0 2>/dev/null || true
else
  echo "[firewall] Creating firewall '${FIREWALL_NAME}'..."
  hcloud firewall create --name "${FIREWALL_NAME}"
fi

echo "[firewall] Applying firewall rules..."

# Inbound TCP 80 — public HTTP
hcloud firewall add-rule "${FIREWALL_NAME}" \
  --direction in \
  --protocol tcp \
  --port 80 \
  --source-ips 0.0.0.0/0 \
  --source-ips ::/0

# Inbound TCP 443 — public HTTPS
hcloud firewall add-rule "${FIREWALL_NAME}" \
  --direction in \
  --protocol tcp \
  --port 443 \
  --source-ips 0.0.0.0/0 \
  --source-ips ::/0

# Inbound TCP 22 — SSH restricted to allowlisted IP only
hcloud firewall add-rule "${FIREWALL_NAME}" \
  --direction in \
  --protocol tcp \
  --port 22 \
  --source-ips "${SSH_CIDR}"

echo "[firewall] Rules applied: TCP 80 (all), TCP 443 (all), TCP 22 (${SSH_CIDR})"

# ── Attach to server ──────────────────────────────
if hcloud server list -o columns=name | grep -qx "${SERVER_NAME}"; then
  echo "[firewall] Attaching '${FIREWALL_NAME}' to server '${SERVER_NAME}'..."
  hcloud firewall apply-to-server \
    --server "${SERVER_NAME}" \
    "${FIREWALL_NAME}"
  echo "[firewall] ✅ Firewall '${FIREWALL_NAME}' attached to '${SERVER_NAME}'."
else
  echo "[firewall] ⚠️  Server '${SERVER_NAME}' not found — firewall created but NOT attached."
  echo "    Set HCLOUD_SERVER_NAME=<your-server-name> and re-run to attach."
fi

echo ""
echo "✅ Firewall configuration complete."
echo "   Firewall: ${FIREWALL_NAME}"
echo "   SSH allowed from: ${SSH_CIDR}"
