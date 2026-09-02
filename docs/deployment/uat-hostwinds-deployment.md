# UAT (Hostwinds) — Stripped-Down Deployment Guide

Get Skillars running on the existing Hostwinds Ubuntu box
(`hwsrv-1301707.hostwindsdns.com`, currently running `gulliver` — see
[`docs/gulliver-doc.md`](../gulliver-doc.md)) far enough to click through the
happy path. **This is not the final UAT environment** — see
[`uat-deployment.md`](uat-deployment.md) for that (Traefik, TLS, its own
domains, the full LGTM observability stack). This guide deliberately drops
all of that: no Traefik, no HTTPS, no Loki/Tempo/Prometheus/Grafana. The
only two things kept from the full UAT design are the `uat` Spring profile
(so `PaymentConfig`'s live-key guard is still active — a stripped-down
environment is exactly where a stray live key would be easiest to miss) and
MinIO for storage (already built for `uat-deployment.md`, and the fastest
way to a working upload flow without real AWS credentials).

**This box can only run one of gulliver or Skillars at a time** — both
default to port `9990`, and this guide reuses that port rather than
reconfiguring either app. See [Step 1](#step-1-stop-gulliver--and-its-postgres).

**The actual box is small: 1 vCPU / 1GB RAM / 30GB SSD (Amsterdam, Ubuntu
24.04).** That's tight enough to need real attention, not just "drop
Traefik and hope" — see [Resource budget](#resource-budget) for the full
accounting and what got tuned because of it.

Image tag: **`skillars:uat`**.

---

## Architecture at a glance

```
Browser ──80──> nginx (existing, unchanged) ──> 127.0.0.1:9990 ──> app container
Browser ──9500─────────────────────────────────────────────────> minio container
app container ──skillars-internal network──> postgres, redis, minio containers
```

- **nginx**: already installed and configured for gulliver
  (`/etc/nginx/sites-available/gulliver`, `proxy_pass http://localhost:9990;`).
  Once gulliver is stopped and the `app` container publishes `9990` on
  `127.0.0.1`, this config transparently starts serving Skillars instead —
  **zero nginx changes needed**. The site file is still literally named
  `gulliver`; see [Notes](#notes) if you want to rename it for clarity.
- **MinIO**: no domain-based routing available here (unlike
  `uat-deployment.md`'s `STORAGE_DOMAIN` + Traefik), so its S3 API port is
  published directly on the host instead — `http://hwsrv-1301707.hostwindsdns.com:9500`.
- **Postgres/Redis**: run in Docker, internal-only (not published to the
  host) — a separate instance from the native PostgreSQL 17 already
  installed on this box for gulliver, no port conflict. That said, the
  native instance is stopped for the duration of this deployment anyway
  (Step 1) — not because of a conflict, but because the box can't afford to
  run two Postgres instances at once. See [Resource budget](#resource-budget).

---

## Resource budget

Every other guide in `docs/deployment/` targets multi-core boxes with
several GB of RAM. This one doesn't have that luxury, so the defaults in
`docker-compose.yml` needed real tuning, not just the `cpus: "1.00"` fix
already applied to `app` (its base-file default of `2.0` exceeds this box's
single vCPU outright — Docker refuses to even create the container in that
case, rather than just running slower) — here's the accounting, so the
numbers in `docker-compose.uat-hostwinds.yml` read as reasoned rather than
arbitrary:

| Component | Memory limit | Why |
|---|---|---|
| OS + Docker daemon + nginx | ~150–200MB (not enforced, just budgeted) | Baseline for Ubuntu 24.04 + `dockerd` + a lightweight reverse proxy |
| `postgres` (Docker) | 220MB | Down from the base file's 1536MB. `shared_buffers` defaults to 128MB in the `postgres:17-alpine` image; 220MB leaves headroom above that without the multi-GB assumption the base file makes |
| `redis` (Docker) | 48MB | Down from 256MB — see below, actual usage is near-zero |
| `minio` | 140MB | Not capped at all before this guide (new service, no base-file default to inherit) |
| `app` | 460MB | Down from 2GB. See the `JAVA_TOOL_OPTIONS` comment in `docker-compose.uat-hostwinds.yml` for the heap/metaspace/code-cache breakdown that fits inside it |

That's 868MB committed across the four containers, leaving roughly
150MB for the OS/Docker/nginx baseline out of 1024MB total — workable, but
not comfortable. Two things make this less fragile than the raw numbers
suggest:

- **Redis is present but not actually load-bearing.** Grepping the codebase
  for `RedisTemplate`/`RedisConnectionFactory`/direct Lettuce usage turns up
  nothing — `bucket4j-redis` is on the classpath but `RateLimitingService`
  uses a plain in-memory `ConcurrentHashMap`, not Redis, and
  `AuthenticationFailureListener` has its own TODO noting Redis-backed login
  throttling is a *future* multi-node change, not current behavior. Spring
  Boot still auto-configures a Lettuce connection factory (nothing disables
  `spring-boot-starter-data-redis`'s auto-configuration), but Lettuce
  connects lazily, so the app boots fine regardless of whether Redis is
  reachable. The only observable effect of Redis being slow/absent is
  Actuator's Redis health indicator, silenced via
  `MANAGEMENT_HEALTH_REDIS_ENABLED=false`. **`redis` still runs as a
  container** (see the note in `docker-compose.uat-hostwinds.yml` about why
  it can't cleanly be dropped from `depends_on`), but its 48MB limit is
  sized for what it actually needs, not what the base file assumed.
- **Swap is added as a safety net** (Step 2) specifically because 868MB
  of limits against ~870MB of actually-available RAM leaves very little
  margin for the moment of peak memory pressure — typically class-loading
  during app startup, not steady-state. Swap turns a miscalculation into
  "slow" instead of "OOM-killed."

**None of this has been run against the live box yet.** Treat the numbers
above as a reasoned starting point, not a guarantee — if `app` gets
OOM-killed on first boot (`docker compose ... logs app` will show it was
killed abruptly rather than logging its own shutdown), the first thing to
try is raising `-Xmx`/the container `memory` limit for `app` at the expense
of `postgres`'s 220MB, since Postgres is the one component here with the
most headroom above its actual working set.

---

## Prerequisites

- SSH access to `hwsrv-1301707.hostwindsdns.com` (root, per
  `gulliver-history.txt`)
- `.env.uat` at the repo root, already prepared with real values for this
  box — `DOMAIN=hwsrv-1301707.hostwindsdns.com`, `APP_IMAGE=skillars:uat`,
  a real Stripe test-mode key, etc. (`MONITORING_DOMAIN`/`STORAGE_DOMAIN`
  are still present in the file but unused by this stripped-down setup —
  see [Notes](#notes))

---

## Step 1: Stop gulliver — and its Postgres

```bash
ps auxw | grep gulliver-0.0.1-SNAPSHOT.jar
kill <PID>
```

Confirm port `9990` is free before continuing:

```bash
netstat -tnlp | grep 9990   # should print nothing
```

Also stop the box's **native** PostgreSQL (installed for gulliver, per
`gulliver-doc.md` §2) — not because of a port conflict (the Docker
`postgres` container never touches the host's 5432), but because this box
can't afford to run two full Postgres instances on 1GB of RAM at once. See
[Resource budget](#resource-budget) for why this matters more than it might
look like it should.

```bash
sudo systemctl stop postgresql
systemctl status postgresql   # should show "inactive (dead)"
```

(`sudo systemctl start postgresql` reverses this if you ever need gulliver
working again without tearing down Skillars first — see
[Tearing down / restarting](#tearing-down--restarting).)

## Step 2: Add swap

1GB of RAM with no swap means the OOM killer is the *only* thing standing
between a momentary memory spike (most likely: JVM class-loading during
`app`'s startup) and a container getting hard-killed. A swapfile turns that
into "briefly slow" instead — cheap insurance given 30GB of SSD to spare,
and this is a one-time smoke-test box, not something where swap's latency
cost matters:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h   # confirm Swap: 2.0Gi
```

## Step 3: Install Docker + git

Not yet on this box — gulliver runs bare-metal (`java -jar`), no Docker
anywhere in its history.

```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
docker compose version
```

## Step 4: Clone the repo and build the image

The repo is private (`git@github.com:tenjohokwen/skillars.git`), so the box
needs its own credentials to clone it. **Use a GitHub deploy key** — an
SSH keypair generated on this box specifically, with the public half
registered as a **read-only Deploy Key on the repo itself** (GitHub →
repo → Settings → Deploy keys → Add deploy key), not tied to your personal
GitHub account or any broader PAT scope. Same pattern already used for
`SSH_DEPLOY_KEY` in `secrets-reference.md`, just in the opposite direction
(this box authenticating *to* GitHub, not GitHub Actions authenticating
*to* this box).

```bash
ssh-keygen -t ed25519 -C "hwsrv-1301707-skillars-uat" -f ~/.ssh/skillars_deploy -N ""
cat ~/.ssh/skillars_deploy.pub
```

Paste that public key into **Settings → Deploy keys → Add deploy key** on
the GitHub repo (leave "Allow write access" unchecked — this box only ever
needs to `git pull`, never push). Then tell SSH to actually use that key for
GitHub:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/skillars_deploy
  IdentitiesOnly yes
EOF
chmod 600 ~/.ssh/config

ssh -T git@github.com   # expect: "Hi tenjohokwen/skillars! You've successfully authenticated..."
```

```bash
mkdir -p /server
cd /server
git clone git@github.com:tenjohokwen/skillars.git skillars
cd /server/skillars
docker build -t skillars:uat .
```

The build compiles the full Java + Quasar frontend (multi-stage Dockerfile,
`mvn package` with tests skipped) — expect a few minutes, and make sure the
box has enough free RAM/disk (`df -h`, `free -h`); a small budget VPS can
struggle with a Maven + Node.js build. If it's too tight, build the image on
your own machine instead and transfer it with `docker save skillars:uat |
gzip | ssh root@<NODE_IP> "gunzip | docker load"`.

## Step 5: Get `.env.uat` onto the box

```bash
scp .env.uat root@hwsrv-1301707.hostwindsdns.com:/server/skillars/.env.uat
ssh root@hwsrv-1301707.hostwindsdns.com "chmod 600 /server/skillars/.env.uat"
```

(If you're already `cd /server/skillars` on the box from Step 4, and
`.env.uat` already has real values for this box, this is the only new file
to bring over — everything else came from `git clone`.)

## Step 6: Open the firewall for MinIO

```bash
ufw allow 9500/tcp
ufw status
```

**Do not open `9990`** — it's bound to `127.0.0.1` only (see
`docker-compose.uat-hostwinds.yml`) and reached through nginx on port 80
instead, matching how gulliver worked. Opening it would just add an
unnecessary second way to reach the app directly, bypassing nginx for no
benefit.

## Step 7: Deploy

```bash
cd /server/skillars
docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml \
  --env-file .env.uat up -d app postgres redis minio minio-init
```

**Explicit service list, not a bare `up -d`** — the base `docker-compose.yml`
also defines `traefik`, `loki`, `tempo`, `prometheus`, and `grafana`; naming
exactly the five services above is what keeps this deployment actually
stripped-down rather than accidentally starting the full stack anyway.

Watch it come up:

```bash
docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml --env-file .env.uat ps
docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml --env-file .env.uat logs -f app
```

If `PaymentConfig`'s live-key guard or the `PLATFORM_PIN_ENCRYPTION_SECRET`/
`APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` fail-fast checks trip, `app` exits
immediately on boot rather than looping unhealthy — see
[`uat-deployment.md` Step 4](uat-deployment.md#step-4-prepare-envuat) for
what each one needs.

If `app` instead disappears/restarts with no clean shutdown log line, that's
more likely the OOM killer than a config problem — see
[Resource budget](#resource-budget) for the memory accounting and what to
adjust first (`docker compose ... logs app`, and `dmesg | grep -i "killed process"`
on the host, will confirm which it was).

## Step 8: Seed bootstrap data

Same fixture as every other environment — without this row, login fails
with `AppSetupException: JWT secret key has not been set in DB` (see
[`local-deployment.md` Step 5](local-deployment.md#step-5-seed-required-bootstrap-data)):

```bash
docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml \
  --env-file .env.uat exec -T postgres psql -U skillars_uat -d skillars_uat < src/test/resources/sql/secData.sql
```

(Substitute `-U`/`-d` if `.env.uat`'s `POSTGRES_USER`/`POSTGRES_DB` differ.
Use `initTestData.sql` instead if you also want ready-made sample logins —
see `local-deployment.md` Step 5 for the account list, same file either
environment.)

## Step 9: Verify

```bash
curl -s http://hwsrv-1301707.hostwindsdns.com/actuator/health
# {"status":"UP"}
```

That request goes through nginx (port 80) to the app container on
`127.0.0.1:9990` — confirms the "no nginx changes needed" claim above
actually holds.

Then click through the happy path in a browser at
`http://hwsrv-1301707.hostwindsdns.com/` (register/login, book a session,
etc.), and specifically exercise:

- **A payment**, using a [Stripe test card](https://docs.stripe.com/testing)
  (`4242 4242 4242 4242`) — confirm it shows up in the Stripe Dashboard
  under **Test mode**, never live.
- **A file upload** (e.g. a coach profile photo) — confirm the image
  actually renders afterward. This is the real test of the MinIO/
  `extra_hosts` setup in `docker-compose.uat-hostwinds.yml`: if the upload
  succeeds but the image never loads, the presigned URL's host
  (`http://hwsrv-1301707.hostwindsdns.com:9500`) likely isn't resolving the
  way the `extra_hosts: host-gateway` entry intends — check
  `docker compose ... exec app getent hosts hwsrv-1301707.hostwindsdns.com`
  resolves to a private/internal IP, not the box's real public IP.

---

## Logging

**No file or folder to create manually — this is a real difference from
gulliver.** Gulliver needed `mkdir -p /var/log/gulliver` because it's
configured to write its own log file to that path (see `gulliver-doc.md`
§7). Skillars' `logback-spring.xml` has no file appender at all — it writes
structured JSON to stdout only, and Docker's `json-file` logging driver
captures that automatically for every container the moment it starts,
storing it under `/var/lib/docker/containers/<id>/` on the host. There's
nothing to provision before or after deploying; `docker logs`/`docker
compose logs` just work.

**One thing this stripped-down setup does need to turn off, though:**
`docker-compose.yml` hardcodes `LOKI_ENABLED=true`/`LOKI_URL=http://loki:3100`
on the `app` service unconditionally, and — unlike an older assumption
recorded in `local-deployment.md`'s own Logs section — `logback-spring.xml`
*does* attach a real `Loki4jAppender` to the root logger when
`loki.enabled=true`.

> This override turned out to be load-bearing for a second reason. Between the
> loki4j `1.5.2 → 2.1.0` bump (`039b1a8`, PR #48, 2026-08-13) and the appender
> fix on 2026-09-01, `loki.enabled=true` did not merely push logs nowhere — it
> aborted startup outright, because logback could not build the 1.x-shaped
> appender and Spring Boot treats a logback configuration error as fatal. This
> guide's `LOKI_ENABLED=false` is why a Hostwinds UAT deploy kept working
> through that window while the full stack would not have. The appender is now
> correct, so the override is back to being a pure "no loki container here"
> optimisation.

Since this guide never starts a `loki` container,
`docker-compose.uat-hostwinds.yml` explicitly sets `LOKI_ENABLED=false` to
stop the app from trying (harmlessly, but pointlessly) to push every log
batch to a hostname that will never resolve. If logs ever look like they're
disappearing or lagging, confirm that override is actually in place with
`docker compose ... exec app env | grep LOKI_ENABLED`.

There's also no Grafana here to browse logs visually (that only exists in
the full `uat-deployment.md`/`local-deployment.md` stacks) — `docker
logs`/`docker compose logs` against the container directly is the only way
to see them in this setup, which in practice is also the faster path for a
quick one-off look.

### Basic tail

```bash
docker logs skillars-app-1 -f --tail 100
```

Or via Compose, equivalent either way:

```bash
cd /server/skillars
docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml --env-file .env.uat logs -f app
```

(`-f` follows in real time, like `tail -f`. Swap `app` for `postgres`,
`redis`, or `minio` to watch a different container — or `skillars-app-1` for
`skillars-postgres-1`/`skillars-minio-1` with the plain `docker logs` form.
Container names follow `<compose-project>-<service>-<index>`; the project
name defaults to the directory Compose is run from, `skillars` per Step 4,
so these names are exactly what you'll see without needing `-p`.)

### Time-boxed (avoid dumping the whole history)

```bash
docker logs skillars-app-1 --since 10m
docker logs skillars-app-1 --since 30m
```

### Pretty-print / filter with `jq` (recommended — every line is JSON)

```bash
docker logs skillars-app-1 --since 10m 2>&1 | jq -r 'select(.level=="ERROR")'
docker logs skillars-app-1 --since 10m 2>&1 | jq -r '"\(.["@timestamp"]) \(.level) \(.logger_name) \(.message)"'
```

### Grep for a specific thing

```bash
docker logs skillars-app-1 --since 30m 2>&1 | grep -i "payment.coachStripeNotConfigured\|player/register"
```

### "Tracing" a request end-to-end

There's no Tempo in this stripped-down setup (`MANAGEMENT_OTLP_TRACING_ENDPOINT`
is left pointed at `tempo:4318`, which doesn't exist here — the OTLP
exporter just fails to send in the background, silently and asynchronously,
which is normal/expected behavior for that exporter and not something to
chase down), so there's no trace-waterfall UI to open. What you still get:
every log line carries the same `traceId` (via logback's `mdc` provider —
see `logback-spring.xml`), so grepping/filtering on one `traceId` reconstructs
a single request's full lifecycle across every log line it touched:

```bash
docker logs skillars-app-1 --since 30m 2>&1 | jq -r 'select(.traceId=="<paste-a-traceId-here>")'
```

Grab a `traceId` from any line first (e.g. from an error you're chasing, or
from the `request_start`/`request_end` lines `LoggingFilter` emits, which
also carry `operation` + `httpStatus`).

Key JSON fields: `level`, `logger_name`, `message`, `traceId`/`spanId`/
`requestId` (for correlating a request), `operation` + `httpStatus` (on
`request_start`/`request_end` lines specifically).

### nginx

Complements the app's own logs — useful when a request never seems to reach
the app at all (bad gateway, connection refused on `127.0.0.1:9990`, etc.):

```bash
tail -f /var/log/nginx/access.log
less /var/log/nginx/error.log
```

### Log rotation / disk space

`app`, `postgres`, `redis`, `minio`, and `minio-init` all cap their Docker
logs at 10MB × 3 files (`x-logging` in `docker-compose.yml`, mirrored for
`minio`/`minio-init` in `docker-compose.uat-hostwinds.yml` since those two
containers don't exist in the base file and would otherwise get Docker's
unbounded default driver). If disk usage still looks off after running for a
while, `docker system df` and `df -h` are the first things to check — the
same class of problem `local-deployment.md`'s Troubleshooting section
describes for the LGTM stack applies here too (repeated image rebuilds
filling Docker's build cache, not just log growth).

---

## Tearing down / restarting

```bash
docker compose -f docker-compose.yml -f docker-compose.uat-hostwinds.yml --env-file .env.uat down
```

Add `-v` to also wipe the Postgres and MinIO volumes and start fully fresh
next time.

To go back to gulliver: `cd /server/gulliver && nohup java -jar -Dspring.profiles.active=local gulliver-0.0.1-SNAPSHOT.jar &`
(see `gulliver-doc.md`) — nginx will pick it back up on port 80 the same
way, automatically, once it's listening on `9990` again.

---

## Notes

- **`.env.uat` is now doing double duty.** The same file backs both this
  stripped-down Hostwinds setup and (eventually) the real
  `uat-deployment.md` environment. Right now it has Hostwinds-shaped values
  (`DOMAIN` is a bare hostname with no TLS, `MONITORING_DOMAIN=unused`).
  When you're ready to stand up the real UAT environment from
  `uat-deployment.md`, you'll need to either swap these back to real
  domain/Traefik-shaped values or split into a second env file — don't
  assume this file is still Hostwinds-specific by then without checking.
- **`STORAGE_DOMAIN` and the LGTM/Grafana variables in `.env.uat` are unused
  here** — `docker-compose.uat-hostwinds.yml` doesn't reference
  `STORAGE_DOMAIN` at all (uses `DOMAIN` + port `9500` instead), and
  `grafana`/`loki`/`tempo`/`prometheus` are never in the explicit service
  list in Step 7. Harmless to leave them in the file.
- **The nginx site is still named `gulliver`** even though it's now serving
  Skillars. Fine for a quick test; if this setup sticks around, consider:
  ```bash
  mv /etc/nginx/sites-available/gulliver /etc/nginx/sites-available/skillars-uat
  rm /etc/nginx/sites-enabled/gulliver
  ln -s /etc/nginx/sites-available/skillars-uat /etc/nginx/sites-enabled/
  nginx -t && sudo systemctl reload nginx
  ```
  (Purely cosmetic — the `proxy_pass http://localhost:9990;` target doesn't
  change.)
- **No systemd unit** — same gap as gulliver had. `docker compose up -d`
  containers do restart automatically on crash (`restart: unless-stopped`
  where set) and after a Docker daemon restart, but if the whole box
  reboots, `dockerd` itself needs to come back and the containers with it —
  worth confirming this actually happens (`systemctl is-enabled docker`
  should say `enabled`) rather than assuming it, since this hasn't been
  tested end-to-end against an actual reboot.
- This guide, like `uat-deployment.md`, was assembled from the current repo
  state and validated with `docker compose config` against the real
  `.env.uat` (merge succeeds, no missing-variable errors) — but has not yet
  been run end-to-end against the live Hostwinds box. Treat Steps 6–8 as the
  most likely to need a small correction the first time through.
