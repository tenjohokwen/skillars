# First-Time Setup Guide

Follow this guide end-to-end to bring up a live, TLS-enabled production environment from zero.
**Target time: ≤ 2 hours.**

You will need: SSH access to a fresh server, a secrets file, and a registered domain.
All deployment instructions are contained in this repository.

---

## Prerequisites

Ensure the following are available on your **local machine** before starting:

| Requirement | How to obtain |
|---|---|
| `hcloud` CLI | `brew install hcloud` (macOS) or download from [hetznercloud/cli releases](https://github.com/hetznercloud/cli/releases) |
| `openssl` | Pre-installed on macOS/Linux; install via `apt install openssl` if absent |
| Hetzner Cloud account | https://console.hetzner.cloud |
| Hetzner API token (read + write) | Hetzner Console → Security → API Tokens → Generate API token |
| SSH key uploaded to Hetzner | Hetzner Console → Security → SSH Keys → Add SSH Key |
| Registered domain with DNS management access | Your domain registrar |
| SSH private key on local machine | The key pair whose public key is uploaded to Hetzner |
| Local clone of this repository | `git clone <REPO_URL>` on your local machine — required for Step 4 (firewall script) and Step 5 (`.env` file) |

---

## Step 1: Create the Hetzner Server and Volume

In the **Hetzner Cloud Console**:

1. **Create a server**
   - Type: **CX32** (4 vCPU, 8 GB RAM)
   - OS: **Ubuntu 22.04 LTS**
   - Name: **`skillars-prod`** — the firewall script uses this name by default; change it only if you also set `HCLOUD_SERVER_NAME` when running the firewall script
   - SSH Keys: select the key you uploaded in the prerequisites
   - Note the **public IP address** — you will need it for SSH and DNS

2. **Attach a 100 GB Volume**
   - Create or attach immediately after server creation
   - The provisioning script mounts the volume at `/opt/skillars/data` — it expects the device at `/dev/sdb` (the default for a single attached Hetzner Volume)
   - PostgreSQL, Prometheus, Loki, Tempo, and Grafana data all live on this volume
   - After attaching, SSH to the Node and run `lsblk` to confirm the volume appears as `/dev/sdb`. If it is listed under a different name, the provisioning script hardcodes `/dev/sdb` and will mount the wrong device — stop and verify before proceeding to Step 3.

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
git clone <REPO_URL> /opt/skillars   # your GitHub repository URL, e.g. https://github.com/<org>/javatemplate.git
cd /opt/skillars
bash deploy/provision.sh
```

What `provision.sh` does (all steps are idempotent — safe to re-run):

1. Installs system packages: Docker Engine, Docker Compose plugin, fail2ban, ufw
2. Applies SSH hardening: password authentication disabled, root login key-only
3. Configures fail2ban: sshd jail, maxretry=5, bantime=3600s
4. Enables `ufw` (host-level firewall): allows SSH (22), HTTP (80), and HTTPS (443), then sets default-deny-incoming / default-allow-outgoing — SSH is allowed *before* `ufw` is enabled so the active provisioning session is not terminated
5. Creates the base directory structure: `/opt/skillars/data/postgres`, `/opt/skillars/lgtm`
6. Mounts the Hetzner Volume (`/dev/sdb`) at `/opt/skillars/data`, then creates the data subdirectories that live on it (`postgres`, `prometheus`, `loki`, `tempo`, `grafana`) with correct ownership
7. **After** the mount: creates `/opt/skillars/data/redis` (owned by uid 999, the redis image's user) and `/opt/skillars/data/traefik/acme.json` with mode 600 (required by Traefik; no manual step needed)

> **The order of 6 and 7 matters and is deliberate.** Both paths live on the Volume, so creating
> them before the mount would write them to the root disk and the mount would then hide them —
> Traefik would start against an empty `acme.json` and silently reissue every certificate.
>
> If the Volume is not attached yet, section 7 of the script logs a warning and skips the mount, but
> still creates the redis directory and `acme.json` — on the root disk, where they work but do not
> survive a rebuild. Attach the Volume in the Hetzner Console and re-run `provision.sh` to complete
> the mount.

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

- **The one non-idempotent hazard is `chown -R` over live data mounts.** It is already mitigated —
  `chown_if_needed` skips the recursive `chown` entirely when the directory's owner already matches,
  so a re-run against a running stack cannot interrupt an in-progress container write.
- **Ordering constraint:** the Hetzner Volume must be attached *before* the run that is expected to
  mount it. A run with no Volume attached completes (with a warning) but leaves all data on the root
  disk; re-run after attaching to complete the mount.

---

## Step 4: Apply the Firewall

> **Defence in depth:** `provision.sh` (Step 3) already enabled `ufw`, a host-level firewall running inside the VM kernel (allows 22/80/443, default-deny incoming). The Hetzner Cloud firewall applied below is the primary network perimeter — it filters traffic before it ever reaches the VM. **Note:** Docker manages iptables directly for ports it publishes (80/443, serving Traefik), which can bypass ufw's rules for those ports — today, ufw's SSH (22) rule is the port genuinely enforced host-side; the Hetzner Cloud firewall remains the real perimeter for 80/443. The two layers are otherwise independent: ufw's SSH enforcement runs entirely inside the VM kernel and does not depend on Hetzner's API at all. Whether already-applied Hetzner Cloud firewall rules keep enforcing during a Hetzner API outage is a claim about Hetzner's infrastructure that we have not verified or found documented — treat it as unconfirmed rather than relying on it.

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

The script is idempotent — re-running updates existing rules rather than creating duplicates.

Optional overrides:

```bash
export HCLOUD_SERVER_NAME=skillars-prod   # default; change if you named the server differently
export FIREWALL_NAME=skillars-prod-fw     # default firewall name
```

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

Replace `<POSTGRES_USER>` and `<POSTGRES_DB>` with the values from your `.env`.

Additionally, log in at `https://<MONITORING_DOMAIN>` with the `GF_SECURITY_ADMIN_USER`/
`GF_SECURITY_ADMIN_PASSWORD` values from `.env` to confirm the admin account works end-to-end — the API
health check above only confirms the process is up and reaches its own database, not that the admin login
itself works.

---

## Step 8: Set Up External Uptime Monitor

With the stack verified, configure an external uptime monitor that is independent of this Node.
If the Node (and the entire LGTM stack) goes down, this monitor is the only alert path still active.

Follow the setup instructions in [`docs/deployment/uptime-monitor.md`](uptime-monitor.md).

**Required before this step:**
- The application is reachable at `https://YOUR_DOMAIN/actuator/health` (confirmed in Step 7)
- You have a Slack webhook URL (the same one used for `SLACK_WEBHOOK_URL` in GitHub Actions secrets works)

**Expected time:** ~5 minutes.

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
- Internal ports (9990, 8367, 5432, 6379, 3000, 9090, 3100, 3200) are not exposed to the host; they are internal to the `skillars-internal` Docker bridge network
- Only ports 80 and 443 are published to the host
