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

**All five retention windows, in one place:**

| Data | Window | Enforced by |
|---|---|---|
| Loki logs | 30 days | `loki.yml`, self-pruning |
| Prometheus metrics | 15 days | Prometheus, self-pruning |
| Tempo traces | 14 days (336h) | `tempo.yml` `block_retention`, self-pruning |
| PostgreSQL dumps (Object Storage) | `BACKUP_RETENTION_DAYS`, default 14 days | `prune-backups.sh`, daily 03:30 UTC |
| Volume backups (Object Storage) | `VOLUME_BACKUP_RETENTION_DAYS`, default 14 days | `prune-backups.sh`, daily 03:30 UTC |

Backup retention keeps the newest `BACKUP_RETENTION_MIN_KEEP` (default 8) dumps and the newest
`VOLUME_BACKUP_RETENTION_MIN_KEEP` (default 4) volume backups regardless of age.
See [`backup-restore.md`](backup-restore.md#retention) for the safety rails and the dry-run first
run.

> **File-level volume backup runs daily.** `deploy/backup/volume-backup.sh` archives everything
> under `/opt/skillars/data` — Loki, Prometheus, Grafana, Tempo, Redis AOF, `acme.json` — to Hetzner
> Object Storage every day at 02:00 UTC, excluding `postgres/` (already covered by the pg_dump
> stream above). It replaces the earlier `volume-snapshot.sh` mechanism, which called a Hetzner
> Cloud API endpoint that does not exist and never produced a working backup — see
> `deferred-work.md`, `skillars-uat-3` D1. Restore via `deploy/backup/restore-from-volume-backup.sh`
> — see [`backup-restore.md`](backup-restore.md) Section B.

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

rm -rf /opt/skillars/data/redis/appendonlydir /opt/skillars/data/redis/dump.rdb

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

**A `payment.booking_payments` row with `status = 'CAPTURE_PENDING'` still needs a human to reconcile
the Stripe side** — only a person can read Stripe and decide whether money actually moved. The
platform will never re-charge or auto-confirm on this state.

**What is now automated (skillars-deferred-91 AC5 Part A):** `PaymentPendingSweeper` bounds the
*slot-hold* harm. Once a `CAPTURE_PENDING` row is older than
`booking.payment_pending.capture_pending_max_hours` (config, default **72h**, min 6, max 720) the
sweeper moves it to the terminal **`CAPTURE_ABANDONED`** status, transitions the booking out of
`PAYMENT_PENDING` (freeing the coach's slot and unblocking the parent's cancel), and emits
`booking_payment_pending_unrecoverable_total{reason="CAPTURE_TIMEOUT"}` with a
`[CAPTURE_TIMEOUT]` ERROR. **This does not resolve the payment** — `CAPTURE_ABANDONED` means "we
stopped waiting; the Stripe side is unknown", and the reconciliation below still applies. A row
with a null `reserved_at` (created before V124) is not aged and stays on the manual
`CAPTURE_UNCONFIRMED` path indefinitely.

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
# 1. List every outstanding reservation (both still-pending and timed-out):
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" \
  "$(docker compose -f /opt/skillars/docker-compose.yml ps -q postgres)" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" -c \
  "SELECT bp.booking_id, bp.status, bp.reserved_at, bp.batch_payment_intent_id,
          bp.credit_debited, bp.stripe_charged, b.parent_id, b.coach_id, b.status AS booking_status
     FROM payment.booking_payments bp
     JOIN booking.bookings b ON b.id = bp.booking_id
    WHERE bp.status IN ('CAPTURE_PENDING', 'CAPTURE_ABANDONED');"
```

A `CAPTURE_ABANDONED` row is one the sweeper already timed out: the booking is no longer holding a
slot and the parent can cancel, but you still owe the Stripe reconciliation below. A
`CAPTURE_PENDING` row is either younger than the timeout or has a null `reserved_at`.

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

The parent paid. Record it and confirm the booking. Use the actual current status in the guard —
`CAPTURE_PENDING` if the sweeper has not timed it out yet, `CAPTURE_ABANDONED` if it has:

```sql
UPDATE payment.booking_payments
   SET status = 'CAPTURED',
       captured_at = now(),
       stripe_payment_intent_id = '<pi_... from the Stripe dashboard>'
 WHERE booking_id = '<booking-id>' AND status IN ('CAPTURE_PENDING', 'CAPTURE_ABANDONED');

-- If CAPTURE_ABANDONED, the booking has already left PAYMENT_PENDING (it is DECLINED). Re-open it:
UPDATE booking.bookings
   SET status = 'CONFIRMED', updated_at = now(), version = version + 1
 WHERE id = '<booking-id>' AND status IN ('PAYMENT_PENDING', 'DECLINED');
```

> If the slot the coach had was taken by another booking in the window between `CAPTURE_ABANDONED`
> and your fix, re-confirming will fail V87's exclusion constraint. In that case refund the Stripe
> charge instead (next section) and tell the parent.

### Resolution — NO charge at Stripe

Nothing was taken. If the row is still `CAPTURE_PENDING`, decline the booking and hand the slot back:

```sql
UPDATE payment.booking_payments
   SET status = 'CHARGE_FAILED'
 WHERE booking_id = '<booking-id>' AND status = 'CAPTURE_PENDING';

UPDATE booking.bookings
   SET status = 'DECLINED', updated_at = now(), version = version + 1
 WHERE id = '<booking-id>' AND status = 'PAYMENT_PENDING';
```

If the row is already `CAPTURE_ABANDONED`, the sweeper has done both of those already — just tidy
the payment status so the reconciliation is closed out:

```sql
UPDATE payment.booking_payments
   SET status = 'CHARGE_FAILED'
 WHERE booking_id = '<booking-id>' AND status = 'CAPTURE_ABANDONED';
```

All statements are guarded on the current status, so re-running one is a no-op rather than a
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

---

## Config change appears to have no effect

**Symptom.** You changed a value in `main.platform_config` and the application is still using the
old one. Reported during UAT for `booking.session.defaultDurationMinutes`, where it read as a bug.

**It is not a bug — it is a cache, and which path you used decides the behaviour.**

| How the value was changed | When it takes effect |
|---|---|
| `PUT /api/config/values/{key}` (admin API) | **Immediately, on the node that served the request.** `ConfigService.updateConfig` invalidates that node's cache as part of the write. |
| Directly in the database — `psql`, a migration, a manual fix | **Up to `app.config.cache-ttl-seconds` (default 300s / 5 minutes), on every node.** |

`ConfigService` keeps an in-memory cache refreshed by
`@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}", timeUnit = TimeUnit.SECONDS)`.
A direct database write has no way to invalidate it, so a node keeps serving the cached value until
its own next refresh.

**`invalidate()` has no cross-node effect — it only clears the cache of the node that ran it.** On
more than one node, the admin API is *not* "immediate everywhere": the node that handled your `PUT`
is correct at once, but every other node still serves the old value until its own TTL elapses, no
differently from a direct DB write. **Prefer the API anyway** — not because it is faster across the
fleet, but because it validates the input, is audited, and guarantees at least one node (the one you
just queried) reflects the change immediately, which is what "wait out the TTL, or restart the
application" below is really waiting on for the rest.

**What to do**

1. Prefer the admin API. That is the whole answer in the normal case.
2. If the value was already changed directly in the database, or you need every node correct right
   now rather than after the TTL: wait out the TTL, or restart the application. A load-balanced
   re-`PUT` through the public API does **not** reliably reach every node — it invalidates only
   whichever node happens to receive that request, and there is no fleet-wide invalidation broadcast.
3. Do not lower `app.config.cache-ttl-seconds` to work around this. It would add polling on every
   node, permanently, for a table that changes a handful of times a year. The TTL was reviewed under
   skillars-deferred-92 AC24 and deliberately left at 300s.

---

## Pre-production release gate: outstanding migration rewrites

**Owner:** whoever prepares the first production deploy. **Trigger:** before that deploy, not after.

Skillars has no production system yet, and several migrations lean on that fact rather than on
rolling-deploy safety. `docs/deployment/migration-conventions.md` records the reasoning; this entry
exists so the obligation survives independently of that document (skillars-deferred-91 code review,
decision D7).

Before the first production deploy, **all four items below must be closed**:

| Item | What is wrong today | Required before production |
| --- | --- | --- |
| `V60` (`main.videos` CHECK re-add) | Validates the whole table under `ACCESS EXCLUSIVE`; `videos` grows | Redo as `ADD CONSTRAINT … NOT VALID` + a later `VALIDATE CONSTRAINT` |
| `V94` (`payment.booking_payments` `chk_bp_status`) | Same — re-`ADD CONSTRAINT … CHECK` validates the whole table | Redo as `NOT VALID` + later `VALIDATE CONSTRAINT` |
| `V117` (`marketplace.coach_radar_preferences` FK + index) | FK `ADD CONSTRAINT` validates under `ACCESS EXCLUSIVE`; the plain `CREATE INDEX` takes a `SHARE` lock | FK as `NOT VALID` + later `VALIDATE`; index as `CREATE INDEX CONCURRENTLY` |
| `V124` (`CAPTURE_ABANDONED` CHECK widen) | The CHECK widen and its first write (`PaymentPendingSweeper.abandonCapture`) ship in the **same** release, deviating from convention rule 5 | Split into widen-then-write across two releases |

Also outstanding, and cheap to close at the same time: `V125`/`V126`/`V127` create indexes without
`CONCURRENTLY` because Flyway runs migrations in a transaction here. Once a production database
exists, either move those to a non-transactional Flyway callback or accept and schedule the lock.

**Verification:** `MigrationConventionLintTest` passes with the corresponding
`-- migration-lint: allow-*` opt-outs **removed** from the affected files. If a migration still
needs its opt-out, the item is not closed.
