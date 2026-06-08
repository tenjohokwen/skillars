# Hetzner Cloud Firewall

Applies the production firewall to the `skillars-prod` server via the `hcloud` CLI.

## Prerequisites

- `hcloud` CLI: `brew install hcloud` (macOS) or download from the [Hetzner CLI releases](https://github.com/hetznercloud/cli/releases)
- A Hetzner Cloud API token with Read & Write permissions

## Usage

```bash
export HCLOUD_TOKEN=<your-api-token>
export SSH_ALLOWLIST_IP=<your-ip-address>   # e.g. 203.0.113.10 (without /32)

./apply-firewall.sh
```

The script is idempotent — running it again updates existing rules rather than creating duplicates.

## What it does

| Rule | Source | Action |
|------|--------|--------|
| TCP 80 inbound | `0.0.0.0/0` | Allow (HTTP → Traefik) |
| TCP 443 inbound | `0.0.0.0/0` | Allow (HTTPS → Traefik) |
| TCP 22 inbound | `$SSH_ALLOWLIST_IP/32` | Allow (SSH) |
| All other inbound | — | Block (Hetzner implicit deny) |

Full usage documentation: `docs/deployment/first-time-setup.md`.
