# Story Deploy-1.1: Server Provisioning Script & IaC Firewall

Status: done

## Story

As a developer inheriting the project with a fresh Hetzner CX32 node,
I want an executable provisioning script and a version-controlled Hetzner Cloud Firewall definition,
So that I can take a raw server to a Docker-ready, SSH-hardened, network-secured state without improvising and without firewall configuration drifting from the intended state.

## Acceptance Criteria

**AC-1: Provisioning script installs all system dependencies**
- Given a fresh Hetzner CX32 node (Ubuntu 22.04 LTS)
- When a developer runs the provisioning script as root (or via sudo)
- Then all required system packages are installed: `curl`, `git`, `unzip`, `fail2ban`, `ufw` (if not using Hetzner Cloud Firewall only), and any other declared dependencies
- And Docker Engine (latest stable) and Docker Compose plugin v2 (`docker compose` not `docker-compose`) are installed via the official Docker APT repo
- And `docker compose version` returns a v2.x version

**AC-2: SSH hardening is applied**
- Given the provisioning script has been run
- When SSH daemon configuration is inspected
- Then `PasswordAuthentication no` is set in `/etc/ssh/sshd_config` (or a drop-in under `/etc/ssh/sshd_config.d/`)
- And the sshd service is reloaded/restarted to apply the change
- And `fail2ban` is active (`systemctl is-active fail2ban` returns `active`) with a `[sshd]` jail configured

**AC-3: Directory structure is prepared**
- Given the provisioning script has been run
- When the filesystem is inspected
- Then the deployment directory `/opt/skillars` exists and is owned by a non-root service user (or root, documented in the script)
- And subdirectories `/opt/skillars/data/postgres` and `/opt/skillars/lgtm` exist (for Volume mount and LGTM data)
- And the secrets placeholder path is documented: the server `.env` file must be placed at `/opt/skillars/.env` with mode `600`

**AC-4: Script is idempotent**
- Given the provisioning script has already been run on a node
- When it is run a second time
- Then it completes without error and produces no unintended side effects (e.g., does not reset existing Docker containers, does not duplicate apt sources, does not fail on already-created directories)

**AC-5: IaC firewall is defined and can be applied via hcloud CLI**
- Given the Hetzner firewall configuration file is committed to the repository under `deploy/firewall/`
- When the operator runs `hcloud firewall create --name skillars-prod ...` (or the equivalent apply script), targeting the Hetzner API
- Then inbound TCP 80 is open from all sources
- And inbound TCP 443 is open from all sources
- And inbound TCP 22 is restricted to a single configurable allowlisted IP (passed as a variable or env var — not hardcoded)
- And all other inbound traffic is blocked
- And the firewall can be fully reproduced by running the script — manual Hetzner UI changes are not required

**AC-6: Hetzner Volume is mounted for PostgreSQL**
- Given the Hetzner Volume (100GB) is attached to the Node
- When the provisioning script runs
- Then it prints clear instructions (or auto-mounts) the Volume at `/opt/skillars/data` so that PostgreSQL data lives on the Volume, not on the ephemeral root disk
- And the Volume mount is made persistent via `/etc/fstab` or equivalent

---

## Tasks / Subtasks

- [x] Task 1: Create `deploy/` directory structure in repo root (AC: 1, 5)
  - [x] Create `deploy/provision.sh` — main idempotent provisioning script
  - [x] Create `deploy/firewall/apply-firewall.sh` — hcloud CLI firewall script
  - [x] Create `deploy/firewall/firewall-rules.json` — declarative firewall rules (optional, for hcloud API)
  - [x] Ensure both `.sh` files are executable (`chmod +x`)

- [x] Task 2: Implement `deploy/provision.sh` (AC: 1, 2, 3, 4, 6)
  - [x] Add shebang `#!/usr/bin/env bash` and `set -euo pipefail`
  - [x] Add idempotency guards: check if Docker already installed before re-installing; check if directories exist before `mkdir`
  - [x] Install Docker Engine via official Docker APT repo for Ubuntu (not via `snap` or `apt install docker.io`)
  - [x] Verify Docker Compose v2 plugin is available: `docker compose version` (not standalone `docker-compose` binary)
  - [x] Apply SSH hardening: write `PasswordAuthentication no` to `/etc/ssh/sshd_config.d/99-skillars-hardening.conf`; reload sshd (`systemctl reload ssh`)
  - [x] Install and enable `fail2ban`; write `/etc/fail2ban/jail.d/sshd-skillars.conf` with `[sshd]` jail (`maxretry=5`, `bantime=3600`)
  - [x] Create `/opt/skillars`, `/opt/skillars/data/postgres`, `/opt/skillars/lgtm`, `/opt/skillars/traefik` directories
  - [x] Print reminder: "Place server .env at /opt/skillars/.env with mode 600"
  - [x] Handle Hetzner Volume mount: detect if the Volume device (e.g., `/dev/sdb`) is attached, format if new (`mkfs.ext4`), mount to `/opt/skillars/data`, add to `/etc/fstab` — guard with idempotency check

- [x] Task 3: Implement `deploy/firewall/apply-firewall.sh` (AC: 5)
  - [x] Accept `SSH_ALLOWLIST_IP` as env var (required, no default — fail if unset)
  - [x] Use `hcloud` CLI (Hetzner CLI, not Terraform) — see Dev Notes for rationale
  - [x] Create firewall via `hcloud firewall create --name skillars-prod-fw`
  - [x] Add rules: inbound TCP 80 from `0.0.0.0/0`, inbound TCP 443 from `0.0.0.0/0`, inbound TCP 22 from `$SSH_ALLOWLIST_IP/32`
  - [x] Make script idempotent: if firewall already exists (`hcloud firewall list | grep skillars-prod-fw`), update rules rather than creating duplicate
  - [x] Add `hcloud firewall apply-to-server --server skillars-prod` step at end

- [x] Task 4: Add `.env` to `.gitignore` if not already present (AC: 3)
  - [x] Check `/Users/mokwen/dev/gitrepos/bluegithub/javatemplate/.gitignore` — add `.env` and `/opt/skillars/.env` patterns if missing

- [x] Task 5: Verify script correctness with `shellcheck` (AC: 1–4)
  - [x] Run `shellcheck deploy/provision.sh` and `shellcheck deploy/firewall/apply-firewall.sh`
  - [x] Fix all shellcheck warnings (SC2086, SC2154, etc.)

---

## Dev Notes

### This story is infrastructure-only — no Java code changes

This story creates shell scripts and configuration files only. Do NOT modify any Java source, `pom.xml`, `application*.yaml`, or test files. The only files touched are:
- `deploy/provision.sh` (new)
- `deploy/firewall/apply-firewall.sh` (new)
- `deploy/firewall/firewall-rules.json` (new, optional)
- `.gitignore` (update if `.env` not already listed)

### IaC Tool Decision: hcloud CLI (not Terraform)

The epics and PRD say "e.g., Terraform or hcloud CLI scripting". Use **hcloud CLI**, not Terraform, for the following reasons:
- No Terraform state file required — avoids the state management burden on a single-developer project
- `hcloud` CLI is a single binary, no HCL/provider setup
- Single-node setup does not benefit from Terraform's full IaC graph
- The firewall definition is fully version-controlled as a bash script with inline rules

Install hcloud CLI locally: `brew install hcloud` (macOS) or download from https://github.com/hetznercloud/cli/releases. The `apply-firewall.sh` script is run once from the developer's local machine (not from the server), using `HCLOUD_TOKEN` env var.

### Server OS: Ubuntu 22.04 LTS

Hetzner CX32 defaults to Ubuntu 22.04 LTS. The provisioning script must target Ubuntu 22.04. Use `apt-get` with `-y` flags; do not assume `yum` or `dnf`.

Docker install source:
```bash
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```
This installs `docker compose` (v2 plugin), not the deprecated standalone `docker-compose` v1.

### Hetzner Volume Configuration

The deployment proposal specifies a **100GB Hetzner Volume** for PostgreSQL data (separate from the root SSD). When a Volume is attached to a CX32, it appears as `/dev/sdb` (if no other volumes are attached). The provisioning script must:
1. Check if the Volume device exists
2. If it has no filesystem: `mkfs.ext4 /dev/sdb`
3. Mount to `/opt/skillars/data`
4. Add to `/etc/fstab` for persistence: `/dev/sdb /opt/skillars/data ext4 defaults,nofail 0 2`

**Important:** Use `nofail` in fstab so the server boots even if the Volume is temporarily detached.

Stories 1.2–1.5 will reference `/opt/skillars/data/postgres` as the PostgreSQL data directory bind-mount in `docker-compose.yml`.

### Firewall Rules — Exact Requirements

From the PRD (FR-2) and Additional Requirements:
- TCP 80 inbound: all sources (`0.0.0.0/0`)
- TCP 443 inbound: all sources (`0.0.0.0/0`)
- TCP 22 inbound: allowlisted IP(s) only (operator-provided via `SSH_ALLOWLIST_IP` env var)
- All other inbound: blocked

**The firewall is applied to the Node, not to the Docker services.** Stories 1.2–1.3 will add Docker network isolation (no service ports exposed to host).

### SSH Hardening Details

Write to `/etc/ssh/sshd_config.d/99-skillars-hardening.conf` (drop-in, Ubuntu 22.04 supports this):
```
PasswordAuthentication no
PermitRootLogin prohibit-password
```

This does NOT disable root login entirely (root key login is still needed for initial setup). Just prevents password-based SSH.

fail2ban jail config at `/etc/fail2ban/jail.d/sshd-skillars.conf`:
```ini
[sshd]
enabled = true
port = ssh
filter = sshd
maxretry = 5
bantime = 3600
findtime = 600
```

### Directory Structure Design

```
/opt/skillars/                  ← deployment root (outside repo)
  .env                       ← secrets (mode 600, owner root or service user)
  data/
    postgres/                ← PostgreSQL data (on Hetzner Volume)
  lgtm/                      ← Grafana/Prometheus/Loki/Tempo data
  traefik/
    acme.json                ← Let's Encrypt cert storage (created by Story 1.2)
```

Stories 1.2 and 1.3 will create `docker-compose.yml` with bind mounts referencing these paths.

### Critical Design Decision: Health Endpoint for Smoke Tests (Story 2.2 dependency)

**Flag for Story 1.2 author:** The application's management server runs on port **8367** with base path `/manage` (see `application.yaml` lines 256–261). The health endpoint is currently `http://app:8367/manage/health`, NOT `http://app:9990/actuator/health`.

The epics explicitly require: "Smoke Test and external uptime monitor MUST target `/actuator/health` specifically." This creates a conflict. Story 1.2 (Docker Compose) must resolve it — the recommended approach is to add a Traefik routing rule that maps `https://<domain>/actuator/health` to `http://app:8367/manage/health` internally. Document this decision in the docker-compose.yml comments.

This story (1.1) is NOT responsible for resolving this — but the story author must document it here so Story 1.2 does not miss it.

### `.gitignore` — `.env` Pattern

The current `.gitignore` covers `.env` implicitly via pattern matching. Verify explicitly that `.env` is ignored:
```bash
git check-ignore -v .env
```
If not ignored, add `.env` to `.gitignore`. The server `.env` at `/opt/skillars/.env` is outside the repo root so `.gitignore` does not need to cover it — but the local developer `.env` must be ignored.

### Existing Files NOT to Modify

The following files in the repo root are for the **dev/local LGTM stack** and must NOT be modified by this story:
- `docker-compose-lgtm.yaml` — local dev LGTM stack (separate from production compose)
- `prometheus.yml` — local Prometheus config (scrapes `host.docker.internal:8367`)
- `alerts.yml` — local alert rules
- `grafana-datasources.yml`, `grafana-alerts.yml`, `grafana-dashboard-config.yml`
- `tempo.yml`

Story 1.2 will create a **new** production `docker-compose.yml` at the repo root, and Story 1.3 will create production LGTM configurations. The existing files are development tooling and must remain untouched.

### Project Structure for Deploy Files

There is no existing `deploy/` directory. Create it at the project root:
```
/deploy/
  provision.sh
  firewall/
    apply-firewall.sh
    README.md          ← brief usage note (full docs in Story 1.5)
```

Do not place deployment scripts under `src/` — they are not part of the Java build.

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 1.1]
- [Source: _bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/prd.md#FR-1, FR-2, FR-3, FR-11, FR-12]
- [Source: _bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/addendum.md#Implementation Values]
- [Source: requirements/deployment/deployment-proposal.md#Security Hardening]
- [Source: src/main/resources/application.yaml — management.server.port=8367, management base-path=/manage]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No blocking issues encountered._

### Completion Notes List

- Implemented `deploy/provision.sh`: idempotent Ubuntu 22.04 provisioning covering Docker Engine (official APT repo), Docker Compose v2 plugin, SSH hardening via drop-in config, fail2ban sshd jail, directory structure (`/opt/skillars/{data/postgres,lgtm,traefik}`), and Hetzner Volume auto-mount with fstab persistence.
- Implemented `deploy/firewall/apply-firewall.sh`: hcloud CLI script that creates/updates `skillars-prod-fw` firewall with TCP 80/443 open to all and TCP 22 restricted to `SSH_ALLOWLIST_IP/32`; attaches to `skillars-prod` server; fully idempotent.
- `deploy/firewall/firewall-rules.json` added as a declarative reference document (non-authoritative; `apply-firewall.sh` is authoritative).
- `deploy/firewall/README.md` added with usage instructions.
- `.gitignore` already contained `.env` pattern (line 70) — no change required.
- Both `.sh` files pass `shellcheck 0.11.0` with zero warnings.
- No Java source, `pom.xml`, or application config files were modified.

### File List

- `deploy/provision.sh` (new, executable)
- `deploy/firewall/apply-firewall.sh` (new, executable)
- `deploy/firewall/firewall-rules.json` (new)
- `deploy/firewall/README.md` (new)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (updated: deploy-1-1 → review)
- `_bmad-output/implementation-artifacts/deploy-1-1-server-provisioning-script-iac-firewall.md` (updated: tasks, status, Dev Agent Record)

## Change Log

- 2026-06-03: Story implemented — created deploy/ directory structure, provision.sh, firewall scripts; all shellcheck clean. Status → review.
