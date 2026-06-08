# Story Deploy-1.5: First-Time Setup Documentation

Status: done

## Story

As a developer arriving at the project for the first time with SSH access, a secrets file, and a domain name,
I want a self-contained first-time setup guide, a complete secrets reference, and DNS configuration instructions all in `docs/deployment`,
so that I can reach a live, TLS-enabled production environment within 2 hours without asking anyone for help.

## Acceptance Criteria

**AC-1: `docs/deployment/first-time-setup.md` covers the complete end-to-end path**
- Given `docs/deployment/first-time-setup.md` exists
- When a developer with zero prior context follows it end-to-end (domain, DNS, provisioning, secrets placement, initial deploy, verification)
- Then the application is reachable over HTTPS at the configured domain with a valid certificate and a passing `/actuator/health` response — within the ≤ 2-hour target
- And no step requires the developer to open any resource outside the repository

**AC-2: DNS configuration section is complete and self-contained**
- Given `docs/deployment/first-time-setup.md` includes a DNS configuration section
- When the developer follows the DNS section
- Then they can configure a domain A record pointing to the Node IP, verify DNS propagation, and understand how long to wait before troubleshooting TLS issuance — using only the guide
- And the guide warns explicitly that Traefik's ACME HTTP-01 challenge requires DNS to be propagated before the stack is first started

**AC-3: `docs/deployment/secrets-reference.md` is complete and contains no secret values**
- Given `docs/deployment/secrets-reference.md` exists
- When a developer with no prior context reads it
- Then every secret required by the application and CI/CD pipeline is listed with its name, format, placement (GitHub Actions secrets vs. server `.env`), and how to generate or obtain it
- And no secret value appears anywhere in the document

---

## Tasks / Subtasks

- [x] Task 1: Create `docs/deployment/first-time-setup.md` (AC-1, AC-2)
  - [x] Prerequisites section: list local tooling requirements (`hcloud` CLI, `openssl`, Hetzner account, SSH key, domain)
  - [x] Step 1: Server and Volume creation (Hetzner Console, CX32, Ubuntu 22.04, 100GB Volume at `/dev/sdb`)
  - [x] Step 2: DNS configuration — create two A records (`DOMAIN`, `MONITORING_DOMAIN`), verify with `dig`, warn about ACME HTTP-01 dependency
  - [x] Step 3: Provision the server — SSH to Node, clone repo, run `deploy/provision.sh`; summarize what the script does
  - [x] Step 4: Apply the firewall — run `deploy/firewall/apply-firewall.sh` from local machine with env vars
  - [x] Step 5: Prepare secrets — copy `.env.example`, fill values, SCP to Node, enforce mode 600 via provision.sh re-run
  - [x] Step 6: Deploy the stack — `docker compose up -d`, watch `docker compose ps`; note app startup timing
  - [x] Step 7: Verify — `curl https://<DOMAIN>/actuator/health`; full service health reference table
  - [x] Troubleshooting section — TLS certificate not issued, app unhealthy, Volume not mounted, SSH lockout, port conflicts

- [x] Task 2: Create `docs/deployment/secrets-reference.md` (AC-3)
  - [x] Server `.env` table: all variables with format, placement, and generation commands (no secret values)
  - [x] GitHub Actions secrets table: `GHCR_PAT`, `SSH_DEPLOY_KEY`, `SSH_HOST`, `SSH_USER`
  - [x] Quick-reference section with `openssl rand` and `ssh-keygen` generation commands

- [x] Task 3: Update `deploy/firewall/README.md` to reference `docs/deployment/first-time-setup.md`
  - [x] Add pointer at end of README: "Full usage documentation: `docs/deployment/first-time-setup.md`"

- [x] Task 4: Update `deploy/traefik/README.md` to reflect that `acme.json` creation is automated by `provision.sh` (Story 1.4)
  - [x] Replace the manual creation step with a note that `provision.sh` handles it automatically
  - [x] Keep the manual fallback command in case the file needs to be recreated
  - [x] Add pointer at end of README: "Full usage documentation: `docs/deployment/first-time-setup.md`"

---

## Dev Notes

### This story is documentation-only — no Java code changes

All work is Markdown files and minor README updates. No application code, no Docker Compose changes, no provisioning script changes.

**Files to CREATE:**
- `docs/deployment/first-time-setup.md` — main deliverable (AC-1, AC-2)
- `docs/deployment/secrets-reference.md` — secrets reference (AC-3)

**Files to MODIFY:**
- `deploy/firewall/README.md` — add reference to first-time-setup.md
- `deploy/traefik/README.md` — update acme.json step to reflect Story 1.4 automation; add reference to first-time-setup.md

---

### Key Implementation Details

#### DNS Section Critical Warning

The first-time setup guide must prominently warn that **DNS must be fully propagated before starting the stack**. Traefik uses the Let's Encrypt HTTP-01 challenge — if port 80 at `DOMAIN` does not resolve to the Node IP when the stack first starts, no TLS certificate will be issued. The guide recommends starting DNS configuration (Step 2) in parallel with provisioning (Step 3) to avoid waiting.

Propagation verification command:
```bash
dig +short <DOMAIN> @8.8.8.8              # must return <NODE_IP>
dig +short <MONITORING_DOMAIN> @8.8.8.8   # must return <NODE_IP>
```

Typical wait: 5–30 minutes for most registrars; allow up to 24 hours worst case.

#### Server and Volume Setup (Step 1)

- Server type: **CX32** (4 vCPU, 8 GB RAM) — named `skillars-prod` by default (the firewall script uses this name via `HCLOUD_SERVER_NAME`)
- OS: **Ubuntu 22.04 LTS**
- Volume: **100 GB** — the provisioning script expects the device at `/dev/sdb` and mounts it at `/opt/skillars/data`
- PostgreSQL, Prometheus, Loki, Tempo, and Grafana data all live on this Volume

#### Provisioning Script Summary (Step 3)

`provision.sh` steps (all idempotent):
1. System packages: Docker Engine, Docker Compose plugin, fail2ban, ufw
2. SSH hardening: `PasswordAuthentication no`, `PermitRootLogin prohibit-password` drop-in
3. fail2ban: sshd jail, maxretry=5, bantime=3600s
4. Directory structure: `/opt/skillars/data/postgres`, `/opt/skillars/lgtm`, `/opt/skillars/traefik`
5.5. Security file permissions: creates `acme.json` with mode 600 (automated in Story 1.4); enforces mode 600 on `.env` if present
6. Volume mount: `/dev/sdb` → `/opt/skillars/data` (logs warning and skips if Volume not attached)

#### Firewall Script (Step 4)

Run from local machine (not the Node) after provisioning:
```bash
export HCLOUD_TOKEN=<your-hetzner-api-token>
export SSH_ALLOWLIST_IP=<your-public-ip>   # without /32
bash deploy/firewall/apply-firewall.sh
```

Optional overrides: `HCLOUD_SERVER_NAME=skillars-prod`, `FIREWALL_NAME=skillars-prod-fw`.

After firewall is applied: port 22 restricted to `SSH_ALLOWLIST_IP/32`; ports 80 and 443 open to all.

#### Secrets Placement (Step 5)

1. `cp .env.example .env` (local machine)
2. Fill all values (reference `docs/deployment/secrets-reference.md`)
3. Verify gitignore: `git check-ignore -v .env` must print a line
4. `scp .env root@<NODE_IP>:/opt/skillars/.env`
5. Re-run `provision.sh` to auto-enforce mode 600 (or `chmod 600` manually)

#### App Startup Timing (Step 6)

- Most services reach `healthy` within ~60 seconds
- The `app` container may take up to **120 seconds** on first start — Docker waits 60 seconds before the first health check, then the app needs additional time to complete database migrations
- Check logs if still `starting` after 2 minutes: `docker compose logs app --tail=50`

#### Two DNS Records Required

The guide creates two A records:
1. `DOMAIN` (e.g. `api.example.com`) — for the application
2. `MONITORING_DOMAIN` (e.g. `monitoring.api.example.com`) — for Grafana

Both must resolve before starting the stack.

#### Secrets Reference: Variable List

| Variable | Format | Notes |
|---|---|---|
| `APP_IMAGE` | `ghcr.io/<org>/javatemplate:sha-<commit>` | Produced by CI pipeline after merge to `main` |
| `DOMAIN` | FQDN | Must have A record before first deploy |
| `LETSENCRYPT_EMAIL` | Email | Used by Let's Encrypt for renewal notifications |
| `POSTGRES_DB` | Alphanumeric | Default: `skillars` |
| `POSTGRES_USER` | Alphanumeric | Default: `skillars` |
| `POSTGRES_PASSWORD` | 32+ char random | `openssl rand -base64 32` |
| `SPRING_DATASOURCE_URL` | JDBC URL | Fixed: `jdbc:postgresql://postgres:5432/${POSTGRES_DB}?TimeZone=UTC` |
| `JWT_SECRET` | 64+ char random | `openssl rand -base64 64` |
| `SPRING_MAIL_HOST` | SMTP hostname | e.g. `smtp.gmail.com` |
| `SPRING_MAIL_PORT` | Integer | 587 for STARTTLS, 465 for SSL/TLS |
| `SPRING_MAIL_USERNAME` | Email | SMTP username or sending address |
| `SPRING_MAIL_PASSWORD` | String | App password or SMTP credential |
| `BUNNY_API_KEY` | Hex string | Bunny.net Dashboard → Account → API |
| `BUNNY_LIBRARY_ID` | Integer | Bunny.net Dashboard → Stream → Library ID |
| `BUNNY_CDN_HOSTNAME` | Hostname | Bunny.net pull zone hostname |
| `MONITORING_DOMAIN` | FQDN | Subdomain for Grafana |
| `GF_SECURITY_ADMIN_USER` | Alphanumeric | Grafana admin username |
| `GF_SECURITY_ADMIN_PASSWORD` | 24+ char random | `openssl rand -base64 24` |
| `LOKI_URL` | Internal URL | Fixed: `http://loki:3100` |
| `LOKI_ENABLED` | Boolean | Fixed: `true` |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | Internal URL | Fixed: `http://tempo:4318/v1/traces` |

GitHub Actions secrets (for Epic 2 CI/CD):

| Secret | Format | Notes |
|---|---|---|
| `GHCR_PAT` | GitHub PAT | `write:packages` scope; pushes images to GHCR |
| `SSH_DEPLOY_KEY` | PEM private key (ed25519) | `ssh-keygen -t ed25519 -C deploy@skillars-prod`; public key → Node `authorized_keys` |
| `SSH_HOST` | IP address | Node public IP |
| `SSH_USER` | String | SSH username on Node (default: `root`) |

---

### Traefik README: acme.json section

Story 1.4 promoted `acme.json` creation from a manual pre-step to an automated, idempotent step in `provision.sh`. The Traefik README must be updated to reflect this — otherwise new developers will follow the old manual instructions unnecessarily. The manual command should remain as a fallback only.

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 1.5 — Acceptance Criteria]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-3 — DNS setup documentation]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-21 — First-time setup guide]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-25 — Secrets reference]
- [Source: _bmad-output/implementation-artifacts/deploy-1-4-security-hardening.md#Task 1 — acme.json automation in provision.sh]
- [Source: deploy/provision.sh — Script structure and idempotency]
- [Source: deploy/firewall/apply-firewall.sh — Firewall script usage]
- [Source: deploy/traefik/traefik.yml — Traefik configuration]
- [Source: .env.example — Variable list and placeholder generation commands]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No debug issues encountered._

### Completion Notes List

- Task 1: Created `docs/deployment/first-time-setup.md` — 7-step guide covering prerequisites, Hetzner server/volume creation, DNS configuration (with ACME warning), provisioning, firewall, secrets placement, stack deploy, and verification. Full service health reference table and troubleshooting section included.
- Task 2: Created `docs/deployment/secrets-reference.md` — all server `.env` variables and GitHub Actions secrets listed with format, placement, and generation commands. No secret values included.
- Task 3: Updated `deploy/firewall/README.md` — added "Full usage documentation" pointer to first-time-setup.md.
- Task 4: Updated `deploy/traefik/README.md` — updated `acme.json` section to reflect Story 1.4 automation; kept manual fallback command; added pointer to first-time-setup.md.

### File List

- docs/deployment/first-time-setup.md (created)
- docs/deployment/secrets-reference.md (created)
- deploy/firewall/README.md (modified)
- deploy/traefik/README.md (modified)

### Change Log

- 2026-06-03: Created first-time-setup.md and secrets-reference.md; updated firewall and Traefik READMEs. Story complete.

---

### Review Findings

_Code review performed 2026-06-04. 13 patches applied, 1 dismissed as false positive (P12 — BUNNY_API_KEY path already correct in file), 14 deferred, 6 dismissed._

#### Patch Items

- [x] [Review][Patch] `chmod 700` dropped from Traefik manual fallback — restored in fallback one-liner [`deploy/traefik/README.md`]
- [x] [Review][Patch] No warning to verify key-based SSH login before running `provision.sh` — added pre-Step 3 warning blockquote [`docs/deployment/first-time-setup.md`, Step 3]
- [x] [Review][Patch] No guidance on determining egress IP or pre-firewall SSH key check — added `curl ifconfig.me` and second-terminal SSH verify instructions [`docs/deployment/first-time-setup.md`, Step 4]
- [x] [Review][Patch] `SPRING_DATASOURCE_URL` interpolation caveat missing — updated to show literal `<POSTGRES_DB>` placeholder with expansion note [`docs/deployment/secrets-reference.md`]
- [x] [Review][Patch] `pg_isready` health check hardcodes `postgres` user and `skillars` database — updated to `<POSTGRES_USER>` / `<POSTGRES_DB>` placeholders with note [`docs/deployment/first-time-setup.md`, Step 7]
- [x] [Review][Patch] `docker compose exec` / `docker compose logs` commands lack working directory context — added "run from `/opt/skillars`" note in Step 7 and Troubleshooting [`docs/deployment/first-time-setup.md`]
- [x] [Review][Patch] Let's Encrypt rate limit not documented — added rate limit warning to Troubleshooting TLS section [`docs/deployment/first-time-setup.md`, Troubleshooting]
- [x] [Review][Patch] `APP_IMAGE` pre-deploy build instructions absent — added manual GHCR login/build/push commands [`docs/deployment/secrets-reference.md`]
- [x] [Review][Patch] `<REPO_URL>` placeholder unresolved — added inline comment pointing to GitHub URL format [`docs/deployment/first-time-setup.md`, Step 3]
- [x] [Review][Patch] ACME warning only references `DOMAIN` — updated to mention both `DOMAIN` and `MONITORING_DOMAIN` [`docs/deployment/first-time-setup.md`, Step 2]
- [x] [Review][Patch] Local repo clone on developer's machine assumed but never stated — added to prerequisites table [`docs/deployment/first-time-setup.md`, Prerequisites]
- [x] [Review][Patch] `BUNNY_API_KEY` navigation path — false positive; file already had `Bunny.net Dashboard → Account → API` (dismissed)
- [x] [Review][Patch] Preamble false claim "All required resources are inside this repository" — softened to "All deployment instructions are contained in this repository" [`docs/deployment/first-time-setup.md`]
- [x] [Review][Patch] No `lsblk` device verification step — added `lsblk` check note in Step 1 Volume attachment [`docs/deployment/first-time-setup.md`, Step 1]

#### Deferred Items

- [x] [Review][Defer] [`docs/deployment/first-time-setup.md`] Repo cloned to `/opt/skillars` before volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile [`deploy/provision.sh`] — deferred, pre-existing provision.sh architecture
- [x] [Review][Defer] [`docs/deployment/first-time-setup.md`] `acme.json` lives on root disk — server rebuild loses all certificates; Let's Encrypt rate limits make reissuance painful; no backup guidance — deferred, pre-existing architecture decision
- [x] [Review][Defer] [`deploy/provision.sh`] `ufw` installed but never enabled — Hetzner firewall is sole protection; no host-level fallback — deferred, pre-existing provision.sh issue
- [x] [Review][Defer] [`docker-compose.yml`] Redis data on named Docker volume (root disk), not Hetzner Volume — session/cache data lost on server rebuild — deferred, pre-existing architecture decision
- [x] [Review][Defer] No outbound firewall rules — observability containers have unrestricted internet access; security hardening beyond this story's scope — deferred, enhancement
- [x] [Review][Defer] Docker Hub rate limit guidance absent — shared Hetzner egress IPs can hit unauthenticated pull limits — deferred, rare edge case
- [x] [Review][Defer] Partial `provision.sh` failure leaves system in unknown state — `set -euo pipefail` exits on first error; re-run may skip broken install blocks — deferred, pre-existing provision.sh issue
- [x] [Review][Defer] No rollback procedure for failed deploy (bad `APP_IMAGE` with Flyway migration already run) — deferred, operational concern out of scope
- [x] [Review][Defer] Loki (720h), Tempo (336h), Prometheus (15d) retention periods inconsistent and undocumented — no disk sizing guidance — deferred, observability config
- [x] [Review][Defer] `docker-compose-lgtm.yaml` (dev-only, anonymous auth) not warned against production use — deferred, pre-existing dev artifact
- [x] [Review][Defer] No secret rotation documentation (PostgreSQL password, JWT secret, Grafana password) — deferred, operational concern
- [x] [Review][Defer] JWT_SECRET minimum length stated but algorithm and Spring enforcement not documented — deferred, application implementation
- [x] [Review][Defer] Grafana admin initial login not explicitly verified as part of Step 7 completion — deferred, low priority
- [x] [Review][Defer] `provision.sh` re-run while stack is live may affect data directory ownership — chown -R runs over live mounts — deferred, maintenance concern
