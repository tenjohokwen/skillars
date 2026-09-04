# Skillars — LGTM Stack Observability Guide

**Stack:** Loki (logs) · Grafana (dashboards + alerting) · Tempo (traces) · Prometheus (metrics)
**Application:** Skillars — a youth-football coaching marketplace. Payments are **Stripe**.

> **What this document is, and is not.**
> This is a *how to query the stack* guide: LogQL / PromQL / TraceQL patterns against the signals
> this application actually emits.
>
> It is **not** the alert inventory. That lives in
> [`docs/deployment/monitoring.md`](deployment/monitoring.md) § *Alert Inventory and Response
> Actions*, generated from `deploy/lgtm/alerts.yml` and `deploy/lgtm/grafana-alerts.yml`. It is
> deliberately not duplicated here — the previous version of this file duplicated it and then drifted
> for a whole project pivot (see *History* at the bottom), which is exactly the failure a
> cross-reference avoids. `AlertDocumentationParityTest` fails the build if the rules and that doc
> disagree.

---

## 0. Quick start

The observability services are part of the **main** compose file — there is no separate LGTM stack
file:

```bash
docker compose up -d prometheus loki tempo grafana node_exporter
```

| Service | Local URL | Notes |
|---|---|---|
| Grafana | <http://localhost:3000> | Datasources and alert rules are provisioned from `deploy/lgtm/` |
| Prometheus | <http://localhost:9090> | Scrapes the app's `/manage/prometheus` |
| Loki | <http://localhost:3100> | See the log-shipping caveat below |
| Tempo | <http://localhost:3200> | OTLP ingest |

> **Log shipping to Loki is OFF by default.** `application.yaml` sets `loki.enabled` from
> `LOKI_ENABLED`, defaulting to **`false`**, and `config/logback-spring.xml` only installs the
> `Loki4jAppender` when it is `true`. With it off, logs go to the console JSON appender only and
> **every LogQL query in §2 returns nothing**. Set `LOKI_ENABLED=true` and `LOKI_URL` first. See
> [dev-docs → Monitoring](dev-docs/monitoring/index.html) for the wider gap.

---

## 1. What each pillar is for

| Pillar | Use it when you are asking |
|---|---|
| **Loki** (logs) | *What exactly failed, for which booking / video / user?* |
| **Prometheus** (metrics) | *Is the system healthy right now, and is this rate normal?* |
| **Tempo** (traces) | *Why was this one request slow, hop by hop?* |
| **Grafana** | *Tell me before a user does.* |

Skillars' failure modes are mostly **asynchronous**: an outbox row that never drains, a scheduled
sweep that skipped a cycle, a Stripe webhook that arrived twice. Those are invisible to a
request-scoped view, which is why the log markers in §2 matter more here than endpoint latency.

---

## 2. Log queries (LogQL — Loki)

Application logs are JSON, so extract fields with `| json` before filtering.

### 2.1 The durable outbox

The generic outbox (`main.outbox_messages`) carries refunds, transactional emails, SLU snapshot
writes and moderation retries. A row is **never dropped**, so a stuck row is a real operation that
has not happened yet.

```logql
# The alert-grade signal: rows that have failed >= 10 times and still hold a promised operation.
{app="skillars"} |= "[OUTBOX_STUCK]"

# Drain outcomes over time — `processed` and `failed` counts per drain.
{app="skillars"} |= "[OUTBOX_DRAIN]" | json

# A drain that aborted mid-way. Rows stay in the table with their backoff, so this is not data loss,
# but a sustained rate here means the sweeper is doing all the work.
{app="skillars"} |= "[OUTBOX_DRAIN_FAILED]"

# Producer-side enqueue failures, per domain support class.
{app="skillars"} |~ "\\[(NOTIFICATION_EMAIL|CREDIT_WALLET_REFUND|SLU_SNAPSHOT_OUTBOX|MODERATION_OUTBOX)_ENQUEUE_FAILED\\]"
```

### 2.2 Transactional email

```logql
# Delivered.
{app="skillars"} |= "[NOTIFICATION_EMAIL]" | json | line_format "{{.template}} -> {{.toAddress}}"

# Permanently undeliverable — a malformed address; no re-drive can help, so the row is released.
{app="skillars"} |= "[NOTIFICATION_EMAIL_UNDELIVERABLE]"
```

### 2.3 Video moderation

```logql
# SLA monitor re-queued a video stuck in SCANNING.
{app="skillars"} |= "[VIDEO_MODERATION_RETRY]"

# Moderation permanently failed — an operator must review this video by hand.
{app="skillars"} |= "[VIDEO_MODERATION_ADMIN_ALERT]"

# Bypass counter (also a Micrometer counter: video_moderation_bypass_total).
{app="skillars"} |= "moderation" | json | eventType != ""
```

### 2.4 GDPR erasure and export

Compliance-relevant and worth alerting on if it fails, because a failed erasure is a legal
obligation left unmet:

```logql
{app="skillars"} |~ "\\[GDPR_(ERASURE|EXPORT)_(REQUESTED|COMPLETED|FAILED)\\]" | json
```

### 2.5 Subscriptions and payments

```logql
{app="skillars"} |= "[SUB_LIFECYCLE]" | json
{app="skillars"} |= "[SUBSCRIPTION_GRACE_PERIOD_CHECKER]"
{app="skillars"} |= "[CAPTURE_TIMEOUT]"
{app="skillars"} |= "[CREDIT_WALLET_REFUND]" | json
```

### 2.6 Rates over time

```logql
# Error rate per minute.
sum(rate({app="skillars"} | json | level="ERROR" [1m]))

# Which markers are firing most — the fastest way to characterise an incident.
topk(10, sum by (marker) (count_over_time({app="skillars"} |~ "\\[[A-Z_]+\\]" | regexp "(?P<marker>\\[[A-Z_]+\\])" [15m])))
```

---

## 3. Metric queries (PromQL — Prometheus)

### 3.1 Booking payments

These are the metrics behind the `BookingPaymentSettleFailureRateHigh` rule, so the query below is
the alert's own expression — useful for confirming a page before acting on it:

```promql
(rate(booking_payment_settle_failed_total[15m]) + rate(booking_payment_settle_error_total[15m]))
/ (rate(booking_payment_settle_success_total[15m]) + rate(booking_payment_settle_failed_total[15m]) + rate(booking_payment_settle_error_total[15m]))
```

### 3.2 Subscriptions

```promql
increase(subscription_payment_invoice_failed_total[1h])
```

### 3.3 HTTP endpoints (Spring Actuator / `@Observed`)

Resources are annotated `@Observed(name = "...")` — e.g. `booking.availability`, `booking.batch`,
`marketplace.profile`, `admin.queue`, `reviews.list` — which produces `http_server_requests_seconds`
plus an observation timer per name.

```promql
# p95 latency by endpoint.
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))

# 5xx rate by endpoint.
sum by (uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
```

### 3.4 JVM and pools

```promql
# The two infrastructure alerts' own expressions.
hikaricp_connections_active / hikaricp_connections_max
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# Executor saturation. Six pools exist — see infrastructure/threadpool/ExecutorShutdown for the
# inventory and the shutdown budget. A queue that never drains is the signal that matters.
executor_queued_tasks{name=~"outbox-drain-|slu-retry-|smPool|modPool|skillars-async-"}
executor_active_threads
sendmail_queue_size
```

### 3.5 Host

```promql
up{job="spring-boot-app"}
up{job="node-exporter"}
```

---

## 4. Trace queries (TraceQL — Tempo)

```traceql
# Slow requests to a named endpoint.
{ name = "GET /api/marketplace/coaches/{coachId}" && duration > 1s }

# Everything that errored.
{ status = error }

# One trace, end to end, from a traceId found in a log line.
{ trace:id = "<traceId>" }
```

Traces cover the **request** path. The outbox drain runs on `outboxDrainPool`, off the request
thread, so a drain is a separate trace — do not expect it inside the booking's span.

---

## 5. Correlating logs, traces and metrics

Every log line carries `traceId` (Micrometer Tracing / OTEL). Grafana's Loki datasource is
provisioned with a derived field in `deploy/lgtm/grafana-datasources.yml` that turns it into a link
to Tempo.

**Typical path:** alert fires → open the panel's PromQL to see the shape → jump to Loki for the same
window → find the `traceId` on an ERROR line → follow it to Tempo for the hop that failed.

---

## 6. MDC fields available on log lines

Populated by the application (not all fields appear on every line — they are set by the code path
that owns them):

| Field | Set by | Example |
|---|---|---|
| `traceId` | Micrometer Tracing (OTEL) | `a1b2c3d4…` |
| `operation` | business services | `admin_bootstrap` |
| `eventType` | video / webhook handling | `video.uploaded` |
| `videoId`, `ownerId`, `viewerId` | video module | UUID / user id |
| `uploadSessionId`, `storageKey`, `providerAssetId` | upload + storage | — |
| `webhookEventId` | inbound webhook handling | Stripe event id |
| `provider`, `localState` | video provider integration | — |

`MdcDecorator` propagates the MDC across `@Async` hand-offs, so a task queued on a pool keeps the
originating request's `traceId`.

---

## 7. Alerts

**Not listed here — deliberately.** See
[`docs/deployment/monitoring.md`](deployment/monitoring.md) § *Alert Inventory and Response Actions*
for all nine rules with their meaning and response runbook, and `deploy/lgtm/alerts.yml` /
`deploy/lgtm/grafana-alerts.yml` for the definitions themselves.

`AlertDocumentationParityTest` (in the `test` phase, no container) fails the build if a rule exists
without documentation or a documented alert no longer exists. That check is the reason this section
is a pointer rather than a copy.

---

## History — why this file was rewritten (skillars-deferred-92 AC27)

This document was inherited from the `javatemplate` origin project and described **a Mobile Money
payment orchestrator (Orange Money + MTN MoMo)** — a system Skillars has never been. It survived the
whole pivot to a Stripe coaching marketplace, and six prior full-file ledger audits closed without
examining it.

What was wrong, concretely:

- **The alert catalogue was entirely fictional.** It documented `CallbackRateZero`,
  `WebhookPermanentFailure`, `OrangeCircuitBreakerOpen`, `MtnCircuitBreakerOpen`,
  `PaymentFailureRateHigh`, `CallbackFailureRatioHigh`, `FraudBlockRateHigh`,
  `ReconciliationDiscrepancy` and `ProviderLatencyP*`. **None of them exist.** The nine that do
  (`AppDown`, `NodeExporterDown`, `BookingPaymentSettleFailureRateHigh`, `DbConnectionPoolHigh`,
  `JvmHeapHigh`, `SubscriptionInvoicePaymentFailureHigh`, `DiskDataVolumeHigh`, `DiskRootHigh`,
  `MemoryPressureHigh`) appeared nowhere in it. An operator following this file during an incident
  hunted for alerts that cannot fire and missed every one that can.
- **~80 of 1143 lines were Orange/MTN/MSISDN/mobile-money narrative**, including seven incident
  runbooks (provider circuit breaker, callback silence, reconciliation discrepancy, fraud engine,
  webhook backlog, token refresh loop) for subsystems this codebase does not contain, and two
  Grafana dashboard specs (*Provider Health — Orange & MTN*, *Fraud & Risk*) for dashboards that do
  not exist.
- **The MDC table was fiction too** — it listed `transactionId`, `externalReference`, `tenantId`,
  `provider: ORANGE|MTN`, `riskScore`, `fraudSignal`, `discrepancyType`. Only `provider` survives,
  and it means a *video* provider. §6 is now the real set, read from `MDC.put` call sites.
- **The Quick Start did not work.** It told you to run
  `docker-compose -f docker-compose-lgtm.yaml up -d`; that file has never existed in this repository.
  The observability services are in the main `docker-compose.yml`.
- **The Loki caveat was buried.** `loki.enabled` defaults to `false`, so every LogQL example returned
  nothing until you set `LOKI_ENABLED=true` — now stated in §0 rather than left to be discovered.

A prior story added a "STALE" warning banner at the top. That was honest but did not help the
operator holding a pager: the wrong content was still the content. Two `dev-docs` pages link here as
the authoritative observability reference (`docs/dev-docs/index.html`,
`docs/dev-docs/monitoring/index.html`), which is what made leaving it in place untenable.
