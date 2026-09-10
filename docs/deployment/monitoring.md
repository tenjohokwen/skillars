# Monitoring Reference

This guide covers how to access Grafana, what dashboards exist, and how to respond to every
configured alert. Every alert defined in `deploy/lgtm/alerts.yml` and `deploy/lgtm/grafana-alerts.yml`
has a corresponding documented response action below.

---

## Accessing Grafana

**URL:** `https://${MONITORING_DOMAIN}` — the value of `MONITORING_DOMAIN` in `/opt/skillars/.env`

**Credentials:**
- Username: value of `GF_SECURITY_ADMIN_USER` (default: `admin`)
- Password: value of `GF_SECURITY_ADMIN_PASSWORD`

Both values are in `/opt/skillars/.env` on the Node. See [`docs/deployment/secrets-reference.md`](secrets-reference.md) for the full secrets inventory.

Grafana is only accessible via HTTPS through Traefik. There is no direct port exposure to the host network.

---

## Dashboards

**Skillars Dashboard** — provisioned automatically from `deploy/lgtm/skillars-dashboard.json`.

Shows:
- Infrastructure health overview
- JVM heap utilisation
- HikariCP database connection pool utilisation
- Live error logs and recent errors (Loki)

For ad-hoc log queries (Loki) or trace lookups (Tempo), use Grafana's built-in **Explore** view — select the appropriate datasource (Loki or Tempo) from the dropdown.

---

## Alert Notifications

All alerts route to the `notify-ops` contact point:
- **Email:** address configured in `GF_ALERT_NOTIFY_EMAIL`
- **Slack:** webhook configured in `GF_SLACK_WEBHOOK_URL`

At least one must be set in `/opt/skillars/.env`. `provision.sh` renders the `contactPoints:` region
of `deploy/lgtm/grafana-alerts.yml` (between the `# >>> BEGIN provision.sh-managed contactPoints`
markers) from **only the channels that are configured**, so a single-channel deployment provisions
exactly one receiver — not one live receiver plus one that silently drops every alert routed to it.
The `${GF_ALERT_NOTIFY_EMAIL}` / `${GF_SLACK_WEBHOOK_URL}` placeholders stay in the file (Grafana
expands them from its own container env); the real values are never written there. Edit the `.env`
channels and re-run `provision.sh` to change which receivers are provisioned — do not hand-edit
inside the markers. Grafana reads its provisioning files **only at container start**, so after a
rewrite you must recreate the container for the change to take effect:
`docker compose up -d --force-recreate grafana`.

**Notification policy:** group wait 30s, group interval 5m, repeat interval 4h.

All alert rules appear in Grafana under **Alerting → Alert rules → Skillars Alerts** folder.

---

## Alerting architecture: the Alertmanager decision

**Decision (skillars-deferred-107 AC7, [DECIDED 2026-09-10]).** Grafana-managed alerting is the
single alert-*delivery* path. The Prometheus `deploy/lgtm/alerts.yml` rules stay — they give Grafana
alert state and panels via the Prometheus datasource — but they **deliver nothing on their own**. No
Alertmanager is deployed and none is planned. This item had been "decision-deferred" since
`deploy-3-3` (2026-06-05) purely because Alertmanager was never stood up; the decision is now
recorded rather than left as a compose comment.

**Why one delivery path.** A second delivery path (Prometheus → Alertmanager → receivers) alongside
Grafana's own notification policies means two notification configs to keep reconciled, two places a
contact point can be edited, and — for any alert defined on both sides — double-paging. One path
means each alert fires exactly once and there is one place to change routing.

**⚠️ Known coverage gap (surfaced by the skillars-deferred-107 code review).** `alerts.yml` defines
9 rules; `deploy/lgtm/grafana-alerts.yml` provisions Grafana equivalents for only 5
(`NodeExporterDown`, `AppDown`, `DiskDataVolumeHigh`, `DiskRootHigh`, `MemoryPressureHigh`). The
four with **no Grafana twin deliver nothing today**:

| Prometheus rule (`alerts.yml`) | What it watches |
|---|---|
| `DbConnectionPoolHigh` | HikariCP pool near exhaustion |
| `JvmHeapHigh` | JVM heap sustained near max |
| `BookingPaymentSettleFailureRateHigh` | booking payment settlement failure rate (has a `runbook:` link) |
| `SubscriptionInvoicePaymentFailureHigh` | subscription invoice payment failures (has a `runbook:` link) |

This gap predates AC7 — the decision just makes it explicit. **Close it** by adding the four rules to
`grafana-alerts.yml` (own follow-up: each needs a threshold, an evaluation window and the
`notify-ops` contact point), or consciously accept that these four are dashboard-only.

**If Alertmanager is ever added, the same change MUST:**

1. Add an `alerting.alertmanagers` block to `deploy/lgtm/prometheus.yml` pointing at the new service.
2. Define the Alertmanager `route` + `receivers` and make it the delivery path for the infra alerts
   currently in `alerts.yml`.
3. **Disable the twin Grafana notification policies** for every alert that now routes through
   Alertmanager, so each alert fires exactly once (not once per path).
4. Update this section and the `# Alerting architecture:` comment above the `prometheus` service in
   `docker-compose.yml`.

---

## Platform config value ranges

`ConfigStartupAssertion` (skillars-deferred-107 AC3) checks these `platform_config` rows on every
application start. A value **outside the range** is clamped to a safe number at read time (a WARN is
logged) **and**:

- for a **fail-fast** key, the application **refuses to boot** in non-`dev` profiles until the row is
  corrected — the ERROR names every offending key and its range. Fail-fast triggers on an
  out-of-range value, a present-but-non-numeric value, or (for a key whose call site has no code
  default) an absent/blank value;
- for the rest, an ERROR is logged and `config.value.misconfigured` is incremented with
  `tag("key", …)` and `tag("reason", …)` where `reason` is `out_of_range`, `missing`, or
  `non_numeric` — the same counter and tag scheme `ConfigService` already uses for feature-gate
  misconfiguration. The read-time clamp keeps the flow alive.

The ERROR + metric fire in **all** profiles (including `dev`); only the boot-blocking throw is gated
to non-`dev`, so a developer who hand-edits a row to test still sees the signal.

| Key | Range | Fail-fast? | What a bad value does |
|---|---|:--:|---|
| `platform.message_retention_months` | `[1, 600]` | ✅ | 0/neg → the retention job deletes **every** message with no open report on the next run (data-destructive) |
| `pack.pause.maxDays` | `[1, 3650]` | ✅ | 0/neg → every session-pack pause rejected as `booking.pauseDurationInvalid` |
| `booking.batch.maxSize` | `[1, 100]` | ✅ | 0/neg → every batch booking rejected as `booking.batchSizeExceeded` |
| `disputes.submissionWindowDays` | `[1, 365]` | ✅ | 0/neg → no dispute can ever be filed |
| `reviews.submissionWindowDays` | `[1, 365]` | ✅ | 0/neg → no review can ever be submitted |
| `platform.moderation_sla_minutes` | `[1, 10080]` | ✅ | 0/neg → every SCANNING video is instantly SLA-breached and re-queued |
| `platform.moderation_lock_timeout_minutes` | `[1, 1440]` | ✅ | 0 → moderation lock is stale on creation; huge → permanently stuck rows |
| `platform.video.playback.signed_url_ttl_minutes` | `[1, 1440]` | ✅ | 0 → every signed HLS URL is expired on issue; all playback breaks |
| `gdpr.export.urlExpiryHours` | `[1, 720]` | ✅ | 0 → a legally-required GDPR export download link is dead on arrival |
| `platform.moderation_max_retries` | `[0, 100]` | — | neg → retry-count comparison inverts |
| `booking.quick_complete_timeout_hours` | `[1, 168]` | — | 0 → Quick Complete auto-confirms instantly |
| `platform.video.lifecycle.blocked_to_archived_days` | `[1, 3650]` | — | 0/neg → BLOCKED videos archived immediately or never |
| `platform.video.lifecycle.archived_to_deleted_days` | `[1, 36500]` | — | 0/neg → ARCHIVED videos deleted immediately or never |
| `platform.video.lifecycle.batch_size` | `[1, 10000]` | — | 0 → lifecycle scheduler makes no progress |
| `platform.video.lifecycle.outbox_max_attempts` | `[1, 100]` | — | 0/neg → subscription-lifecycle outbox never drains |
| `platform.video.deletion.max_attempts` | `[1, 100]` | — | 0/neg → Bunny.net deletion outbox dead-letters on the first attempt (or never) |
| `platform.development.radar_composite_dlq.max_attempts` | `[1, 100]` | — | 0/neg → radar-composite DLQ dead-letters on the first attempt (or never) |
| `platform.video.access.coach_window_days` | `[1, 3650]` | — | 0/neg → a coach with a recent completed booking can no longer view player videos |
| `platform.video_reservation_timeout_minutes` | `[1, 1440]` | — | 0 → every upload reservation expires instantly |
| `development.timeline.coachAccessExpiryDays` | `[1, 3650]` | — | 0/neg → coach development-timeline access reads as always expired |
| `development.correlation.minSessionCount` | `[0, 10000]` | — | neg → correlation gate never blocks |
| `development.neglectedSkill.warmupSessionCount` | `[0, 10000]` | — | neg → neglected-skill warmup predicate inverts |
| `subscription.pastDue.gracePeriodDays` | `[0, 365]` | — | neg → PAST_DUE grace cutoff moves into the future |
| `reviews.autoHoldFlagThreshold` | `[1, 1000]` | — | 0 → the first flag on any review auto-holds it |
| `video.quota.{scout,instructor,academy,athlete}.storageBytes` | `[0, 2^63-1]` | — | neg → quota math breaks (0 is a legitimate "no upload" sentinel — scout is seeded 0) |
| `video.quota.{scout,instructor,academy,athlete}.bandwidthBytesMonthly` | `[0, 2^63-1]` | — | neg → quota math breaks (0 is a legitimate "no streaming" sentinel) |
| `video.{homework,drillDemo,coachReview}.maxSizeBytes` | `[1, 2^63-1]` | — | 0 → every upload of that type rejected |
| `video.{homework,drillDemo,coachReview}.maxDurationSeconds` | `[1, 86400]` | — | 0 → every upload of that type rejected |

The single source of truth for these numbers is
`com.softropic.skillars.platform.config.service.ConfigBounds`; each `ConfigService.getBoundedLong(...)`
/ `getBoundedInt(...)` call site passes the same `[min, max]` literally.

---

## Alert Inventory and Response Actions

### Critical Alerts

---

#### AppDown

**Source:** `deploy/lgtm/alerts.yml` (skillars-infra-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-infra-alerts)

**Meaning:** The Spring Boot application is not reachable by Prometheus — the `/manage/prometheus` metrics endpoint returned no data for more than 1 minute. The app may be crashed or its container unhealthy.

**Response:**

```bash
# Check container state:
docker compose ps app

# Check logs:
docker compose logs --tail=50 app

# If stopped, start it:
docker compose start app

# If running but unhealthy (Up but health: unhealthy), restart it:
docker compose restart app

# If crashed in a loop, look for the root cause:
docker compose logs --tail=200 app | grep -i "error\|exception\|oom"
```

If the application crashed due to an OOM kill: increase the memory limit (requires a `docker-compose.yml` change and re-deploy).

If the application failed to start due to missing configuration: check `/opt/skillars/.env` for missing required variables.

---

#### NodeExporterDown

**Source:** `deploy/lgtm/alerts.yml` (skillars-infra-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-infra-alerts)

**Meaning:** The `node_exporter` container is not running. Disk and memory alerts are now blind — no disk or memory metrics are being collected.

**Response:**

```bash
# Check state:
docker compose ps node_exporter

# If stopped, start it:
docker compose start node_exporter

# If failing health check, check logs:
docker compose logs node_exporter --tail=50

# If the container keeps crashing, re-pull the image and restart:
docker compose pull node_exporter && docker compose up -d --no-deps node_exporter
```

---

### High Alerts

---

#### BookingPaymentSettleFailureRateHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** More than 25% of booking-payment settle outcomes over a 15-minute window were failures
(`booking.payment.settle_failed`, incremented by `BookingPaymentPersistenceService.persistPaymentFailure`)
or unexpected settle-transition errors (`booking.payment.settle_error`), relative to all settle outcomes in
that window. May indicate a Stripe outage, a misconfiguration, or a bug in the booking-payment settle path.

**Response:**

1. Check app logs for the settle failure/error path:

```bash
docker compose logs app --tail=200 | grep -i "settle"
```

2. Check the [Stripe status page](https://status.stripe.com/) for an active incident.
3. Check `booking_payment_settle_conflict_total` and `booking_payment_settle_error_total` in Grafana Explore
   to distinguish "the transition itself was rejected" (a concurrency/state issue, see
   [`runbook.md`](runbook.md)'s `booking_payments` guidance) from "the settle call failed outright."
4. If Stripe itself is degraded: no application action needed — monitor until Stripe recovers; failed
   booking payments are surfaced to the parent to retry.

---

#### DbConnectionPoolHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** HikariCP database connection pool usage exceeded 80% of the configured maximum. Connection starvation is imminent if unchecked.

**Response:**

1. Check app logs for slow queries or blocked threads:

```bash
docker compose logs app --tail=100 | grep -i "hikari\|slow"
```

2. Identify long-running or blocked queries in PostgreSQL:

```bash
CID=$(docker compose ps -q --status running postgres | head -1)
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  -c "SELECT pid, query, state, wait_event_type, now()-query_start AS duration
      FROM pg_stat_activity
      WHERE state != 'idle'
      ORDER BY duration DESC;"
```

3. If you identify blocking long-running queries, terminate them:

> **WARNING:** `pg_terminate_backend` immediately kills database connections mid-transaction. For a payment system, this can leave in-flight payment state writes incomplete. Only run this step when you have confirmed the blocking queries are not active payment transactions (e.g., they are stuck background jobs or idle-in-transaction sessions with no recent activity).

```bash
CID=$(docker compose ps -q --status running postgres | head -1)
docker exec -e PGPASSWORD="${POSTGRES_PASSWORD}" "$CID" \
  psql -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-skillars}" \
  -c "SELECT pg_terminate_backend(pid)
      FROM pg_stat_activity
      WHERE now() - query_start > interval '30 seconds'
      AND state != 'idle';"
```

---

### Warning Alerts

---

#### JvmHeapHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** JVM heap usage exceeded 85% of the configured maximum. The application may experience GC pauses or, if sustained, an OOM restart.

**Response:**

1. Check for a monotonically growing heap pattern using the Prometheus query `jvm_memory_used_bytes{area="heap"}` over time in the Explore view.
2. If heap is growing steadily without returning to baseline, the application may have a memory leak — plan a restart during low traffic:

```bash
docker compose restart app
```

3. Monitor after restart. If heap grows back quickly to the threshold, escalate to a code-level memory investigation.
4. If heap is spiking and returning to normal: this is likely a traffic spike; monitor and consider scaling if spikes are sustained.

---

#### SubscriptionInvoicePaymentFailureHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** More than 5 Stripe subscription invoice payments failed in the last hour
(`subscription.payment.invoice_failed`, incremented by `StripeWebhookService.handleInvoicePaymentFailed`
whenever an `invoice.payment_failed` webhook arrives for a known subscription). Coach or player
subscriptions may be entering a past-due state.

**Response:**

1. Check app logs for the affected subscriptions:

```bash
docker compose logs app --tail=200 | grep -i "invoice.payment_failed"
```

2. Check the [Stripe Dashboard](https://dashboard.stripe.com/) → Billing → Failed payments for the specific
   invoices and their decline reasons.
3. If several failures share a decline reason (e.g. expired cards), this may be a real widespread payment-
   method issue rather than an application bug — no code action needed, Stripe's own dunning/retry emails
   handle follow-up.
4. If failures correlate with a recent deploy, check for a regression in the subscription webhook handling
   path (`StripeWebhookService`, `SubscriptionService`).

---

#### DiskDataVolumeHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-infra-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-infra-alerts)

**Meaning:** The Hetzner Volume (`/opt/skillars/data`) is more than 80% full. PostgreSQL data, Loki logs, Prometheus metrics, and Grafana state are all stored here.

**Response:** See [`docs/deployment/runbook.md`](runbook.md) — Disk Exhaustion scenario.

Quick remediation:

```bash
docker system prune -f
# Removes stopped containers and unused images — including the previous app image used by rollback.
# If you may need to roll back, use docker container prune -f instead (containers only).
```

For full analysis and remediation steps, follow the Disk Exhaustion runbook.

---

#### DiskRootHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-infra-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-infra-alerts)

**Meaning:** The root disk (`/`) is more than 80% full. Docker image layers, container logs, and OS files are stored here.

**Response:** See [`docs/deployment/runbook.md`](runbook.md) — Disk Exhaustion scenario.

Quick remediation:

```bash
# Docker image accumulation is the most common cause:
docker image ls --format '{{.Size}} {{.Repository}}:{{.Tag}}' | sort -h
docker image prune -a -f
```

---

#### MemoryPressureHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-infra-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-infra-alerts)

**Meaning:** Node memory usage exceeded 85%. Container OOM kills may follow if unchecked.

**Response:** See [`docs/deployment/runbook.md`](runbook.md) — Redis OOM scenario for one common cause.

General diagnosis:

```bash
# Identify the highest-memory container:
docker stats --no-stream
```

If a container is approaching its Docker memory limit, the kernel will OOM-kill it. Identify the offending container from `docker stats` output and follow its scenario in the runbook.

---

## Silencing an Alert During Maintenance

To suppress alert notifications temporarily without disabling the alert rule:

1. Go to **Grafana → Alerting → Silences → New silence**
2. Set a label matcher for the specific alert — for example: `alertname = DiskDataVolumeHigh`
3. Set the silence duration (for example, 2 hours)
4. Add a comment explaining the reason for the silence
5. Click **Create**

The alert evaluates normally during the silence window, but no notifications are sent to `notify-ops`. The silence expires automatically at the end of the configured duration.
