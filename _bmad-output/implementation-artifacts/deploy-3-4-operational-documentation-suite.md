# Story Deploy-3.4: Operational Documentation Suite

Status: done

## Story

As a developer who needs to understand, operate, or recover any aspect of the production system,
I want a complete set of operational documents in `docs/deployment` covering backup/restore, Traefik/TLS, monitoring, and a failure runbook,
so that I can diagnose and resolve any operational incident without improvising steps or consulting sources outside the repository.

## Acceptance Criteria

**AC-1: backup-restore.md**
- Given `docs/deployment/backup-restore.md` exists,
- When a developer initiates a restore from either a volume snapshot or a `pg_dump`,
- Then the guide covers all steps: initiating the restore, verifying data integrity (row count check or health endpoint pass), and bringing the application back online
- And a developer can complete the full restore using the guide alone

**AC-2: traefik-tls.md**
- Given `docs/deployment/traefik-tls.md` exists,
- When a developer needs to verify TLS certificate status or diagnose a failed renewal,
- Then the guide explains how Traefik is configured, how certificates are issued and renewed, the expected renewal timeline, and the steps to take if a certificate is not renewed before expiry

**AC-3: monitoring.md**
- Given `docs/deployment/monitoring.md` exists,
- When a developer needs to access Grafana or respond to an alert,
- Then the guide explains how to access Grafana, what dashboards exist, what each configured alert means, and what action to take when each alert fires
- And every alert defined in `alerts.yml` and `grafana-alerts.yml` has a corresponding documented response action in this guide

**AC-4: runbook.md**
- Given `docs/deployment/runbook.md` exists with three failure scenarios (disk exhaustion, PostgreSQL service down, Redis OOM),
- When a developer encounters one of these failures,
- Then each scenario section includes: a detection method, step-by-step remediation, and a verification check
- And a developer can resolve each scenario by following the runbook alone, without improvising any step

---

## Tasks / Subtasks

- [x] Task 1: Create `docs/deployment/backup-restore.md` (AC-1)
  - [x] Intro section: when to use this guide vs. rollback.md (two restore paths: pg_dump and volume snapshot)
  - [x] Section A: Restore from pg_dump — step-by-step using `deploy/backup/restore-from-dump.sh`
  - [x] Section B: Restore from volume snapshot — step-by-step using `deploy/backup/restore-from-snapshot.sh`
  - [x] Section C: Data integrity verification (table count check + health endpoint check)
  - [x] Section D: Bringing the application back online post-restore
  - [x] Quarterly drill reminder: record result in `deploy/backup/drill-log.md`

- [x] Task 2: Create `docs/deployment/traefik-tls.md` (AC-2)
  - [x] How Traefik is configured (config files, dashboard status, entry points)
  - [x] How TLS certificates are issued (HTTP-01 challenge, acme.json storage location)
  - [x] Expected renewal timeline (Let's Encrypt renews ~30 days before expiry)
  - [x] How to check current certificate status from the Node
  - [x] What to do if a certificate fails to renew (step-by-step recovery)

- [x] Task 3: Create `docs/deployment/monitoring.md` (AC-3)
  - [x] How to access Grafana (URL, credentials)
  - [x] Grafana dashboards that exist and what they show
  - [x] Alert inventory table: all 17 alerts with severity and meaning
  - [x] Response actions for each alert, organized by severity group
  - [x] How to silence an alert temporarily (Grafana UI instructions)

- [x] Task 4: Create `docs/deployment/runbook.md` (AC-4)
  - [x] Scenario 1: Disk Exhaustion — detection, remediation, verification
  - [x] Scenario 2: PostgreSQL Service Down — detection, remediation, verification
  - [x] Scenario 3: Redis OOM / Container Restart Loop — detection, remediation, verification

---

## Dev Notes

### Scope: docs-only — NO code, YAML, config, or script changes

This is a pure documentation story. The 4 scripts that perform restores already exist and are fully implemented. This story only creates the human-readable guides that explain when and how to use them.

**Files to CREATE (all 4 are new):**
- `docs/deployment/backup-restore.md`
- `docs/deployment/traefik-tls.md`
- `docs/deployment/monitoring.md`
- `docs/deployment/runbook.md`

**Files to NOT touch (already complete):**
- `docs/deployment/first-time-setup.md`
- `docs/deployment/deploy-guide.md`
- `docs/deployment/rollback.md`
- `docs/deployment/secrets-reference.md`
- `docs/deployment/uptime-monitor.md`
- `deploy/backup/*.sh` — scripts are done; only document them
- `deploy/lgtm/*.yml`, `docker-compose.yml`, `deploy/traefik/traefik.yml` — read them for facts, do not modify
- Any Java source file
- `.github/workflows/`

**Document style:** Match the tone and structure of `docs/deployment/rollback.md`:
- Short intro paragraph ("This guide covers…")
- Horizontal rule (`---`) section separators
- Named section headings (`## Step N:` or `## Section Title`)
- Bash code blocks for all commands
- Inline notes with `>` blockquote callouts for critical warnings
- No marketing language; direct, instructional prose

---

### Task 1 Detail: backup-restore.md

**Purpose:** FR-24 — the backup/restore guide.

**Two restore paths — be explicit about which to use when:**

| Situation | Use |
|---|---|
| Node hardware failure, volume corruption, or catastrophic data loss | Volume snapshot restore (`restore-from-snapshot.sh`) |
| Database corruption, accidental data deletion, application bug | pg_dump restore (`restore-from-dump.sh`) |
| Testing quarterly restore drill | Either (record in `deploy/backup/drill-log.md`) |

**Path A: pg_dump restore**

Script location: `deploy/backup/restore-from-dump.sh`

Usage (run as root on the Node from `/opt/skillars`):
```bash
# Restore the latest dump:
sudo bash deploy/backup/restore-from-dump.sh latest

# Restore a specific dump by S3 object key:
sudo bash deploy/backup/restore-from-dump.sh pg-backups/skillars-20260603T060000Z.sql.gz
```

What the script does (document each step so developer knows what happened):
1. Loads `/opt/skillars/.env` for `HOS_*` and `POSTGRES_*` vars
2. Lists objects in HOS bucket to find the latest (if `latest` specified)
3. Checks `/tmp` has enough space before download
4. Downloads dump to `/tmp/skillars-restore-<timestamp>.sql.gz`
5. Validates gzip integrity before touching the database
6. Stops the `app` service (`docker compose stop app`)
7. Drops and recreates the database inside the running postgres container
8. Pipes the decompressed dump through `docker exec -i` to psql
9. Integrity check: counts public tables — fails if count < 1
10. Starts `app` service (`docker compose start app`)
11. Waits up to 90s for app container health to become `healthy`
12. Deletes the temp file

Data integrity verification (already done by script — document what the developer sees):
- Script output: `[restore-dump] Integrity check: N public tables found.`
- Then health wait: `[restore-dump] Waiting for app health (up to 90s)...`
- Then final: `[restore-dump] Restore complete.`
- External verification: `curl -s https://${DOMAIN}/actuator/health | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['status'])"`
  - Expected: `UP`

**Path B: volume snapshot restore**

Script location: `deploy/backup/restore-from-snapshot.sh`

This path requires a manual Hetzner Cloud Console step in the middle — the script pauses and prompts.

Usage (run as root on the Node):
```bash
sudo bash /opt/skillars/deploy/backup/restore-from-snapshot.sh
```

Steps the script performs:
1. Stops ALL Docker services (`docker compose down`)
2. Unmounts `/opt/skillars/data` (the Hetzner Volume at `/dev/sdb`)
3. **PAUSES** — prints instructions for Hetzner Console:
   - Go to Hetzner Cloud Console → Volumes
   - Detach the current volume
   - Create a new Volume from the target snapshot
   - Attach the new volume to the server as `/dev/sdb`
   - Press ENTER to continue
4. Mounts `/dev/sdb` at `/opt/skillars/data`
5. Restores subdirectory ownership (prometheus: 65534:65534, loki/tempo: 10001:10001, grafana: 472:472)
6. Starts all services (`docker compose up -d`)
7. Waits up to 120s for app health

**CRITICAL NOTE for snapshot restore:** This restores ALL data (PostgreSQL, Redis-on-disk, Loki, Prometheus, Grafana) to the snapshot point. Any data written between the snapshot and the failure is lost. RPO for snapshots is 24 hours.

**Quarterly drill:** After every drill (whether pg_dump or snapshot path), record results in `deploy/backup/drill-log.md`. Include: date, path used (dump/snapshot), environment (must be non-production), outcome (pass/fail), and RTO achieved.

---

### Task 2 Detail: traefik-tls.md

**Purpose:** FR-26 — Traefik and TLS reference.

**Traefik version:** `traefik:v3.3`

**Configuration files:**
- `deploy/traefik/traefik.yml` — static config (entry points, ACME resolver, dashboard, ping)
- `docker-compose.yml` labels on `app` and `grafana` services — dynamic routing config
- `/opt/skillars/traefik/acme.json` on the Node — ACME certificate storage (NOT in the repository; auto-created by Traefik)

**What the dashboard status is:** Dashboard is DISABLED (`api.dashboard: false` in `traefik.yml`). There is no `/dashboard` endpoint. An unauthenticated request to port 8080 (the Traefik API port) is not exposed on the host network — this satisfies FR-13.

**Entry points:**
- `web` (:80) — redirects all HTTP to HTTPS permanently
- `websecure` (:443) — terminates TLS

**TLS certificate issuance:**
- Resolver: `letsencrypt` using HTTP-01 challenge
- HTTP-01 requires Traefik to respond on port 80 — the `web` entry point must be reachable during initial issuance
- Traefik automatically creates and populates `/opt/skillars/traefik/acme.json` on first run
- Email for expiry warnings: `LETSENCRYPT_EMAIL` from `.env` (set at startup via `--certificatesresolvers.letsencrypt.acme.email=${LETSENCRYPT_EMAIL}` CLI arg in docker-compose.yml)

**Renewal timeline:**
- Let's Encrypt certificates are valid for 90 days
- Traefik checks for renewal every 24 hours
- Traefik initiates renewal when fewer than 30 days remain (standard Traefik behavior for Let's Encrypt)
- No operator action is required for routine renewal

**How to check the certificate expiry date from the Node:**
```bash
# Connect to the production server
ssh <SSH_USER>@<SSH_HOST>

# Check certificate expiry using openssl via TLS handshake:
echo | openssl s_client -servername ${DOMAIN} -connect ${DOMAIN}:443 2>/dev/null \
  | openssl x509 -noout -dates

# Expected output:
# notBefore=May 25 00:00:00 2026 GMT
# notAfter=Aug 23 23:59:59 2026 GMT
```

Alternatively, check raw acme.json (may be empty if Traefik just started):
```bash
# Count certificates stored in acme.json:
sudo python3 -c "import json; d=json.load(open('/opt/skillars/traefik/acme.json')); print(len(d.get('letsencrypt',{}).get('Certificates', [])),'certificate(s) stored')"
```

**What to do if certificate fails to renew:**

Scenario A — Traefik is running but certificate is not renewing:
1. Check Traefik logs for ACME errors: `docker compose logs --tail=100 traefik | grep -i acme`
2. Common cause: port 80 was blocked at the Hetzner firewall during renewal attempt. Verify firewall still allows inbound TCP 80: `cat deploy/firewall/firewall-rules.json`
3. If firewall was modified, reapply: `bash deploy/firewall/apply-firewall.sh`
4. Restart Traefik to trigger an immediate renewal attempt: `docker compose restart traefik`
5. Wait 60 seconds, then recheck with `openssl s_client` above

Scenario B — acme.json is empty or corrupt (e.g., after volume restore or accidental deletion):
1. Stop Traefik: `docker compose stop traefik`
2. Delete the stale acme.json: `sudo rm -f /opt/skillars/traefik/acme.json`
3. Traefik creates a fresh acme.json on startup and immediately requests new certificates
4. Start Traefik: `docker compose start traefik`
5. Wait 2–3 minutes for Let's Encrypt to issue the certificate (HTTP-01 challenge)
6. Verify: `echo | openssl s_client -servername ${DOMAIN} -connect ${DOMAIN}:443 2>/dev/null | openssl x509 -noout -dates`

Scenario C — Let's Encrypt rate limit hit (5 failures per domain per hour):
1. Wait 1 hour before retrying
2. If testing repeatedly on the same domain, use the Let's Encrypt staging environment — but this is production only, so just wait

**Let's Encrypt staging note:** There is no staging flag in the current config. Do NOT add one for production troubleshooting — it will issue an untrusted certificate.

---

### Task 3 Detail: monitoring.md

**Purpose:** FR-27 — monitoring reference with documented response for EVERY alert.

**Grafana access:**
- URL: `https://${MONITORING_DOMAIN}` — value is from `.env` (`MONITORING_DOMAIN`)
- Default credentials: username = value of `GF_SECURITY_ADMIN_USER` (default: `admin`), password = value of `GF_SECURITY_ADMIN_PASSWORD`
- Both are in `/opt/skillars/.env` on the Node and in `docs/deployment/secrets-reference.md`
- Grafana is only accessible via HTTPS through Traefik — there is no direct port exposure

**Dashboards:**
- "Skillars Dashboard" — provisioned from `deploy/lgtm/skillars-dashboard.json`. Shows application-level metrics (payment throughput, provider latency, JVM metrics, DB connection pool, circuit breaker state).
- No custom Loki/Tempo/Prometheus dashboards are provisioned — use Grafana's built-in Explore view for ad-hoc queries

**Alerts folder in Grafana:** "Skillars Alerts" — all configured alert rules appear here under the Alerting → Alert rules section

**Alert notification:** All alerts route to `notify-ops` contact point (email: `GF_ALERT_NOTIFY_EMAIL`, Slack: `GF_SLACK_WEBHOOK_URL`). Notification policy: `group_wait: 30s`, `group_interval: 5m`, `repeat_interval: 4h`.

**COMPLETE ALERT INVENTORY — all 17 alerts must be documented with response actions:**

*Group: skillars-loki-alerts (Loki source)*

| Alert | Severity | Meaning | Action |
|---|---|---|---|
| WebhookPermanentFailure | high | A payment webhook exhausted all retry attempts and was moved to the dead-letter state. The merchant will not receive the payment status update automatically. | 1. Check Loki for the transaction ID: search `{service="skillars"} |= "FAILED_PERMANENT"`. 2. Identify the affected `transactionId`. 3. Check merchant webhook endpoint reachability. 4. Re-trigger the webhook manually via the Admin API if the endpoint is restored. 5. If the endpoint is permanently down, notify the merchant through an alternative channel. |
| ReconciliationDiscrepancy | warning | The reconciliation job found a HIGH-severity discrepancy between provider records and internal payment state. At least one payment may be in an incorrect state. | 1. Check Loki for the reconciliation report: search `{service="skillars"} |= "severity=\"HIGH\""`. 2. Identify affected `transactionId`(s) and the discrepancy type. 3. Compare against provider status via the provider's portal or API. 4. Manually correct the payment state via the Admin API if needed. 5. Monitor for repeat discrepancies — repeated alerts indicate a systemic provider sync issue. |

*Group: skillars-prometheus-alerts (Prometheus source)*

| Alert | Severity | Meaning | Action |
|---|---|---|---|
| PaymentFailureRateHigh | critical | Payment failure rate exceeded 5% over a 5-minute window. May indicate provider outage, misconfiguration, or upstream fraud spike. | 1. Check circuit breaker state in the Skillars Dashboard — if a breaker is OPEN, the provider is down (see OrangeCircuitBreakerOpen / MtnCircuitBreakerOpen action). 2. Check `docker compose logs app --tail=100` for `OrchestratorError`. 3. Check provider status pages. 4. If neither provider is down, check `FraudBlockRateHigh` — elevated fraud blocks also count as failures. |
| OrangeCircuitBreakerOpen | critical | The Orange Money circuit breaker tripped OPEN. All new Orange Money payments are immediately rejected with 503. | 1. Check Orange Money API status page. 2. Check `docker compose logs app --tail=100` for connection errors to Orange. 3. If provider is down: wait for automatic half-open retry (circuit breaker retries after a configured wait). 4. If provider is restored but breaker hasn't closed: restart the app service to reset the in-memory circuit breaker state: `docker compose restart app`. |
| MtnCircuitBreakerOpen | critical | The MTN Mobile Money circuit breaker tripped OPEN. All new MTN payments are immediately rejected with 503. | Same as OrangeCircuitBreakerOpen — substitute MTN MoMo API status page and MTN-related log entries. |
| ProviderLatencyP99Critical | critical | A payment provider's p99 response latency exceeded 10 seconds. Payments are still succeeding but very slowly — SLO breach risk. | 1. Identify which `provider` label is firing from the alert annotation. 2. Check provider status page for degraded performance. 3. Check `docker compose logs app --tail=100` for timeout patterns. 4. If latency is sustained > 15 minutes, consider temporarily routing traffic to the other provider if multi-provider failover is supported. |
| CallbackRateZero | high | No payment callbacks received in 5 minutes while payments are being initiated (> 0.1 payments/s). Payments may be stuck in PROCESSING state indefinitely. | 1. Verify the provider webhook URL configured in the provider's portal matches the application's public callback endpoint. 2. Check provider firewall/IP whitelist — the Node's public IP must be whitelisted. 3. Check `docker compose logs app --tail=100` for callback receipt or rejection logs. 4. If callbacks are being received but rejected: check authentication signature verification in logs. |
| ProviderLatencyP95High | high | A payment provider's p95 latency exceeded 5 seconds. Not yet a SLO breach but trending toward one. | 1. Identify the `provider` label from the Grafana alert. 2. Monitor — this is a warning signal, not a critical failure. 3. If it persists > 30 minutes, escalate to ProviderLatencyP99Critical response. |
| DbConnectionPoolHigh | high | HikariCP database connection pool usage exceeded 80%. Starvation imminent if unchecked. | 1. Check `docker compose logs app --tail=100` for slow queries or blocked threads. 2. Check current pool size: search Loki or Prometheus for `hikaricp_connections_active`. 3. If caused by a query backlog: identify the slow query in PostgreSQL: `docker exec <postgres-cid> psql -U ${POSTGRES_USER} -d ${POSTGRES_DB} -c "SELECT pid, query, state, wait_event_type, now()-query_start AS duration FROM pg_stat_activity WHERE state != 'idle' ORDER BY duration DESC;"`. 4. Terminate blocking long-running queries if identified: `SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE duration > interval '30 seconds' AND state != 'idle';`. |
| FraudBlockRateHigh | warning | More than 20% of payment attempts are being blocked by fraud rules. Rules may be misconfigured or there is a genuine fraud spike. | 1. Check Loki for fraud block reason: search `{service="skillars"} |= "fraud_blocked"`. 2. Determine if the blocked transactions are from a single source (potential attack) or distributed (rules misconfiguration). 3. If misconfigured: review and adjust fraud rule thresholds — no code change required, this is a configuration concern. 4. If genuine fraud: no action required on the infrastructure side; alert the fraud/compliance team. |
| JvmHeapHigh | warning | JVM heap usage exceeded 85%. The application may experience GC pauses or, if sustained, an OOM restart. | 1. Check for a memory leak pattern: query Prometheus for `jvm_memory_used_bytes{area="heap"}` over time. 2. If heap is growing monotonically, the application may have a leak — plan a restart during low traffic: `docker compose restart app`. 3. Monitor after restart — if heap grows back quickly, escalate to a code-level investigation. 4. If heap is spiking and returning: this is likely a traffic spike; monitor and consider scaling. |
| CallbackFailureRatioHigh | warning | More than 10% of received callbacks reference unknown transaction IDs. May be stale callbacks from a previous deployment, test callbacks from the provider, or a state management bug. | 1. Check Loki: search `{service="skillars"} |= "callback_failed"` for the reason. 2. If callbacks reference old transaction IDs: these may be from a previous payment session and are expected — suppress if confirmed harmless. 3. If new transactions are failing: investigate the transaction lifecycle for state loss. |

*Group: skillars-infra-alerts (Prometheus source)*

| Alert | Severity | Meaning | Action |
|---|---|---|---|
| NodeExporterDown | critical | The `node_exporter` container is not running. Disk and memory alerts are now blind — no disk or memory data is being collected. | 1. Check: `docker compose ps node_exporter`. 2. If stopped: `docker compose start node_exporter`. 3. If failing health check: `docker compose logs node_exporter --tail=50`. 4. If the container keeps crashing, re-pull the image: `docker compose pull node_exporter && docker compose up -d --no-deps node_exporter`. |
| AppDown | critical | The Spring Boot application is not reachable by Prometheus (the `/manage/prometheus` metrics endpoint returned no data for > 1 minute). The app may be crashed or its container unhealthy. | 1. Check container state: `docker compose ps app`. 2. Check logs: `docker compose logs --tail=50 app`. 3. If stopped: `docker compose start app`. 4. If crashed in a loop: `docker compose logs --tail=200 app | grep -i "error\|exception\|oom"`. 5. If OOM: increase memory limit (requires docker-compose.yml change and re-deploy). 6. If application startup failure: check `/opt/skillars/.env` for missing required vars. |
| DiskDataVolumeHigh | warning | The Hetzner Volume (`/opt/skillars/data`) is more than 80% full. PostgreSQL data, Loki logs, Prometheus metrics, and Grafana state are stored here. | See runbook: Disk Exhaustion scenario. Quick remediation: `docker system prune -f` (removes unused images/layers). For full steps see `docs/deployment/runbook.md`. |
| DiskRootHigh | warning | The root disk (`/`) is more than 80% full. Docker image layers, container logs, and OS files are stored here. | See runbook: Disk Exhaustion scenario. Most common cause: Docker image accumulation (`docker image ls --format '{{.Size}} {{.Repository}}:{{.Tag}}' | sort -h`). Quick remediation: `docker image prune -a -f`. |
| MemoryPressureHigh | warning | Node memory usage exceeded 85%. Container OOM kills may follow if unchecked. | See runbook: Redis OOM scenario for one common cause. General: check `docker stats --no-stream` to identify the highest-memory container. If a container is approaching its Docker limit, it will be OOM-killed by the kernel. |

**How to silence an alert in Grafana (temporary suppression during maintenance):**
1. Go to Grafana → Alerting → Silences → New silence
2. Set a label matcher for the specific alert (e.g., `alertname = DiskDataVolumeHigh`)
3. Set the silence duration (e.g., 2 hours)
4. Add a comment explaining the reason
5. Click "Create". The alert fires normally but notifications are suppressed during the silence window.

---

### Task 4 Detail: runbook.md

**Purpose:** FR-28 — operational runbook for 3 failure scenarios.

**Format for each scenario:**
1. Detection (how to confirm the failure)
2. Remediation (step-by-step, with exact commands)
3. Verification (how to confirm resolution)

---

**Scenario 1: Disk Exhaustion on the Node**

Detection:
- `DiskDataVolumeHigh` alert fires (data volume `/opt/skillars/data` > 80%)
- `DiskRootHigh` alert fires (root disk `/` > 80%)
- Or: application writes fail with "no space left on device" in logs

```bash
# Confirm disk usage:
df -h
# Look for the line showing / or /opt/skillars/data at > 80% use

# Find top consumers on the data volume:
du -sh /opt/skillars/data/*
# Typical large consumers: postgres/, loki/, prometheus/, grafana/

# Find top consumers on root:
du -sh /var/lib/docker/overlay2/* 2>/dev/null | sort -h | tail -20
# Usually Docker image layers are the culprit on root
```

Remediation steps:

*For root disk (`/`) exhaustion — usually Docker layer accumulation:*
```bash
# Remove unused Docker images (safe — only affects untagged/unused images):
docker image prune -a -f

# Remove stopped containers:
docker container prune -f

# Remove unused volumes (CAUTION: do NOT prune named volumes — redis-data is a named volume):
docker volume prune -f --filter "label!=com.docker.compose.version"
# Note: 'docker volume prune' without filters would delete redis-data — do NOT run it without the filter

# Remove build cache:
docker builder prune -f
```

*For data volume (`/opt/skillars/data`) exhaustion:*
```bash
# Check Loki storage size:
du -sh /opt/skillars/data/loki/

# Check Prometheus storage size:
du -sh /opt/skillars/data/prometheus/

# If Loki is oversized (retention is 30 days — check loki.yml):
# Loki self-prunes per its retention config. If it's oversized, the retention may not have run yet.
# Restart Loki to trigger compaction: docker compose restart loki
# Wait 5 minutes, then re-check size.

# If Prometheus is oversized (retention is 15 days):
# Prometheus self-prunes. Restart to trigger: docker compose restart prometheus
```

Verification:
```bash
df -h
# Expected: data volume and root disk both below 80%

# Confirm services are still healthy after cleanup:
docker compose ps
# All services should show: health: healthy or Up
```

---

**Scenario 2: PostgreSQL Service Down**

Detection:
- `AppDown` alert fires (Spring Boot cannot connect to DB → health endpoint fails → Prometheus scrape fails)
- Or: application logs contain `Connection refused` or `FATAL: the database system is starting up`
- Or: `docker compose ps postgres` shows `Exit` or `Restarting`

```bash
# Confirm state:
docker compose ps postgres
# If healthy: postgres is running — check app side instead

# Check postgres container logs:
docker compose logs --tail=100 postgres
```

Common causes:
- Container ran out of memory and was OOM-killed by the kernel (check `dmesg | grep -i oom`)
- Volume ran out of disk space (check `df -h /opt/skillars/data`)
- Container health check is failing (check logs for startup errors)

Remediation:
```bash
# If postgres exited normally and can be restarted:
docker compose start postgres

# Wait 15 seconds for postgres to start accepting connections, then:
docker compose ps postgres
# Expected: health: healthy

# If postgres is in a restart loop (check state: Restarting):
docker compose logs --tail=200 postgres
# Identify the error before attempting a restart

# If disk is full (postgres cannot write WAL):
# First free disk space (see Disk Exhaustion scenario), then:
docker compose restart postgres
```

Verification:
```bash
# Check postgres is healthy:
docker compose ps postgres

# Check the app recovers (Spring Boot retries DB connection on startup):
docker compose ps app
# If app is showing Exited or Restarting, restart it after postgres is healthy:
docker compose restart app

# Wait ~30 seconds, then verify app health:
CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

---

**Scenario 3: Redis OOM / Container Restart Loop**

Detection:
- `MemoryPressureHigh` alert fires AND Redis container is restarting
- Or: `docker compose ps redis` shows `Restarting`
- Or: application logs contain `NOAUTH` or `ERR max number of clients reached` or `Connection refused` to Redis
- Or: `docker compose logs redis --tail=50` shows `OOM command not allowed` or the container keeps restarting

Context: Redis container has a 256m Docker memory limit (`docker-compose.yml`: `resources.limits.memory: 256m`). Redis uses `--appendonly yes` (AOF persistence to `redis-data` named volume). When Docker's memory limit is exceeded, the kernel OOM-kills the Redis process and Docker restarts the container (`restart: unless-stopped`).

```bash
# Confirm Redis state:
docker compose ps redis
docker compose logs --tail=50 redis

# Check if Redis is being OOM-killed by kernel:
dmesg | grep -i oom | tail -10
```

Remediation:

*If Redis is in a restart loop due to large AOF/RDB:*
```bash
# The restart loop typically self-resolves: Docker restarts Redis, Redis loads AOF, if the AOF is not corrupt Redis comes up.
# Wait 60 seconds and check: docker compose ps redis

# If Redis cannot start due to AOF corruption:
docker compose stop redis
# WARNING: the following CLEARS all Redis data. Only do this if data loss is acceptable.
# All session tokens and distributed locks will be invalidated; active user sessions end.
docker volume inspect redis-data  # confirm it's the right volume
docker run --rm -v javatemplate_redis-data:/data alpine sh -c "ls -la /data && rm -f /data/appendonly.aof /data/dump.rdb"
docker compose start redis
```

*If Redis container keeps being OOM-killed and data is not corrupted:*
```bash
# Check current Redis memory usage:
docker exec $(docker compose ps -q redis) redis-cli info memory | grep -E "used_memory_human|maxmemory_human"

# Flush non-critical caches (sessions will be invalidated — users must re-login):
docker exec $(docker compose ps -q redis) redis-cli FLUSHDB

# Restart Redis:
docker compose restart redis
```

*If the node-level MemoryPressureHigh alert is the root cause (not just Redis):*
```bash
# Check all container memory usage:
docker stats --no-stream
# Identify which container is consuming the most and follow its-specific runbook scenario
```

Verification:
```bash
# Confirm Redis is running and responding:
docker compose ps redis
docker exec $(docker compose ps -q redis) redis-cli ping
# Expected: PONG

# Confirm app reconnected to Redis:
CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
# Note: if app container was stopped during Redis outage, restart it: docker compose restart app
```

---

### Infrastructure Context (carried forward from deploy-3-1, 3-2, 3-3)

**Server root:** `/opt/skillars` — all Docker Compose commands must use `-f /opt/skillars/docker-compose.yml` if not run from `/opt/skillars`

**Hetzner Volume:** `/dev/sdb` mounted at `/opt/skillars/data` — PostgreSQL, Loki, Prometheus, Grafana all persist here

**Management port (8367) is NOT exposed to host** — never `curl localhost:8367` from the Node directly; use `docker exec`:
```bash
CID=$(docker compose ps -q --status running app | head -1)
docker exec "$CID" wget -qO- http://localhost:8367/manage/health
```

**External health URL:** `https://${DOMAIN}/actuator/health` — Traefik rewrites `/actuator/health` → `/manage/health` before forwarding to port 8367

**Grafana URL:** `https://${MONITORING_DOMAIN}` — value of `MONITORING_DOMAIN` in `.env`

**All alert routing:** single contact point `notify-ops` → email + Slack (both `GF_ALERT_NOTIFY_EMAIL` and `GF_SLACK_WEBHOOK_URL` must be set in `.env` for notifications to reach the operator)

**Backup scripts:**
- `deploy/backup/restore-from-dump.sh` — pg_dump restore; runs on the Node; requires root
- `deploy/backup/restore-from-snapshot.sh` — volume snapshot restore; runs on the Node; requires root + Hetzner Console access
- `deploy/backup/drill-log.md` — record quarterly drill results here

**docker-compose.yml service names:** `app`, `postgres`, `redis`, `traefik`, `prometheus`, `loki`, `tempo`, `grafana`, `node_exporter`

**Named volumes:** `redis-data` (Redis AOF persistence) — do NOT prune this with `docker volume prune` without the label filter

**Traefik config:** `api.dashboard: false` — dashboard is disabled; no `/dashboard` endpoint exists

**acme.json location on Node:** `/opt/skillars/traefik/acme.json` — not in the repository; auto-managed by Traefik

---

### Previous Story Intelligence (deploy-3-3 learnings)

- Docker service names (not container names) are the correct reference: `docker compose logs app`, NOT `docker logs skillars-app-1`
- `docker compose ps -q --status running <service> | head -1` reliably gets the running container ID
- `${VAR:-}` (empty default) vs `${VAR:?error}` (required var): Grafana notification vars use empty defaults; secrets in app service use `:?` required pattern — do not confuse in any docs written
- Port 8367 is internal only — the standard verification pattern is always `docker exec $CID wget -qO- http://localhost:8367/manage/health`
- Grafana provisioning (YAML files) is declarative and takes effect on Grafana container restart

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 3.4 — Operational Documentation Suite]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-24 — Backup/restore guide]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-26 — Traefik and TLS reference]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-27 — Monitoring reference with documented response actions]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-28 — Operational runbook, 3 scenarios]
- [Source: deploy/backup/restore-from-dump.sh — exact script behavior, DB drop/recreate, integrity check (table count), 90s health wait]
- [Source: deploy/backup/restore-from-snapshot.sh — exact script behavior, manual Hetzner console step, subdirectory ownership (65534:65534 prometheus, 10001:10001 loki/tempo, 472:472 grafana), 120s health wait]
- [Source: deploy/backup/drill-log.md — quarterly drill record location]
- [Source: deploy/traefik/traefik.yml — api.dashboard: false, api.insecure: false, httpChallenge entry point: web, acme storage path, log level: ERROR, ping enabled]
- [Source: docker-compose.yml — traefik image v3.3, acme.json volume mount /opt/skillars/traefik/acme.json, LETSENCRYPT_EMAIL CLI arg injection]
- [Source: docker-compose.yml — app Traefik labels: /actuator/health → /manage/health rewrite, app-main-svc port 9990, app-health-svc port 8367]
- [Source: docker-compose.yml — redis:7-alpine, --appendonly yes, memory limit 256m, restart: unless-stopped, redis-data named volume]
- [Source: docker-compose.yml — app memory limit 2g, postgres memory limit 1536m]
- [Source: deploy/lgtm/alerts.yml — all Prometheus alert rules: 11 in skillars-alerts group + 5 in skillars-infra-alerts group including NodeExporterDown]
- [Source: deploy/lgtm/grafana-alerts.yml — all Grafana alert rules: 2 in skillars-loki-alerts + 3 in skillars-prometheus-alerts + 4 in skillars-infra-alerts; contactPoints notify-ops (email + Slack); policies repeat_interval: 4h]
- [Source: docker-compose.yml — grafana service GF_SERVER_ROOT_URL=https://${MONITORING_DOMAIN}, image grafana/grafana:11.4.0]
- [Source: docs/deployment/rollback.md — document style reference (intro para, horizontal rules, bash code blocks, blockquote callouts)]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None.

### Completion Notes List

- Created `docs/deployment/backup-restore.md`: covers both restore paths (pg_dump and volume snapshot), verified step counts match actual scripts, includes data integrity verification and quarterly drill reminder.
- Created `docs/deployment/traefik-tls.md`: covers all three configuration file sources, entry points, HTTP-01 challenge flow, renewal timeline, certificate status check commands, and three failure scenarios (not-renewing, corrupt acme.json, rate limit).
- Created `docs/deployment/monitoring.md`: covers Grafana access, Skillars Dashboard, all 17 unique alerts from `alerts.yml` and `grafana-alerts.yml` with severity, meaning, and specific response actions; alert silence instructions included.
- Created `docs/deployment/runbook.md`: covers all three failure scenarios (Disk Exhaustion, PostgreSQL Down, Redis OOM) each with detection commands, step-by-step remediation, and verification.
- No code, YAML, config, or script files were modified. All documents match the style of `docs/deployment/rollback.md`.

### File List

- docs/deployment/backup-restore.md (created)
- docs/deployment/traefik-tls.md (created)
- docs/deployment/monitoring.md (created)
- docs/deployment/runbook.md (created)

## Change Log

- 2026-06-05: Created operational documentation suite (4 new docs) — backup-restore.md, traefik-tls.md, monitoring.md, runbook.md

---

### Review Findings

#### Patch — must be resolved before marking done

- [x] [Review][Patch] P-01: Wrong Redis volume name `javatemplate_redis-data` — actual Docker volume is `skillars_redis-data` (project prefix from /opt/skillars) [docs/deployment/runbook.md:201]
- [x] [Review][Patch] P-02: `docker volume prune --filter "label!=..."` filter may not protect `redis-data` on older Docker Engine; safer to document explicit alternative [docs/deployment/runbook.md:46]
- [x] [Review][Patch] P-03: Missing `--status running` filter on Redis `docker exec` commands (3 occurrences); hazardous during restart-loop scenario [docs/deployment/runbook.md:212,216,236]
- [x] [Review][Patch] P-04: Missing `--status running` on postgres CID capture [docs/deployment/backup-restore.md:126, docs/deployment/monitoring.md:260]
- [x] [Review][Patch] P-05: `${DOMAIN}` used in verification commands without sourcing `.env`; expands to empty string in operator shell [docs/deployment/backup-restore.md, docs/deployment/traefik-tls.md]
- [x] [Review][Patch] P-06: `${POSTGRES_PASSWORD}`, `${POSTGRES_USER}`, `${POSTGRES_DB}` unexpanded in Section C psql commands — no `source /opt/skillars/.env` step [docs/deployment/backup-restore.md:127-134]
- [x] [Review][Patch] P-07: No recovery path documented if dump restore integrity check fails (app left stopped, database empty) [docs/deployment/backup-restore.md:Section A]
- [x] [Review][Patch] P-08: No snapshot verification step before creating new volume; wrong snapshot selection causes silent data overwrite [docs/deployment/backup-restore.md:Section B]
- [x] [Review][Patch] P-09: `deploy/backup/drill-log.md` does not exist in repository; no creation step documented [docs/deployment/backup-restore.md:Quarterly Drill]
- [x] [Review][Patch] P-10: acme.json deleted in Scenario B without suggesting a backup first; deletion is irreversible [docs/deployment/traefik-tls.md:Scenario B]
- [x] [Review][Patch] P-11: Python3 acme.json inspection command lacks `sudo`; fails with PermissionError when run as non-root [docs/deployment/traefik-tls.md]
- [x] [Review][Patch] P-12: No `cd /opt/skillars` prerequisite in traefik-tls.md scenarios; `docker compose` commands target wrong project if run from another directory [docs/deployment/traefik-tls.md]
- [x] [Review][Patch] P-13: Let's Encrypt rate limit description incorrect ("5 failed validation attempts per domain per hour"); actual lockout can be up to 1 week [docs/deployment/traefik-tls.md:Scenario C]
- [x] [Review][Patch] P-14: Traefik restart misleadingly described as triggering "immediate renewal attempt" regardless of window; only applies when fewer than 30 days remain [docs/deployment/traefik-tls.md:Scenario A step 4]
- [x] [Review][Patch] P-15: `pg_terminate_backend` missing critical warning about killing in-flight payment transactions [docs/deployment/monitoring.md:DbConnectionPoolHigh]
- [x] [Review][Patch] P-16: `$CID` undefined in step 3 terminate block (separate bash code block); developer running it alone gets `docker exec "" psql ...` failure [docs/deployment/monitoring.md:DbConnectionPoolHigh step 3]
- [x] [Review][Patch] P-17: `docker system prune -f` comment omits that it deletes unused images including previous app image needed for rollback [docs/deployment/monitoring.md:DiskDataVolumeHigh]
- [x] [Review][Patch] P-18: `CallbackRateZero` meaning inaccurate — describes co-condition as "payments being actively initiated" but expr uses `rate(payment_success_total[5m])` (successful completions) [docs/deployment/monitoring.md:CallbackRateZero]
- [x] [Review][Patch] P-19: `AppDown` response uses `docker compose start app` which is a no-op if container is running-but-unhealthy; should check state first or prefer `restart` [docs/deployment/monitoring.md:AppDown]

#### Defer — pre-existing issues, not introduced by this story

- [x] [Review][Defer] D-01: Integrity check (table count ≥ 1) trivially weak — pre-existing script limitation; a partially-loaded dump with one table passes [docs/deployment/backup-restore.md] — deferred, pre-existing
- [x] [Review][Defer] D-02: DROP DATABASE may fail if other services hold open DB connections — pre-existing script limitation; script stops only `app` [docs/deployment/backup-restore.md] — deferred, pre-existing
- [x] [Review][Defer] D-03: Hardcoded container UIDs (65534, 10001, 472) not tied to image versions; upstream UID changes break ownership silently — pre-existing script limitation [docs/deployment/backup-restore.md] — deferred, pre-existing
- [x] [Review][Defer] D-04: /tmp space check validates compressed size only; decompressed SQL (5-10x) may exhaust /tmp mid-restore — pre-existing script limitation [docs/deployment/backup-restore.md] — deferred, pre-existing
- [x] [Review][Defer] D-05: Health-wait APP_CID capture races container registration; loop may timeout even when app is healthy — pre-existing script limitation [docs/deployment/backup-restore.md] — deferred, pre-existing
- [x] [Review][Defer] D-06: WebhookPermanentFailure Admin API re-trigger step has no endpoint or auth reference — out of scope, Admin API not defined in this story [docs/deployment/monitoring.md] — deferred, pre-existing
- [x] [Review][Defer] D-07: CallbackRateZero public callback endpoint undocumented — application-specific, out of scope [docs/deployment/monitoring.md] — deferred, pre-existing
