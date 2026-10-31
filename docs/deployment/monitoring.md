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

Provisioned into the **Skillars** folder from `deploy/lgtm/dashboards/`. Every dashboard links to the others from its top bar, and purple annotations mark app restarts.

| Dashboard | Open it when | Shows |
|---|---|---|
| **Operations Overview** | First stop, or an alert fired | Up/down, traffic, 5xx, latency, heap, DB pool, what is waiting on a human, firing alerts, latest errors |
| **Business Health** | Daily check, or a user reports something odd | Every work queue with its oldest item, the admin queue by type, booking activity, coach wrap-ups and lost development credit, every tagged `[MARKER]` warning |
| **Payments & Payouts** | Anything about money | Settlement outcomes, Stripe webhooks, payouts held / frozen / failed, cash-outs, mismatch log |
| **Performance & Memory** | "It's slow", GC pressure, OOM risk | Slowest routes and traces, **heap allocated per route**, heap after GC, GC, Tomcat threads, executors |
| **Database & Transactions** | Conflicts, timeouts, pool pressure | Hikari wait and hold times, deadlocks, lock timeouts, rollbacks, lock retries, repository time |
| **Background Work** | A job, email or video seems stuck | Scheduled jobs that failed, outboxes, email, video, storage, GDPR requests |

Panels fed by a counter stay empty until that event first happens. That is normal for the failure panels.

### Where the numbers come from

- **`skillars_backlog_count` / `skillars_backlog_oldest_age_seconds`** (`OperationalBacklogMetrics`): work waiting in the database, refreshed every minute. Tags: `backlog`, `detail`.
- **`http_server_allocated_bytes`** (`RequestAllocationMetricsFilter`): heap allocated per request, by route. It counts allocation on the request thread, not retained memory, so async listeners and SSE streams are not attributed to the route.
- **`tasks_scheduled_execution_seconds`**: Spring's own per-job timer. A job that throws shows `outcome="ERROR"`.
- **Log markers:** the app logs integrity problems as `[MARKER] ...` at WARN or ERROR. Dashboards count these in Loki. A new marker appears on Business Health with no dashboard change.

Loki stream labels are `app`, `environment` and `level` (upper case: `level="ERROR"`). Everything else is in the JSON line, or in structured metadata such as `traceId`.

Postgres itself is not exported, so deadlocks and lock timeouts are read from the exceptions the app logs. Add `postgres_exporter` if you need server-side views (bloat, slow statements, replication).

### Grafana alert or admin queue?

- **Admin queue (in-app `AdminAlertType`):** a specific record needs a person to act on it and mark it handled, for example a chargeback or a booking that could not be cancelled.
- **Grafana alert (email/Slack):** the system or a trend needs attention, or there is no in-app action to take.

Long-unresolved admin items reach Grafana too, through `AdminAlertsAging`, so the queue cannot go quietly stale.

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

**Only the rules in `grafana-alerts.yml` notify anyone.** A rule in `alerts.yml` alone is visible in Prometheus and reaches nobody. Each Grafana rule is a query (A) plus a threshold (C): Grafana refuses to alert on a raw series, and a query returning nothing means healthy (`noDataState: OK`). Check a new rule with Grafana's *Rule state* before relying on it.

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
| `reviews.minSessionAgeDays` | `[1, 358]` | — | out-of-range (incl. 0, or above 358 — unreachable under the minimum-7-day cross-field gap since `reviews.updateCooldownDays` caps at 365) falls back to the coded default (7), never to an unbounded floor; also cross-checked against `reviews.updateCooldownDays` (must leave a minimum 7-day gap, at both boot and `PUT /api/config` — a bad combination fails fast even though neither key does alone) |
| `reviews.updateCooldownDays` | `[1, 365]` | — | out-of-range (incl. 0) falls back to the coded default (30), never to an unbounded cooldown; same cross-check as `reviews.minSessionAgeDays` above |
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

#### HttpServerErrorRateHigh

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** More than 5% of requests returned a 5xx for 5 minutes.

**Response:** Operations Overview shows the failing routes and exceptions. Open an error line and follow its traceId into Tempo. If it began with a deploy, roll back.

---

#### StripeWebhookFailing

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** More than 2 Stripe webhook deliveries failed in 15 minutes. Stripe retries, but payment state drifts meanwhile and repeated failures disable the endpoint.

**Response:** Payments & Payouts shows the error class. Check the webhook secret, then the app logs. After fixing, resend failed events from the Stripe dashboard.

---

#### MoneyIntegrityEvent

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** The app logged that money and its records disagree: an amount mismatch, a settle failing after a charge, a refund of unknown amount, an unreconciled pack purchase or a failed payout or refund enqueue.

**Response:** Read the "Mismatches and failures" log on Payments & Payouts. Each line names the booking or PaymentIntent. Compare with Stripe and correct by hand. Do not retry blindly: the charge may already have succeeded.

---

#### UnrecoverablePayment

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** The payment-pending sweeper gave up on a booking.

**Response:** The panel on Payments & Payouts gives the reason and the log names the booking. Check the PaymentIntent in Stripe, then settle or refund by hand.

---

#### OutboxRowsStuck

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** Outbox rows have failed 10 or more times. Each is a committed operation (a refund, email or SLU delta) that has not run.

**Response:** Background Work shows the type. Read `last_error`: `SELECT aggregate_type, attempts, last_error FROM main.outbox_messages WHERE attempts >= 10;`. Fix the cause. Rows keep retrying on backoff.

---

### High Alerts

---

#### TomcatThreadsSaturated

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** Tomcat request threads are above 90% of the pool, so requests will queue before any app code runs.

**Response:** Performance & Memory: check for a slow route or slow dependency holding threads. Compare with DB pool wait on Database & Transactions.

---

#### DbConnectionTimeouts

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** A request failed because no DB connection was free.

**Response:** Database & Transactions: look for a high "time a connection is held". That is usually a long transaction, often network I/O inside one.

---

#### LockRetriesExhausted

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** A row stayed locked through every `PessimisticLockRetryer` attempt, so the user got a conflict error.

**Response:** Database & Transactions names the lock. Occasional hits under a race are expected. Repeats mean a transaction holds that row too long.

---

#### TransactionDeadlocks

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** Postgres reported a deadlock: two code paths take locks in opposite order.

**Response:** The log panel on Database & Transactions has both statements in the exception. Fix the lock order in code.

---

#### ScheduledJobFailing

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** A `@Scheduled` job threw. Spring retries on the next tick, so without this a job can fail forever in silence.

**Response:** Background Work lists the job and exception. Check whether it clears on the next run. If not, what it maintains (sweeps, payouts, retention) has stopped.

---

#### SecondInstanceRunning

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** More than one app instance is heartbeating for 5 minutes. SSE state lives in one JVM, so users on the other node miss real-time events.

**Response:** Expected only briefly during a deploy. Otherwise find and stop the stray instance.

---

#### PayoutsOverdue

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A payout is over an hour past its release date and not frozen, or is `FAILED_PERMANENT` / `REVERSAL_FAILED`.

**Response:** Payments & Payouts shows which. For overdue, check that the release job is running on Background Work. For failed ones, resolve the coach Connect account, then correct by hand.

---

#### CashOutsUnresolved

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A cash-out refund outcome has been unknown for over an hour. The recheck job has not been able to confirm it.

**Response:** Find the refund in Stripe by the PaymentIntent on the `CASH_OUT_UNRESOLVED` admin alert and resolve it there.

---

#### GdprRequestOverdue

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A GDPR request has been open for 20 days. The legal limit is one month.

**Response:** Background Work shows the request type and any failure log. Complete it by hand if the job cannot.

---

#### MailDeliveryFailing

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** More than 20% of emails failed to send for 10 minutes.

**Response:** Background Work shows the transport. Check SES or SMTP status and credentials. `mail_ses_rate_limiter_rejected_total` shows throttling.

---

#### VideoModerationBypassed

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A video reached READY without a moderation verdict.

**Response:** Find the video in the logs, then review it in the admin moderation queue. Check the moderation provider.

---

#### BookingPaymentSettleFailureRateHigh

**Source:** `deploy/lgtm/grafana-alerts.yml` (notifies) and `deploy/lgtm/alerts.yml` (skillars-alerts)

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

**Source:** `deploy/lgtm/grafana-alerts.yml` (notifies) and `deploy/lgtm/alerts.yml` (skillars-alerts)

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

#### HttpLatencyHigh

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** p95 latency has been above 2s for 10 minutes.

**Response:** Performance & Memory: start with "Most server time spent" and "Slowest traces".

---

#### ErrorLogSpike

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** More than 50 ERROR lines in 10 minutes.

**Response:** Operations Overview, "Errors". Group by logger in Explore to find the source. Anonymous 401s are logged at ERROR too (`Filter-origin 401`) and are excluded, so they cannot trip this alert.

---

#### GcOverheadHigh

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-app-alerts)

**Meaning:** The JVM spent over 20% of its CPU in GC for 10 minutes.

**Response:** Performance & Memory: "Allocation pressure by route" shows what drives it. If "Live data after GC" is also high, treat it as a leak.

---

#### AdminAlertsAging

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** An item in the admin queue has been unresolved for over 24 hours.

**Response:** Work the admin queue. Business Health shows the types.

---

#### DisputesAging

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A dispute has been open for over 48 hours, which may be freezing a coach payout.

**Response:** Resolve or dismiss it in the admin disputes queue.

---

#### AwaitedWrapUpRefused

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A coach tried to submit a wrap-up after the recap window closed, for a session the family had already confirmed. The session stays paid, but its SLU, timeline and homework are never recorded. Only `RECAP_EDIT_WINDOW_CLOSED` alerts. The erased and disputed refusals are expected and appear on the dashboard only.

**Response:** Tell the coach their wrap-up was not recorded. The booking id is in the log line. If this repeats, coaches are not finding "Resume wrap-up". This is a Grafana alert rather than an admin-queue item because no admin action can restore the data.

---

#### AwaitedWrapUpsNearDeadline

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** A wrap-up has been awaited for 26 days and the development credit is lost at day 30. This covers the silent case where the coach never tries, which logs nothing. The threshold assumes the default `booking.recap.editWindowDays` of 30, so change it here if that setting changes.

**Response:** Nudge the coach while there is still time. The rows are `booking.session_completion_data WHERE awaiting_wrap_up`.

---

#### ConfigValueMisconfigured

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-business-alerts)

**Meaning:** An admin-editable config value is outside its safe range and the default is in use.

**Response:** The `key` tag on Background Work names it. Correct it in the admin config page.

---

#### JvmHeapHigh

**Source:** `deploy/lgtm/grafana-alerts.yml` (notifies) and `deploy/lgtm/alerts.yml` (skillars-alerts)

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

**Source:** `deploy/lgtm/grafana-alerts.yml` (notifies) and `deploy/lgtm/alerts.yml` (skillars-alerts)

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
