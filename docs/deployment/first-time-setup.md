# First-Time Setup Guide

Follow this guide end-to-end to bring up a live, TLS-enabled production environment from zero.
**Target time: ≤ 2 hours.**

**Supported providers (as of 2026-09):**
- **netcup VPS 1000 G12** (recommended) — currently Hetzner CX32 is out of supply
- **Hetzner Cloud CX42+** (legacy, tested)

You will need: SSH access to a fresh server, a secrets file, a registered domain, and cloud provider account.
All deployment instructions are contained in this repository.

---

## Prerequisites

Ensure the following are available on your **local machine** before starting:

| Requirement | How to obtain |
|---|---|
| `openssl` | Pre-installed on macOS/Linux; install via `apt install openssl` if absent |
| Cloud provider account | **netcup** (https://www.netcup.eu) or **Hetzner Cloud** (https://console.hetzner.cloud) |
| Provider API credentials (if using Hetzner) | Hetzner Console → Security → API Tokens → Generate API token; for netcup, use account credentials |
| `hcloud` CLI (Hetzner only) | `brew install hcloud` (macOS) or download from [hetznercloud/cli releases](https://github.com/hetznercloud/cli/releases) |
| SSH key uploaded to provider | Upload to your provider's console (Hetzner Console → Security → SSH Keys or netcup control panel) |
| Registered domain with DNS management access | Your domain registrar |
| SSH private key on local machine | The key pair whose public key is uploaded to your cloud provider |
| Local clone of this repository | `git clone https://github.com/tenjohokwen/skillars.git` on your local machine — required for Step 4 (firewall script) and Step 5 (`.env` file) |

---

## Step 1: Create the Server and Storage

**⚠️ Provider Migration Note:** This guide was written for Hetzner Cloud. As of 2026-09, the recommended provider is **netcup VPS 1000 G12** (Hetzner CX32 is out of supply). The general principles below apply to both, but provider-specific steps (Cloud Console, firewall API, volume mounting) differ. Verify netcup equivalents before proceeding.

Choose your provider and follow the equivalent steps:

### Option A: netcup VPS (Recommended as of 2026-09)

1. **Create a VPS**
   - Product: **VPS 1000 G12** (2 vCPU, 4 GB RAM base; scales to 4 vCPU, 8 GB for high-load scenarios)
   - OS: **Ubuntu 22.04 LTS**
   - Name: **`skillars-prod`** or your chosen identifier
   - SSH Keys: upload your public key during provisioning
   - Note the **public IP address** — you will need it for SSH and DNS

2. **Provision additional storage**
   - netcup's standard VPS includes local storage; ensure **≥100 GB** available for `/opt/skillars/data`
   - Alternatively, attach a managed volume if available
   - After provisioning, verify available space: `lsblk` or `df -h`
   - **MinIO:** The stack runs MinIO as a container for object storage (videos, images, documents). Ensure the Volume/storage pool has sufficient capacity for your media library (plan for growth).

### Option B: Hetzner Cloud (Legacy)

1. **Create a server** (in **Hetzner Cloud Console**)
   - Type: **CX42** or larger (4+ vCPU, 8+ GB RAM — CX32 is out of supply)
   - OS: **Ubuntu 22.04 LTS**
   - Name: **`skillars-prod`** — the firewall script uses this name by default
   - SSH Keys: select the key you uploaded in the prerequisites
   - Note the **public IP address** — you will need it for SSH and DNS

2. **Attach a 100 GB Volume**
   - Create or attach immediately after server creation
   - The provisioning script mounts the volume at `/opt/skillars/data` via its stable `/dev/disk/by-id/scsi-0HC_Volume_*` symlink
   - PostgreSQL, Prometheus, Loki, Tempo, Grafana, MinIO, and Traefik certificates all live on this volume
   - After attaching, verify with `lsblk`; the provisioning script picks it up via the `by-id` path

---

### Resource Requirements (Both Providers)

The stack requires:
- **CPU:** ~2–3 vCPU under normal load; 4 vCPU recommended for headroom
- **Memory:** ~6–7 GB reserved by containers; 8 GB minimum for host OS and overhead
- **Storage:** ≥100 GB for database and system; add capacity for MinIO media library (depends on your upload volume)

If running on netcup VPS 1000 G12 base tier (2 vCPU, 4 GB RAM), monitor CPU and memory closely during testing. Upgrade if sustained load exceeds 80% on either metric.

---

## Step 2: Configure DNS

> **You can do this in parallel with Step 3.** DNS propagation takes time; starting early saves waiting later.

Create two **A records** at your domain registrar pointing to the Node IP:

| Record | Type | Value |
|---|---|---|
| `DOMAIN` (e.g. `api.example.com`) | A | `<NODE_IP>` |
| `MONITORING_DOMAIN` (e.g. `monitoring.api.example.com`) | A | `<NODE_IP>` |

Verify propagation from your local machine:

```bash
dig +short <DOMAIN> @8.8.8.8              # must return <NODE_IP>
dig +short <MONITORING_DOMAIN> @8.8.8.8   # must return <NODE_IP>
```

Typical wait: **5–30 minutes** for most registrars; allow up to 24 hours in the worst case.

> **Do NOT start the stack until both records resolve to the Node IP.**
> Traefik uses Let's Encrypt HTTP-01 challenge — it must be reachable on port 80 at **both** `DOMAIN` and `MONITORING_DOMAIN`.
> If either record is not propagated when the stack first starts, the corresponding TLS certificate will not be issued.

---

## Step 3: Provision the Server

SSH to the Node and run the provisioning script:

> **Before running `provision.sh`:** in a second terminal, confirm key-based login works — `ssh root@<NODE_IP>` must connect without a password prompt. The script immediately disables password authentication. If your connection drops before key access is confirmed, you will be locked out with no recovery path except the Hetzner web console.

```bash
ssh root@<NODE_IP>
git clone <REPO_URL> /opt/skillars   # your GitHub repository URL, e.g. https://github.com/<org>/skillars.git
cd /opt/skillars
bash deploy/provision.sh
```

What `provision.sh` does (all steps are idempotent — safe to re-run):

1. Installs system packages: Docker Engine, Docker Compose plugin, fail2ban, ufw
2. Applies SSH hardening: password authentication disabled, root login key-only
3. Configures fail2ban: sshd jail, maxretry=5, bantime=3600s
4. Enables `ufw` (host-level firewall): allows SSH (22), HTTP (80), and HTTPS (443), then sets default-deny-incoming / default-allow-outgoing — SSH is allowed *before* `ufw` is enabled so the active provisioning session is not terminated
5. Creates the base directory structure: `/opt/skillars/data/postgres`, `/opt/skillars/lgtm`
6. Mounts the Hetzner Volume (resolved via its stable `/dev/disk/by-id` path) at `/opt/skillars/data`, then creates the data subdirectories that live on it (`postgres`, `prometheus`, `loki`, `tempo`, `grafana`) with correct ownership
7. **After** the mount: creates `/opt/skillars/data/redis` (owned by uid 999, the redis image's user) and `/opt/skillars/data/traefik/acme.json` with mode 600 (required by Traefik; no manual step needed)

> **The order of 6 and 7 matters and is deliberate.** Both paths live on the Volume, so creating
> them before the mount would write them to the root disk and the mount would then hide them —
> Traefik would start against an empty `acme.json` and silently reissue every certificate.
>
> If the Volume is not attached yet, section 7 of the script logs a warning and skips the mount, but
> still creates the redis directory and `acme.json` — on the root disk, where they work but do not
> survive a rebuild. Attach the Volume in the Hetzner Console and re-run `provision.sh`: the re-run
> mounts the Volume **and migrates the pre-Volume `data/` tree onto it** (TLS certs included),
> staging it through `/opt/skillars/.pre-volume-migration` first. If that re-run is interrupted
> *before* any file has landed on the Volume, the next re-run resumes it automatically; if it is
> interrupted *mid-copy*, the next re-run sees a partially-populated Volume, stops with the
> no-clobber warning below, and leaves `/opt/skillars/.pre-volume-migration` in place for you to
> reconcile by hand. If the Volume you attach already contains data (a re-attach), the script
> likewise does **not** overwrite it — it leaves the staged copy in `/opt/skillars/.pre-volume-migration`
> and logs a reconciliation note. The now-shadowed root-disk copy under the mount can be reclaimed
> manually — see **Reclaim shadowed pre-Volume data** below.

#### Reclaim shadowed pre-Volume data

Whenever `/opt/skillars/data` is a mount and a `data/` tree was written to the **root disk** under
that path before the Volume was mounted, that root-disk copy is now hidden beneath the mount. It is
harmless (it consumes root-disk space but is never read), but to reclaim the space:

1. Stop the stack: `docker compose -f /opt/skillars/docker-compose.yml down`
2. `umount /opt/skillars/data`
3. `rm -rf /opt/skillars/data/*` (this now targets the **root-disk** directory, not the Volume)
4. `mount /opt/skillars/data` (or re-run `provision.sh`)
5. Restart the stack.

This is only needed when the Volume was mounted **outside `provision.sh`** (a manual `mount`, or an
`/etc/fstab` entry) before the script first ran — in that case nothing was ever staged. When
`provision.sh` itself performed the first mount it staged and migrated the tree onto the Volume, so
the root-disk copy there is a verified duplicate, not unique data. `findmnt` / `lsblk` cannot show
content beneath a mount; only the unmount in step 2 reveals it.

### If `provision.sh` fails partway through

`provision.sh` runs `set -euo pipefail` and exits on the first error. It is **idempotent** — every
step checks whether its work is already done — so the recovery procedure is simply:

1. Read the last `[provision]` / `[provision][error]` line to identify the failed step (error lines
   go to **both** stdout and stderr, so they survive `provision.sh > provision.log` with no
   `2>&1`).
2. Fix the reported cause (missing package mirror, `.env` not yet placed, alert channel not set,
   Volume not attached, …).
3. Re-run `bash deploy/provision.sh`. Completed steps are detected and skipped.

Two things to know before re-running:

- **The one non-idempotent hazard is `chown -R` over live data mounts.** `chown_if_needed` skips the
  recursive `chown` when the directory's **top-level** owner already matches — so a re-run against a
  running stack does not interrupt an in-progress container write — and *additionally* re-runs
  `chown -R` when the directory, its **children or its grandchildren** (`find -maxdepth 2`) has a
  mismatched uid **or** gid, which covers a run killed mid-`chown -R` with the top level already
  done (two levels, not one, so a single-child dir like `loki/` → `chunks/` that was descended into
  is still caught). A mismatch buried **more than two levels deep** is *not* caught automatically: after any
  interrupted provision, remediate each non-root data subdir by hand —

  | Subdir | Owner (`uid:gid`) |
  |---|---|
  | `data/prometheus` | `65534:65534` |
  | `data/loki`, `data/tempo` | `10001:10001` |
  | `data/grafana` | `472:472` |
  | `data/redis` | `999:1000` |

  ```bash
  # example for grafana — repeat per subdir with its own uid:gid
  find /opt/skillars/data/grafana \( \! -uid 472 -o \! -gid 472 \) -print -quit   # any output ⇒ mismatch present
  chown -R 472:472 /opt/skillars/data/grafana                                     # fix it
  ```

  Both uid and gid are checked because `chown -R` sets both; an interruption can leave one correct
  and the other stale.
- **Ordering constraint:** attach the Hetzner Volume *before* the run that is expected to mount it. A
  run with no Volume attached still completes (with a warning), leaving data on the root disk; the
  next run after attaching mounts the Volume and **migrates that pre-Volume `data/` tree onto it**
  (via `/opt/skillars/.pre-volume-migration`), unless the attached Volume already holds data, which
  it will not overwrite.

---

## Step 4: Apply the Firewall

> **Defence in depth:** `provision.sh` (Step 3) already enabled `ufw`, a host-level firewall running inside the VM kernel (allows 22/80/443, default-deny incoming). A provider-level firewall (if available) adds a second perimeter — it filters traffic before it reaches the VM. **Note:** Docker manages iptables directly for ports it publishes (80/443, serving Traefik), which can bypass ufw's rules for those ports — ufw's SSH (22) rule is the port genuinely enforced host-side. The provider-level firewall (if applicable) remains the real perimeter for 80/443. The two layers are otherwise independent: ufw runs entirely inside the VM kernel and does not depend on provider APIs.

**⚠️ Provider-specific firewall steps:**
- **Hetzner Cloud:** Use `hcloud` CLI (see below)
- **netcup:** Manual firewall configuration via control panel (Step 4 instructions below apply to Hetzner; for netcup, apply equivalent rules via your provider console)

### Hetzner Cloud Firewall Setup

Run this from your **local machine** (not the Node):

First, find your current public egress IP — this is what `SSH_ALLOWLIST_IP` must be:

```bash
curl -s ifconfig.me
```

> **Before applying the firewall:** in a second terminal, confirm `ssh root@<NODE_IP>` connects with your key (no password prompt). After the firewall is applied, port 22 is restricted to `SSH_ALLOWLIST_IP/32` only. If the IP is wrong or key-based login is not working, you will be locked out. Recovery requires the Hetzner web console.

```bash
export HCLOUD_TOKEN=<your-hetzner-api-token>
export SSH_ALLOWLIST_IP=<your-public-ip>       # without /32, e.g. 203.0.113.10 — use output of curl ifconfig.me

# From the root of the local repository clone:
bash deploy/firewall/apply-firewall.sh
```

> **Run this AFTER Step 3 (provisioning).** The Hetzner default allows SSH from all IPs.
> After the firewall is applied, port 22 is restricted to `SSH_ALLOWLIST_IP` only.
> Ports 80 and 443 remain open to all.

#### SSH exposure window (Step 3 → Step 4)

Between `provision.sh` finishing (**Step 3**) and `apply-firewall.sh` running (**Step 4**), TCP 22
is reachable from **any internet IP** *unless* you scope it with `SSH_ALLOWLIST_IP` (below) — by
default the host `ufw` rule `provision.sh` adds is `allow 22/tcp` (all sources), and the Hetzner
Cloud firewall that narrows it to `SSH_ALLOWLIST_IP/32` is only applied here in Step 4. During that
window an unscoped host is protected only by key-only SSH auth + `fail2ban`. **Minimise it by
running Step 4 immediately after Step 3.** The Hetzner Cloud firewall remains the real perimeter
regardless.

To scope the host `ufw` SSH rule from the start of Step 3 instead, export `SSH_ALLOWLIST_IP`
**before** running `provision.sh`. It must be **your workstation's** public egress IP — the *same*
value Step 4 uses. Get it by running `curl -s ifconfig.me` **on your local machine** (not on the
node — on the node that command returns the node's own IP, which will never match your SSH session
and scoping will silently fall back to open):

```bash
# on your LOCAL machine, note the output:
curl -s ifconfig.me

# then on the node, before provisioning (use `sudo -E` if you run provision.sh via sudo, or plain
# `sudo` strips the variable):
export SSH_ALLOWLIST_IP=<that-value>   # bare IPv4, no /32
bash deploy/provision.sh
```

`provision.sh` only scopes the rule when the value is a valid bare IPv4 **and** matches the client
IP of the SSH session it is running in (`$SSH_CLIENT` / `$SSH_CONNECTION`). A malformed value, a
mismatch (operator behind NAT/VPN, dynamic IP, IPv6 session, or the "ran `curl` on the node"
mistake above), or no SSH session at all → it logs a warning and falls back to the internet-wide
`allow 22/tcp`, so it can never lock you out mid-run.

**IPv6:** the scoped rule is IPv4-only, and scoping removes the broad v4 **and** v6 port-22 rules,
so while it is active SSH over IPv6 on port 22 is closed. Don't set `SSH_ALLOWLIST_IP` if you need
IPv6 SSH before Step 4; the Step 4 Hetzner Cloud firewall is dual-stack either way.

**Asymmetry:** a later `provision.sh` run with `SSH_ALLOWLIST_IP` unset does **not** re-widen a
previously-scoped rule — remove it by hand with
`ufw delete allow from <ip>/32 to any port 22 proto tcp` if you need 22 open to all again. A
re-run with a *different* `SSH_ALLOWLIST_IP` (your egress IP changed) *does* clean up: the old
scoped rule is pruned before the new one is added.

The script is idempotent — re-running updates existing rules rather than creating duplicates.

Optional overrides:

```bash
export HCLOUD_SERVER_NAME=skillars-prod   # default; change if you named the server differently
export FIREWALL_NAME=skillars-prod-fw     # default firewall name
```

### netcup Firewall Setup (Alternative to Hetzner)

If using **netcup**, the `apply-firewall.sh` script does not apply. Instead, manually configure your VPS firewall via the netcup control panel:

1. Log into your netcup account
2. Navigate to your VPS (e.g., VPS 1000 G12) and open the **Firewall** or **Security** settings
3. Create inbound rules:
   - **SSH (22)**: Restrict to your public IP (from `curl -s ifconfig.me`), or allow all if not yet provisioned
   - **HTTP (80)**: Allow from all sources (required for Let's Encrypt HTTP-01 challenge)
   - **HTTPS (443)**: Allow from all sources
4. All other inbound traffic: Deny by default
5. Outbound: Allow all (required for SMTP, Let's Encrypt, Stripe, etc.)

After firewall configuration, proceed to Step 5.

---

## Step 5: Prepare Secrets

On your **local machine**:

```bash
cp .env.example .env
```

Open `.env` and fill in **every value**. See [`docs/deployment/secrets-reference.md`](secrets-reference.md) for the full list with format descriptions and generation commands.

> **At least one of `GF_ALERT_NOTIFY_EMAIL` / `GF_SLACK_WEBHOOK_URL` is required** — `provision.sh`
> (Step 3) refuses to proceed (`exit 1`) if `.env` is present and both are blank. If you set
> `GF_ALERT_NOTIFY_EMAIL`, you must **also** set `GF_SMTP_ENABLED=true` and the `GF_SMTP_*` block, or
> email alerts silently fail. Setting only one channel is allowed (a warning is printed for the
> other).

> **Before copying secrets:** verify `.env` is gitignored so it can never be accidentally committed:
> ```bash
> git check-ignore -v .env   # must print a line referencing .gitignore — if empty, stop and fix .gitignore first
> ```

Once all values are filled, copy the file to the Node:

```bash
scp .env root@<NODE_IP>:/opt/skillars/.env
```

Re-run `provision.sh` to enforce mode 600 on the file (or set it manually):

```bash
# Option A — idempotent re-run (recommended):
ssh root@<NODE_IP> "bash /opt/skillars/deploy/provision.sh"

# Option B — manual:
ssh root@<NODE_IP> "chmod 600 /opt/skillars/.env"
```

---

## Step 6: Deploy the Stack

Wait for DNS propagation (verify with `dig` as shown in Step 2), then start all services:

```bash
ssh root@<NODE_IP> "cd /opt/skillars && docker compose up -d"
```

> **Docker Hub pull rate limits.** Unauthenticated pulls from Docker Hub are rate-limited per
> source IP, and shared Hetzner egress IPs can hit that ceiling. The symptom is `toomanyrequests`
> on `docker compose pull` / `up`. Mitigation: run `docker login` on the Node with a free Docker
> Hub account, which raises the limit. **As of 2026 the figures are ~100 pulls / 6h
> unauthenticated and ~200 / 6h authenticated — Docker has changed these repeatedly, so check
> [docs.docker.com/docker-hub/download-rate-limit](https://docs.docker.com/docker-hub/download-rate-limit/)
> for the current numbers rather than relying on these.** The images this stack pulls from Docker
> Hub are `grafana/grafana`, `redis`, `prom/*`, `grafana/loki`, and `grafana/tempo`; the `app`
> image is GHCR-hosted and unaffected.

Watch the startup status:

```bash
ssh root@<NODE_IP> "cd /opt/skillars && docker compose ps"
```

All services should reach the `healthy` state within **~60 seconds**. The `app` container may take up to **120 seconds** on first start — Docker waits 60 seconds before the first health check begins, then the app needs additional time to complete database migrations. If `docker compose ps` still shows `starting` after 2 minutes, check logs with `docker compose logs app --tail=50`.

---

## Step 7: Verify the Environment

```bash
curl -s https://<DOMAIN>/actuator/health
# Expected response: {"status":"UP"}  with HTTP 200
```

If you see a certificate error, wait 2–5 more minutes — Traefik may still be obtaining the Let's Encrypt certificate.

Full service health reference — run `docker compose` commands from `/opt/skillars` on the Node:

| Service | Health endpoint / command |
|---|---|
| app | `curl -s https://<DOMAIN>/actuator/health` → `{"status":"UP"}` |
| postgres | `docker compose exec postgres pg_isready -U <POSTGRES_USER> -d <POSTGRES_DB>` |
| redis | `docker compose exec redis redis-cli ping` → `PONG` |
| traefik | `docker compose exec traefik traefik healthcheck --ping` |
| prometheus | `docker compose exec prometheus wget -qO- http://localhost:9090/-/ready` |
| grafana | `curl -s https://<MONITORING_DOMAIN>/api/health` → `{"database":"ok"}` |
| loki | `docker compose exec loki wget -qO- http://localhost:3100/ready` |
| tempo | `docker compose exec tempo wget -qO- http://localhost:3200/ready` |
| minio | `docker compose exec minio mc admin info local` → displays service info and status |

Replace `<POSTGRES_USER>` and `<POSTGRES_DB>` with the values from your `.env`.

Additionally, log in at `https://<MONITORING_DOMAIN>` with the `GF_SECURITY_ADMIN_USER`/
`GF_SECURITY_ADMIN_PASSWORD` values from `.env` to confirm the admin account works end-to-end — the API
health check above only confirms the process is up and reaches its own database, not that the admin login
itself works.

---

## Step 8: Set Up External Uptime Monitor (Required)

With the stack verified, you **must** configure an external uptime monitor that is independent of this Node.
If the Node (and the entire LGTM stack) goes down, this monitor is the only alert path still active.

Follow the setup instructions in [`docs/deployment/uptime-monitor.md`](uptime-monitor.md).

**Required before this step:**
- The application is reachable at `https://YOUR_DOMAIN/actuator/health` (confirmed in Step 7)
- You have a Slack webhook URL (the same one used for `SLACK_WEBHOOK_URL` in GitHub Actions secrets works)
- OR an alternative alerting channel (email, PagerDuty, etc.)

**Expected time:** ~5 minutes.

> **Do not mark this environment as "production-ready" until Step 8 is complete.** Without an external monitor, you have no way to detect outages.

---

## Step 9: Configure Backup Strategy (Strongly Recommended)

After the stack is live, establish a backup and recovery plan:

**Data at risk:**
- PostgreSQL database: player/coach/admin data, payment history, session state
- MinIO object storage: uploaded video files, images, documents
- Traefik `acme.json`: TLS certificates (regenerating costs time and hits Let's Encrypt rate limits)

**Backup options:**

1. **Database backups**
   - Run `docker compose exec postgres pg_dump -U postgres skillars > skillars-$(date +%Y%m%d-%H%M%S).sql` regularly (daily recommended)
   - Store backups off-server (S3, cloud storage, NAS)
   - Test recovery at least monthly: `psql -U postgres skillars < backup.sql`

2. **Volume/filesystem backups**
   - If using a managed volume (Hetzner, netcup), use provider snapshots if available
   - Alternatively, use `restic`, `duplicati`, or similar for incremental off-site backups of `/opt/skillars/data`
   - Include MinIO data in filesystem backups

3. **Automated recovery testing**
   - Monthly: restore a backup to a test environment and verify the app starts correctly
   - Keep recovery procedures documented and tested

See [`docs/deployment/backup-restore.md`](backup-restore.md) for detailed backup/restore procedures.

---

## Step 10: Post-Setup Monitoring & Operations

After setup is complete:

1. **Review monitoring dashboards**
   - Log into Grafana at `https://<MONITORING_DOMAIN>`
   - Verify alerts are wired up: check [`docs/deployment/monitoring.md`](monitoring.md)

2. **Familiarize yourself with the runbook**
   - Read [`docs/deployment/runbook.md`](runbook.md) for common operational tasks:
     - Viewing logs
     - Restarting services
     - Scaling the stack
     - Health checks
     - Known issues and fixes

3. **Establish on-call procedures**
   - Configure escalation policies if using PagerDuty or similar
   - Document your team's response procedures for alerts

---

## Troubleshooting

All `docker compose` commands below must be run from `/opt/skillars` on the Node. If starting a new SSH session, run `cd /opt/skillars` first.

**TLS certificate not issued / HTTPS returns a certificate error**
- Confirm DNS is propagated for **both** `DOMAIN` and `MONITORING_DOMAIN`: `dig +short <DOMAIN> @8.8.8.8` and `dig +short <MONITORING_DOMAIN> @8.8.8.8` must each return the Node IP
- Check Traefik logs for ACME errors: `docker compose logs traefik --tail=50`
- Traefik requires `acme.json` to have mode 600; `provision.sh` sets this — re-run if needed
- **Do not delete `acme.json`** — Traefik will immediately attempt a new ACME request and a failed attempt counts against the rate limit. Stop the stack first (`docker compose down`) if the file must be recreated, then re-run `provision.sh` to recreate it with the correct permissions before restarting
- **Do not restart the stack repeatedly while debugging** — Let's Encrypt rate-limits failed validations to 5 per hostname per hour and 5 duplicate certificates per week. Exhausting this limit blocks certificate issuance for up to a week. Confirm `dig` returns the correct IP before each restart attempt.

**App service is unhealthy**
- Check logs: `docker compose logs app --tail=100`
- Confirm all required `.env` values are set (no `change-me` placeholders remain)
- Confirm PostgreSQL is healthy: `docker compose ps postgres`

**Volume not mounted / PostgreSQL data not on persistent storage**
- If `provision.sh` section 7 logged a warning, the Volume was not attached at provisioning time
- Attach the Volume in the Hetzner Cloud Console, then re-run: `ssh root@<NODE_IP> "bash /opt/skillars/deploy/provision.sh"`

**SSH access locked out after firewall**
- The firewall restricts SSH to the IP you specified in `SSH_ALLOWLIST_IP`
- If your IP changed or key-based auth is broken, recover via the **Hetzner web console**:
  1. Go to [Hetzner Cloud Console](https://console.hetzner.cloud) → **Servers** → `skillars-prod` → **Console** tab
  2. Log in as `root` (no SSH key required — this is a direct VNC session)
  3. **Incorrect IP:** re-run the firewall script from inside the server with the updated IP, or remove the Hetzner firewall rule via Console → **Firewalls** → `skillars-prod-fw` → edit the SSH rule
  4. **Broken SSH key:** run `cat >> /root/.ssh/authorized_keys` and paste the correct public key, then exit

**Service fails to start: port conflict**
- Internal ports (9990, 8367, 5432, 6379, 3000, 9090, 3100, 3200) are not exposed to the host; they are internal to the Docker bridge networks
- Only ports 80 and 443 are published to the host

**Network topology (two bridge networks):**
- `skillars-internal` — `traefik`, `postgres`, `app`, `grafana`. Has an egress route: `app` needs OTLP/email/Bunny.net/Stripe, `grafana` needs email/Slack alert delivery, `traefik` needs ACME.
- `skillars-observability` — `internal: true` (no gateway, **no outbound internet**): `prometheus`, `loki`, `tempo`, `redis`, `node_exporter`. None of them need egress; a compromised one cannot exfiltrate or call home.
- `app` and `grafana` are members of **both** networks so they can still reach the observability services while keeping their own egress. Every service still shares a network with every peer it talks to, so no internal traffic is affected. No host `iptables` rules involved — this is compose-only.
