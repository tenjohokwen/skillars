# Operational Runbook

This guide covers step-by-step remediation for four production failure scenarios: disk exhaustion,
PostgreSQL service down, Redis OOM or container restart loop, and a booking stuck in
`CAPTURE_PENDING`. Each scenario includes detection, remediation, and verification. A developer can
resolve each scenario by following this runbook alone.

---

## Scenario 1: Disk Exhaustion on the Node

### Detection

- `DiskDataVolumeHigh` alert fires: data volume `/opt/skillars/data` exceeds 80% used
- `DiskRootHigh` alert fires: root disk `/` exceeds 80% used
- Or: application writes fail with "no space left on device" in logs

```bash
# Confirm disk usage:
df -h
# Look for / or /opt/skillars/data showing > 80% Use%

# Find top consumers on the data volume:
du -sh /opt/skillars/data/*
# Typical large consumers: postgres/, loki/, prometheus/, grafana/

# Find top consumers on root (usually Docker image layers):
du -sh /var/lib/docker/overlay2/* 2>/dev/null | sort -h | tail -20
```

### Remediation

**For root disk (`/`) — usually Docker layer accumulation:**

```bash
# Remove unused Docker images (safe — only removes untagged or unreferenced images):
docker image prune -a -f

# Remove stopped containers:
docker container prune -f

# Remove build cache:
docker builder prune -f

# Remove unused anonymous volumes. Since Redis moved onto the Hetzner Volume bind mount
# (/opt/skillars/data/redis), the production stack declares NO named volumes at all, so this is
# now safe as written:
docker volume ls -q --filter "dangling=true" | xargs -r docker volume rm
```

> **CAUTION:** Redis AOF persistence now lives at `/opt/skillars/data/redis`, a bind mount on the Hetzner Volume, **not** in a Docker volume. Deleting that directory invalidates all active user sessions and distributed locks. `docker volume prune` can no longer touch it — but `rm -rf` on the data volume can.

**For data volume (`/opt/skillars/data`) — usually Loki or Prometheus accumulation:**

```bash
# Check Loki storage size:
du -sh /opt/skillars/data/loki/

# Check Prometheus storage size:
du -sh /opt/skillars/data/prometheus/

# If Loki is oversized (retention is 30 days configured in loki.yml):
# Loki self-prunes per its retention configuration. Restart to trigger compaction:
docker compose restart loki
# Wait 5 minutes, then re-check size.

# If Prometheus is oversized (retention is 15 days):
# Prometheus self-prunes on schedule. Restart to trigger immediate compaction:
docker compose restart prometheus
```

**All four retention windows, in one place:**

| Data | Window | Enforced by |
|---|---|---|
| Loki logs | 30 days | `loki.yml`, self-pruning |
| Prometheus metrics | 15 days | Prometheus, self-pruning |
| PostgreSQL dumps (Object Storage) | `BACKUP_RETENTION_DAYS`, default 14 days | `prune-backups.sh`, daily 03:30 UTC |
| Hetzner Cloud snapshots | `SNAPSHOT_RETENTION_DAYS`, default 7 days | `prune-backups.sh`, daily 03:30 UTC |

Backup retention keeps the newest `BACKUP_RETENTION_MIN_KEEP` (default 8) dumps regardless of age.
See [`backup-restore.md`](backup-restore.md#retention) for the safety rails and the dry-run first
run.

> **⚠️ The Hetzner Volume at `/opt/skillars/data` has no working backup.** Verified against the
> Hetzner Cloud API on 2026-08-11: that API has **no volume snapshot** — volumes support only
> attach / detach / resize / change_protection, and a Hetzner *server* snapshot explicitly excludes
> attached volumes. `volume-snapshot.sh` POSTs to a non-existent endpoint and has failed on every
> cron run since it was written, which is consistent with `drill-log.md` never having recorded a
> drill. The PostgreSQL dumps in Object Storage are unaffected and are the only working backup.
> Choosing a replacement (file-level backup of the volume, a Storage Box, or accepting dumps-only)
> is an open operational decision.

### Verification

```bash
df -h
# Expected: both / and /opt/skillars/data below 80% Use%

# Confirm all services are still healthy after cleanup:
docker compose ps
# All services should show: health: healthy or state: Up
```

---

## Scenario 2: PostgreSQL Service Down

### Detection

- `AppDown` alert fires: Spring Boot cannot connect to the database — its health endpoint fails — Prometheus scrape returns no data for more than 1 minute
- Or: application logs contain `Connection refused` or `FATAL: the database system is starting up`
- Or: `docker compose ps postgres` shows state `Exit` or `Restarting`

```bash
# Check postgres container state:
docker compose ps postgres

# Check postgres logs for the failure reason:
docker compose logs --tail=100 postgres

# Check if PostgreSQL was OOM-killed by the kernel:
dmesg | grep -i oom | tail -10

# Check if disk space caused the failure:
df -h /opt/skillars/data
```

Common causes:

- Container was OOM-killed by the kernel (kernel logs will show the kill event)
- Data volume ran out of disk space (postgres cannot write WAL files)
- Container health check is failing due to a startup error

### Remediation

**If postgres exited cleanly and can be restarted:**

```bash
docker compose start postgres

# Wait 15 seconds for postgres to start accepting connections, then verify:
docker compose ps postgres
# Expected: health: healthy
```

**If postgres is in a restart loop (state shows `Restarting`):**

```bash
# Read the logs to identify the error BEFORE attempting a restart:
docker compose logs --tail=200 postgres

# If the error is disk-related, free space first (see Disk Exhaustion scenario), then restart:
docker compose restart postgres

# If the error is an OOM kill, postgres will restart automatically (restart: unless-stopped).
# Wait 30 seconds and check state — postgres should recover on its own after a kernel OOM kill.
```

**If the app container also exited during the postgres outage:**

```bash
# After postgres is healthy, restart the app:
docker compose restart app
```

### Verification

```bash
# Confirm postgres is healthy:
docker compose ps postgres
# Expected: health: healthy

# Wait ~30 seconds for the app to reconnect to postgres, then check app health:
APP_CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$APP_CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

> **Note:** The management port 8367 is not exposed to the Node host. Always use `docker exec` — do not attempt to curl port 8367 from the Node directly.

---

## Scenario 3: Redis OOM / Container Restart Loop

### Detection

- `MemoryPressureHigh` alert fires AND the Redis container is restarting
- Or: `docker compose ps redis` shows state `Restarting`
- Or: application logs contain `NOAUTH`, `ERR max number of clients reached`, or `Connection refused` to Redis
- Or: `docker compose logs redis --tail=50` shows `OOM command not allowed` or the container keeps cycling

**Context:** The Redis container has a 256m Docker memory limit. Redis uses `--appendonly yes` with AOF persistence to `/opt/skillars/data/redis` on the Hetzner Volume. When the Docker memory limit is exceeded, the kernel OOM-kills the Redis process and Docker restarts it automatically (`restart: unless-stopped`).

```bash
# Confirm Redis state and recent logs:
docker compose ps redis
docker compose logs --tail=50 redis

# Check if Redis is being OOM-killed by the kernel:
dmesg | grep -i oom | tail -10
```

### Remediation

**If Redis is in a restart loop and the AOF is intact (most common case):**

```bash
# Docker restarts Redis automatically. The restart loop typically self-resolves as Redis loads the AOF.
# Wait 60 seconds and check:
docker compose ps redis
# Expected: state Up and health: healthy
```

**If Redis cannot start due to AOF corruption:**

```bash
docker compose stop redis

# WARNING: The following CLEARS all Redis data.
# All session tokens and distributed locks will be invalidated.
# All active user sessions will end — users must re-login.
ls -la /opt/skillars/data/redis   # confirm this is the correct directory before proceeding

rm -f /opt/skillars/data/redis/appendonly.aof /opt/skillars/data/redis/dump.rdb

docker compose start redis
```

> **CAUTION:** Only clear the AOF if Redis cannot start and you have confirmed the file is corrupt from the logs. Data loss is irreversible.

**If Redis keeps being OOM-killed and the AOF is not corrupt:**

```bash
# Check current Redis memory usage:
docker exec $(docker compose ps -q --status running redis | head -1) redis-cli info memory \
  | grep -E "used_memory_human|maxmemory_human"

# Flush the database to free memory (active user sessions will be invalidated):
docker exec $(docker compose ps -q --status running redis | head -1) redis-cli FLUSHDB

# Restart Redis:
docker compose restart redis
```

**If the node-level `MemoryPressureHigh` alert is the root cause (not just Redis):**

```bash
# Identify which container is consuming the most memory:
docker stats --no-stream
# Find the container with the highest MEM USAGE and follow its specific runbook scenario
```

### Verification

```bash
# Confirm Redis is running and responding:
docker compose ps redis

docker exec $(docker compose ps -q --status running redis | head -1) redis-cli ping
# Expected: PONG

# Confirm the app has reconnected to Redis:
APP_CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
docker exec "$APP_CID" wget -qO- http://localhost:8367/manage/health
# Expected: {"status":"UP",...}
```

> **Note:** If the app container stopped during the Redis outage, restart it first: `docker compose restart app`. Wait ~30 seconds before checking health.

---

## Scenario 4: A Booking Stuck in `CAPTURE_PENDING`

**A `payment.booking_payments` row with `status = 'CAPTURE_PENDING'` has no automated exit.** It is
deliberately the one case the platform will not resolve on its own, because only a human can read
the Stripe side and decide whether money actually moved.

### Symptoms

- ERROR log: `Stranded PAYMENT_PENDING booking cannot be swept automatically (CAPTURE_UNCONFIRMED)`
- Metric: `booking_payment_pending_unrecoverable_total{reason="CAPTURE_UNCONFIRMED"}` increments on
  every sweep (every 15 minutes) and keeps incrementing until resolved — the repetition **is** the
  alert and is deliberately not deduplicated.
- Possibly also `booking_payment_settle_aborted_total{reason="capture_unconfirmed"}` if a duplicate
  settlement event arrived, or `{reason="reservation_failed"}` if the reservation itself threw (lock
  timeout or constraint violation) — in that last case no charge was attempted and the sweeper can
  resolve it on its own, so no manual work is needed.
- `booking_payment_settle_conflict_total{event="PAYMENT_CAPTURED"|"PAYMENT_FAILED"}` and
  `booking_payment_settle_error_total` cover the settle-side transition failing after a reservation
  stands. **These two are the "routes nobody has found yet" alarms.** If either fires, the row below
  is genuinely stuck and this scenario applies; capture the surrounding ERROR log, because the cause
  is by definition not one of the paths analysed in UAT.3.
- The parent cannot cancel the booking: `POST /api/bookings/{id}/cancel` returns **409**
  `booking.paymentInProgress` for as long as the row stands.
- The booking holds the coach's slot (`PAYMENT_PENDING` is in `ACTIVE_SLOT_STATUSES` and in V87's
  exclusion constraint), so nobody else can book that time.

### What it means

`BookingPaymentPersistenceService.reserveCapture` writes a `CAPTURE_PENDING` row in its own
transaction immediately **before** either Stripe call. A row still in that state means the process
died — JVM crash, container restart, rolling deploy — between the reservation and the record of the
outcome. **Money may or may not have been captured at Stripe.** The platform will never re-charge on
this state; doing so risks double-charging the parent.

### Diagnosis

```bash
# 1. List every outstanding reservation:
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" \
  "$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres)" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" -c \
  "SELECT bp.booking_id, bp.batch_payment_intent_id, bp.credit_debited, bp.stripe_charged,
          b.parent_id, b.coach_id, b.status, b.updated_at
     FROM payment.booking_payments bp
     JOIN booking.bookings b ON b.id = bp.booking_id
    WHERE bp.status = 'CAPTURE_PENDING';"
```

> **The `credit_debited` / `stripe_charged` columns on a CAPTURE_PENDING row are a reconciliation
> hint, not an accounting record — do not reconcile against them as if they were.** For a **single**
> booking they hold the intended split and are reliable. For a **batch** booking (`batch_payment_intent_id`
> is non-null) the per-booking credit/Stripe split is not known at reservation time, so the row
> carries `credit_debited = 0` and the whole price under `stripe_charged`; the real split is written
> only when the settle completes. Treat the batch figure as an **upper bound on the Stripe leg**, and
> take the actual amount from the Stripe dashboard, not from this table. Only `CAPTURED` rows are
> ever summed by the revenue reports, so a stuck row is not distorting any coach's earnings while you
> work.

2. In the **Stripe dashboard**, search PaymentIntents by metadata `referenceId`:
   - single booking → the **booking id**
   - batch booking → the **batch id** (the `batch_payment_intent_id` column above)

   `StripePaymentGateway` writes both as `referenceId` metadata, which is why this search works.

### Resolution — charge FOUND at Stripe

The parent paid. Record it and confirm the booking:

```sql
UPDATE payment.booking_payments
   SET status = 'CAPTURED',
       captured_at = now(),
       stripe_payment_intent_id = '<pi_... from the Stripe dashboard>'
 WHERE booking_id = '<booking-id>' AND status = 'CAPTURE_PENDING';

UPDATE booking.bookings
   SET status = 'CONFIRMED', updated_at = now(), version = version + 1
 WHERE id = '<booking-id>' AND status = 'PAYMENT_PENDING';
```

### Resolution — NO charge at Stripe

Nothing was taken. Decline the booking and hand the coach's slot back:

```sql
UPDATE payment.booking_payments
   SET status = 'CHARGE_FAILED'
 WHERE booking_id = '<booking-id>' AND status = 'CAPTURE_PENDING';

UPDATE booking.bookings
   SET status = 'DECLINED', updated_at = now(), version = version + 1
 WHERE id = '<booking-id>' AND status = 'PAYMENT_PENDING';
```

Both statements are guarded on the current status, so re-running one is a no-op rather than a
second overwrite.

### Verification

```bash
# No CAPTURE_PENDING rows should remain, and the counter stops incrementing on the next sweep
# (within 15 minutes):
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" \
  "$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres)" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" -c \
  "SELECT count(*) FROM payment.booking_payments WHERE status = 'CAPTURE_PENDING';"
# Expected: 0
```

> **Why this is manual.** Every automatic option is worse. Declining could charge a parent for
> nothing; confirming could give away a session that was never paid for; re-charging could take the
> money twice. The `booking_payments` row exists precisely so that a human has something durable to
> reconcile against — before UAT.3 there was no record at all, and this situation was undetectable.
