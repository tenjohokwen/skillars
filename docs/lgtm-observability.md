# Skillars — LGTM Stack Observability Guide

**Stack:** Loki · Grafana · Tempo · Mimir/Prometheus  
**Application:** Skillars — Mobile Money Payment Orchestration (Orange Money + MTN MoMo)

---

## 0. Quick Start

To spin up the observability stack locally, ensure Docker is running and execute:

```bash
# From the project root
docker-compose -f docker-compose-lgtm.yaml up -d
```

- **Grafana:** [http://localhost:3000](http://localhost:3000) (Default datasource: Prometheus)
- **Prometheus:** [http://localhost:9090](http://localhost:9090)
- **Loki:** [http://localhost:3100](http://localhost:3100)
- **Tempo:** [http://localhost:3200](http://localhost:3200)

---

## 1. Why Observability Matters Here

Skillars orchestrates real-money payments across two live provider APIs (Orange Money and MTN MoMo) on behalf of multiple merchant tenants. A payment is not a simple request/response — it spans multiple async hops: the initial provider dispatch, an inbound callback (or status poll), a webhook delivery to the merchant, and a daily reconciliation. Failures at any hop can result in funds collected but not acknowledged, phantom charges, or missed fraud signals.

The four LGTM pillars serve distinct incident-response needs:

| Pillar | Primary Use Case |
|--------|-----------------|
| **Loki** (logs) | Exact error context — what failed, for which tenant/transaction/provider |
| **Prometheus/Mimir** (metrics) | Rate and volume signals — is the system healthy overall right now? |
| **Tempo** (traces) | Causal chain — why did this specific transaction fail, step by step? |
| **Grafana** (dashboards + alerts) | Unified view + automated alerting before users notice |

---

## 2. Log Queries (LogQL — Loki)

All logs are emitted in structured JSON (the console uses `logstash-logback-encoder` while the Loki appender uses the native `Loki4j.JsonLayout`). Both formats ensure that every log line sent to Loki includes the **stream labels** `service`, `environment`, `version`, `host`, and `level` (these are indexed and low-cardinality). Business context fields (`traceId`, `transactionId`, `externalReference`, `tenantId`, `provider`, `errorCode`, etc.) are **log body fields** emitted via MDC/key-values — they are not stream labels and must be extracted with `| json` before filtering or aggregating on them.

### 2.1 Payment Flow Errors

**All ERROR-level logs from the payment service**
```logql
{service="skillars", level="error"}
```

**Payment initiation failures (any cause)**
```logql
{service="skillars", level="error"} |= "initiate_payment"
```

**Provider dispatch failures only (PROVIDER_ERROR / PROVIDER_UNAVAILABLE)**
```logql
{service="skillars", level="error"}
  | json
  | operation="initiate_payment"
  | errorCode=~"PROVIDER_ERROR|PROVIDER_UNAVAILABLE"
```
> Useful to separate provider-side issues from local validation failures.

**Fraud-blocked payments**
```logql
{service="skillars", level="error"}
  | json
  | errorCode="FRAUD_BLOCKED"
  | line_format "tenant={{.tenantId}} tx={{.transactionId}} score={{.riskScore}} reason={{.fraudReason}}"
```

**Subscriber inactive rejections (Orange or MTN account not found)**
```logql
{service="skillars", level="error"}
  | json
  | errorCode="SUBSCRIBER_INACTIVE"
  | line_format "provider={{.provider}} msisdn_suffix={{.msisdn}} tenant={{.tenantId}}"
```

**Unknown MSISDN prefix (routing failure)**
```logql
{service="skillars", level="error"}
  | json
  | errorCode="UNKNOWN_MSISDN_PREFIX"
```

### 2.2 Callback & Webhook Logs

**All inbound Orange callbacks**
```logql
{service="skillars"} |= "orange-callback"
```

**Orange callbacks that failed to match a transaction**
```logql
{service="skillars", level="error"} |= "orange-callback"
```

**All inbound MTN callbacks**
```logql
{service="skillars"} |= "mtn-callback"
```

**Duplicate callback received (dedup hit — expected, but monitor volume)**
```logql
{service="skillars"} |= "duplicate_callback"
```

**Outbound webhook delivery failures**
```logql
{service="skillars", level="error"} |= "webhook_delivery"
  | json
  | line_format "tenant={{.tenantId}} tx={{.transactionId}} attempt={{.retryCount}} url={{.webhookUrl}}"
```

**Outbound webhooks that exhausted all retries (FAILED_PERMANENT)**
```logql
{service="skillars", level="error"} |= "FAILED_PERMANENT"
```

### 2.3 Provider Token & Auth Errors

**Orange token refresh failures**
```logql
{service="skillars", level="error"} |= "OrangeTokenService"
```

**MTN token refresh failures**
```logql
{service="skillars", level="error"} |= "MtnTokenService"
```

**Any 401/403 from a provider (stale token)**
```logql
{service="skillars", level="error"}
  | json
  | httpStatusCode=~"401|403"
```

### 2.4 Reconciliation Logs

**Reconciliation job start and end**
```logql
{service="skillars"} |= "ReconciliationJob"
```

**Reconciliation discrepancies found**
```logql
{service="skillars", level="error"} |= "ReconciliationService"
  | json
  | line_format "date={{.reconciliationDate}} type={{.discrepancyType}} severity={{.severity}} provider={{.provider}} txId={{.transactionId}}"
```

**HIGH severity reconciliation discrepancies only**
```logql
{service="skillars", level="error"}
  | json
  | severity="HIGH"
  | line_format "type={{.discrepancyType}} txId={{.transactionId}} localAmount={{.localAmount}} providerAmount={{.providerAmount}}"
```

### 2.5 Fraud Detection Logs

**All fraud evaluations that resulted in a block**
```logql
{service="skillars"} |= "FRAUD_BLOCKED"
  | json
  | line_format "tenant={{.tenantId}} score={{.riskScore}} signal={{.triggeringSignal}} msisdn={{.msisdn}}"
```

**Velocity limit hits per signal type**
```logql
{service="skillars"} |= "velocity_limit_exceeded"
  | json
  | line_format "signal={{.fraudSignal}} count={{.observedCount}} limit={{.ruleLimit}} window={{.windowSeconds}}s"
```

### 2.6 Circuit Breaker Logs

**Circuit breaker transitions (open/half-open/closed)**
```logql
{service="skillars"} |= "CallNotPermittedException"
```

**Orange circuit breaker open**
```logql
{service="skillars", level="error"}
  | json
  | provider="ORANGE"
  | errorCode="PROVIDER_UNAVAILABLE"
```

**MTN circuit breaker open**
```logql
{service="skillars", level="error"}
  | json
  | provider="MTN"
  | errorCode="PROVIDER_UNAVAILABLE"
```

### 2.7 Idempotency & Replay

**Idempotency key collisions (payment already in flight)**
```logql
{service="skillars"} |= "PAYMENT_ALREADY_PROCESSING"
  | json
  | line_format "tenant={{.tenantId}} key={{.idempotencyKey}}"
```

**Idempotency cache misses (Redis unavailable)**
```logql
{service="skillars", level="error"} |= "IdempotencyService"
```

### 2.8 Rates and Volume Over Time

**Error rate per minute (all payment errors)**
```logql
sum(rate({service="skillars", level="error"} |= "initiate_payment" [1m]))
```

**Callback receipt rate per minute by provider**
```logql
sum by (provider) (
  rate({service="skillars"} |= "callback" | json [1m])
)
```

**Payment errors by error code (top-N bar chart)**
```logql
sum by (errorCode) (
  count_over_time({service="skillars", level="error"} | json | __error__="" [5m])
)
```

---

## 3. Metrics Queries (PromQL — Prometheus / Mimir)

Skillars exposes metrics via Micrometer at `/manage/prometheus`. All metrics carry the tag `application="skillars"`.

### 3.1 Payment Success & Failure Rates

**Total payments succeeded (all time)**
```promql
payment_success_total
```

**Total payments failed (all time)**
```promql
payment_failed_total
```

**Payment success rate over 5-minute window**
```promql
rate(payment_success_total[5m])
```

**Payment failure rate over 5-minute window**
```promql
rate(payment_failed_total[5m])
```

**Fraud-blocked rate over 5-minute window**
```promql
rate(payment_fraud_blocked_total[5m])
```

**Failure ratio (fraction of all payments that failed)**
```promql
rate(payment_failed_total[5m])
  /
(rate(payment_success_total[5m]) + rate(payment_failed_total[5m]) + rate(payment_fraud_blocked_total[5m]))
```
> Alert when this exceeds 0.05 (5%) for more than 2 minutes.

**TPS — transactions per second (combined success + failure)**
```promql
rate(payment_success_total[1m]) + rate(payment_failed_total[1m])
```

### 3.2 Callback Metrics

**Inbound callback rate by provider**
```promql
rate(callback_received_total[5m])
```

**Callback failure rate (no matching transaction found)**
```promql
rate(callback_failed_total[5m])
```

**Callback failure ratio**
```promql
rate(callback_failed_total[5m]) / rate(callback_received_total[5m])
```
> Alert when this exceeds 0.10 (10%) — indicates provider is sending callbacks with unknown references.

### 3.3 Provider Latency

**95th percentile provider call latency by provider**
```promql
histogram_quantile(0.95,
  sum by (le, provider) (
    rate(payment_provider_latency_seconds_bucket[5m])
  )
)
```

**99th percentile provider call latency by provider**
```promql
histogram_quantile(0.99,
  sum by (le, provider) (
    rate(payment_provider_latency_seconds_bucket[5m])
  )
)
```

**Mean provider latency by provider**
```promql
sum by (provider) (rate(payment_provider_latency_seconds_sum[5m]))
  /
sum by (provider) (rate(payment_provider_latency_seconds_count[5m]))
```

**Orange vs MTN latency comparison (p95)**
```promql
histogram_quantile(0.95,
  sum by (le, provider) (
    rate(payment_provider_latency_seconds_bucket{provider=~"ORANGE|MTN"}[5m])
  )
)
```

### 3.4 Circuit Breaker State

> **How Resilience4J exposes state:** One time series is emitted per state per circuit breaker, each with a `state` label (`closed`, `open`, `half_open`). The value is `1` when the circuit breaker is currently in that state, `0` otherwise. There is no single numeric encoding like 0/1/2.

**All state series for both circuit breakers**
```promql
resilience4j_circuitbreaker_state{name=~"orange|mtn"}
```

**Check whether Orange circuit is OPEN**
```promql
resilience4j_circuitbreaker_state{name="orange", state="open"} == 1
```

**Check whether MTN circuit is OPEN**
```promql
resilience4j_circuitbreaker_state{name="mtn", state="open"} == 1
```

**Circuit breaker failure rate (sliding window)**
```promql
resilience4j_circuitbreaker_failure_rate{name=~"orange|mtn"}
```

**Circuit breaker call count by state**
```promql
increase(resilience4j_circuitbreaker_calls_total{name=~"orange|mtn"}[5m])
```

**Circuit breaker transitions to OPEN (alert candidate)**
```promql
changes(resilience4j_circuitbreaker_state{name=~"orange|mtn", state="open"}[5m]) > 0
```
> Alert when the Orange or MTN circuit opens — indicates sustained provider failures.

### 3.5 HTTP Endpoint Metrics (Spring Actuator)

**Payment endpoint request rate**
```promql
rate(http_server_requests_seconds_count{uri="/v1/payments"}[5m])
```

**Payment endpoint error rate (4xx + 5xx)**
```promql
rate(http_server_requests_seconds_count{uri="/v1/payments", status=~"4..|5.."}[5m])
```

**Payment endpoint p95 response latency**
```promql
histogram_quantile(0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{uri="/v1/payments"}[5m])
  )
)
```

**Callback endpoint request rate by provider**
```promql
rate(http_server_requests_seconds_count{uri=~"/v1/callbacks/.*"}[5m])
```

**HTTP 503 rate from payment endpoint (circuit breaker open → client-facing)**
```promql
rate(http_server_requests_seconds_count{uri="/v1/payments", status="503"}[5m])
```

### 3.6 JVM & Infrastructure

> All metrics carry `application="skillars"` because `management.metrics.tags.application` is set in `application.yaml`. If you copied this config without that property, drop the `application` label filter.

**JVM heap used**
```promql
jvm_memory_used_bytes{area="heap", application="skillars"}
```

**JVM heap utilisation (used / max)**
```promql
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```

**Live thread count**
```promql
jvm_threads_live_threads{application="skillars"}
```

**Database connection pool active / max**
```promql
hikaricp_connections_active{application="skillars"}
hikaricp_connections_max{application="skillars"}
```

**Connection pool utilisation**
```promql
hikaricp_connections_active / hikaricp_connections_max
```
> Alert when pool utilisation exceeds 0.80 sustained — indicates connection starvation under load.

**Redis active connections (Lettuce connection pool)**
```promql
lettuce_connections_active{application="skillars"}
```
> Requires `spring.data.redis.lettuce.pool.enabled=true` (enabled automatically when `commons-pool2` is on the classpath). `lettuce_connections_active / lettuce_connections_max` gives pool utilisation.

**Quartz scheduler job execution time**
```promql
histogram_quantile(0.95,
  rate(quartz_job_execution_seconds_bucket[5m])
)
```

### 3.7 SLO Queries

**Payment availability SLO — fraction of time success rate > 95%**
```promql
(
  rate(payment_success_total[30m])
  /
  (rate(payment_success_total[30m]) + rate(payment_failed_total[30m]))
) >= 0.95
```

**Latency SLO — fraction of provider calls completing under 3 s**
```promql
sum(rate(payment_provider_latency_seconds_bucket{le="3"}[5m]))
  /
sum(rate(payment_provider_latency_seconds_count[5m]))
```

---

## 4. Trace Queries (TraceQL — Tempo)

All spans are exported to Tempo via OpenTelemetry OTLP (port 4318). Span names follow `@Observed` annotations; every payment carries a `traceId` that is also stored on the `Transaction` entity.

### 4.1 Finding Traces for a Specific Transaction

**By transactionId (stored as span attribute)**
```traceql
{ .transactionId = "txn-uuid-here" }
```

**By externalReference (merchant's reference)**
```traceql
{ .externalReference = "merchant-ref-here" }
```

**By traceId directly** — paste the traceId from the `Transaction` table or from Loki log output into Grafana → Explore → Tempo datasource → **"TraceID"** search field. TraceQL cannot filter by trace ID; the Tempo UI search field is the correct tool for this lookup.

### 4.2 Slow Payment Traces

**Payment orchestration spans taking longer than 5 seconds**
```traceql
{ name = "http.payment" && duration > 5s }
```

**Orange provider calls taking longer than 3 seconds**
```traceql
{ name =~ "orange.*" && duration > 3s }
```

**MTN provider calls taking longer than 3 seconds**
```traceql
{ name =~ "mtn.*" && duration > 3s }
```

### 4.3 Error Traces

**All payment spans that resulted in an error**
```traceql
{ name = "http.payment" && status = error }
```

**All callback spans that resulted in an error**
```traceql
{ name =~ "http\\..*-callback" && status = error }
```

**Reconciliation job traces with errors**
```traceql
{ name = "quartz.reconciliation" && status = error }
```

### 4.4 Span-Level Drilldown

**Full causal chain for a given traceId** — Use Grafana's Tempo datasource Explore panel, paste the traceId from a Loki log line (field `traceId`) into the TraceID field. Grafana renders the full waterfall including:
- `http.payment` root span
- `FraudScoringService` child span
- `OrangeMoneyPort.initiateMerchantPayment` child span
- `OrangeTokenService.getAccessToken` nested child
- `OrangeMoneyClient.initTransaction` HTTP client span
- `OrangeMoneyClient.pay` HTTP client span

**Correlating a Loki log line to its trace:** In Grafana, enable the Loki → Tempo derived field. Map the log field `traceId` to the Tempo datasource. Clicking a log line opens the full trace waterfall in one click.

---

## 5. Incident-Specific Runbooks

### Incident: Provider Down (Circuit Breaker Open)

**Symptoms:** Spike in HTTP 503 from `/v1/payments`; `resilience4j_circuitbreaker_state` for Orange or MTN = 1.

**Step 1 — Confirm the circuit is open (Prometheus)**
```promql
resilience4j_circuitbreaker_state{name="orange"}
```

**Step 2 — Measure failure rate in the sliding window**
```promql
resilience4j_circuitbreaker_failure_rate{name="orange"}
```

**Step 3 — Find triggering errors in Loki**
```logql
{service="skillars", level="error"}
  | json
  | provider="ORANGE"
  | errorCode="PROVIDER_ERROR"
  | line_format "{{.timestamp}} tenant={{.tenantId}} txId={{.transactionId}} httpStatus={{.httpStatusCode}} msg={{.message}}"
```

**Step 4 — Check duration of the outage**
```promql
changes(resilience4j_circuitbreaker_state{name="orange"}[1h])
```

**Step 5 — Count transactions stuck in PROCESSING (need manual poll or retry)**
```logql
{service="skillars"} |= "status_poller" |= "PROCESSING"
```

**Step 6 — Trace a failed payment**  
From the Loki error line, copy `traceId`, open Tempo, and inspect the span for the root cause HTTP error.

---

### Incident: Elevated Payment Failure Rate

**Symptoms:** `payment_failed_total` rate is rising; merchant reports rejections.

**Step 1 — Overall failure ratio**
```promql
rate(payment_failed_total[5m])
  /
(rate(payment_success_total[5m]) + rate(payment_failed_total[5m]))
```

**Step 2 — Break down by error code (Loki)**
```logql
sum by (errorCode) (
  count_over_time(
    {service="skillars", level="error"} | json | __error__="" [5m]
  )
)
```

**Step 3 — Is fraud blocking too aggressively?**
```promql
rate(payment_fraud_blocked_total[5m]) / rate(payment_success_total[5m])
```

**Step 4 — Is it subscriber-inactive errors (bad phone numbers)?**
```logql
{service="skillars", level="error"}
  | json
  | errorCode="SUBSCRIBER_INACTIVE"
  | line_format "provider={{.provider}} tenant={{.tenantId}}"
```

**Count by provider and tenant:**
```logql
sum by (provider, tenantId) (
  count_over_time({service="skillars", level="error"} | json | errorCode="SUBSCRIBER_INACTIVE" [5m])
)
```

**Step 5 — Is it a specific tenant flooding bad requests?**
```logql
sum by (tenantId) (
  count_over_time({service="skillars", level="error"} | json | __error__="" [5m])
)
```

---

### Incident: Callbacks Not Arriving (Silent Payments)

Payments stay in `PROCESSING` state; neither callback nor poller updates them.

**Step 1 — Confirm callback rate has dropped**
```promql
rate(callback_received_total[5m])
```

**Step 2 — Check status poller activity in Loki**
```logql
{service="skillars"} |= "StatusPollerJob"
```

**Step 3 — Check for IP whitelist rejections (callbacks blocked before reaching controller)**
```logql
{service="skillars"} |= "ip_whitelist" |= "rejected"
```

**Step 4 — Check for Orange payToken expiry (payToken valid 60 min; polled transactions might be expired)**
```logql
{service="skillars", level="error"} |= "PayTokenExpiredException"
```

**Step 5 — Count PROCESSING transactions by age (SQL to cross-reference — Loki for logs)**
```logql
{service="skillars"} |= "poll_attempts" | json
  | line_format "txId={{.transactionId}} attempts={{.pollAttempts}} provider={{.provider}}"
```

---

### Incident: Reconciliation Discrepancy

**Symptoms:** Reconciliation job reports HIGH-severity discrepancies.

**Step 1 — Find the job run log**
```logql
{service="skillars"} |= "ReconciliationJob"
```

**Step 2 — List all HIGH-severity discrepancies for the last run**
```logql
{service="skillars", level="error"}
  | json
  | severity="HIGH"
  | line_format "type={{.discrepancyType}} txId={{.transactionId}} provider={{.provider}} localStatus={{.localStatus}} providerStatus={{.providerStatus}} localAmount={{.localAmount}} providerAmount={{.providerAmount}}"
```

**Step 3 — Filter for transactions missing from local records**
```logql
{service="skillars", level="error"}
  | json
  | discrepancyType="MISSING_FROM_LOCAL"
```

**Step 4 — Filter for amount mismatches**
```logql
{service="skillars", level="error"}
  | json
  | discrepancyType="AMOUNT_MISMATCH"
  | line_format "txId={{.transactionId}} localAmount={{.localAmount}} providerAmount={{.providerAmount}} diff={{.amountDelta}}"
```

**Step 5 — Trace a discrepant transaction end-to-end in Tempo**  
Copy the `transactionId` from the discrepancy log, search Tempo with:
```traceql
{ .transactionId = "discrepant-txn-id" }
```

---

### Incident: Fraud Engine Blocking Legitimate Traffic

**Symptoms:** Legitimate merchants report payments being blocked; `payment_fraud_blocked_total` rate spike.

**Step 1 — Fraud block rate relative to total traffic**
```promql
rate(payment_fraud_blocked_total[5m]) / rate(payment_success_total[5m])
```

**Step 2 — Which fraud signals are firing most?**

Display recent hits:
```logql
{service="skillars"} |= "velocity_limit_exceeded"
  | json
  | line_format "signal={{.fraudSignal}} count={{.observedCount}} window={{.windowSeconds}}s tenant={{.tenantId}}"
```

Count by signal type (use for bar chart):
```logql
sum by (fraudSignal) (
  count_over_time({service="skillars"} |= "velocity_limit_exceeded" | json [10m])
)
```

**Step 3 — Is a specific tenant the source?**
```logql
{service="skillars"} |= "FRAUD_BLOCKED"
  | json
  | line_format "tenant={{.tenantId}} score={{.riskScore}} signal={{.triggeringSignal}}"
```

**Step 4 — Distribution of risk scores for blocked transactions**
```logql
{service="skillars"} |= "FRAUD_BLOCKED"
  | json
  | unwrap riskScore
  | quantile_over_time(0.50, [1h])
```

---

### Incident: Webhook Delivery Backlog

**Symptoms:** Merchants not receiving payment status updates; webhook jobs retrying repeatedly.

**Step 1 — Webhook delivery failure rate**
```logql
count_over_time(
  {service="skillars", level="error"} |= "webhook_delivery" [5m]
)
```

**Step 2 — Permanently failed webhooks (alert condition)**
```logql
{service="skillars", level="error"} |= "FAILED_PERMANENT"
  | json
  | line_format "tenant={{.tenantId}} txId={{.transactionId}} url={{.webhookUrl}} attempts={{.retryCount}}"
```

**Step 3 — Which merchant URLs are failing?**

Display recent failures:
```logql
{service="skillars", level="error"} |= "webhook_delivery"
  | json
  | line_format "url={{.webhookUrl}} httpStatus={{.httpStatusCode}}"
```

Count by URL (use for bar chart):
```logql
sum by (webhookUrl) (
  count_over_time({service="skillars", level="error"} |= "webhook_delivery" | json [10m])
)
```

**Step 4 — Is it a single tenant's webhook endpoint being unreachable?**
```logql
{service="skillars", level="error"} |= "webhook_delivery"
  | json
  | tenantId="specific-tenant-id"
```

---

### Incident: Token Refresh Failure (Auth Loop)

**Symptoms:** All payments to Orange or MTN failing with 401; token service logs showing errors.

**Step 1 — Orange token errors**
```logql
{service="skillars", level="error"} |= "OrangeTokenService"
  | line_format "{{.message}}"
```

**Step 2 — MTN token errors**
```logql
{service="skillars", level="error"} |= "MtnTokenService"
  | line_format "{{.message}}"
```

**Step 3 — Rate of 401/403 responses from providers**
```logql
count_over_time(
  {service="skillars", level="error"}
  | json
  | httpStatusCode=~"401|403"
  [5m]
)
```

---

## 6. Grafana Dashboards

### 6.1 Skillars — Operations Overview (Golden Signals)

**Purpose:** Primary on-call dashboard. Answers "is the system healthy right now?" in under 10 seconds.

**Panels:**

| Panel | Query | Visualization |
|-------|-------|---------------|
| Payment Success Rate | `rate(payment_success_total[5m])` | Stat (green > 10/min) |
| Payment Failure Rate | `rate(payment_failed_total[5m])` | Stat (red if > 0) |
| Fraud Block Rate | `rate(payment_fraud_blocked_total[5m])` | Stat |
| TPS | `rate(payment_success_total[1m]) + rate(payment_failed_total[1m])` | Stat |
| Failure Ratio (%) | `rate(payment_failed_total[5m]) / (rate(payment_success_total[5m]) + rate(payment_failed_total[5m])) * 100` | Gauge (red > 5%) |
| Success vs Failure over time | Both rates | Time series |
| Provider p95 Latency | `histogram_quantile(0.95, sum by (le, provider) (rate(payment_provider_latency_seconds_bucket[5m])))` | Time series |
| Circuit Breaker State | `resilience4j_circuitbreaker_state{name=~"orange\|mtn", state="open"}` | State timeline (1=OPEN) |
| Callback Received Rate | `rate(callback_received_total[5m])` | Time series |
| Callback Failure Rate | `rate(callback_failed_total[5m])` | Stat |
| JVM Heap Utilisation | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100` | Gauge |
| DB Connection Pool Utilisation | `hikaricp_connections_active / hikaricp_connections_max * 100` | Gauge |
| Recent Error Logs | LogQL: `{service="skillars", level="error"}` | Logs panel |

---

### 6.2 Provider Health — Orange & MTN

**Purpose:** Deep-dive into individual provider behaviour during an incident.

> **Known gap:** `callback_received_total` and `callback_failed_total` currently have no `provider` tag (see `PaymentMetricsService`). Provider-split callback rate panels use the log query as a workaround until the tag is added to the counters.

**Panels:**

| Panel | Query | Visualization |
|-------|-------|---------------|
| Orange Circuit State | `resilience4j_circuitbreaker_state{name="orange", state="open"}` | State timeline |
| MTN Circuit State | `resilience4j_circuitbreaker_state{name="mtn", state="open"}` | State timeline |
| Orange Failure Rate (sliding window) | `resilience4j_circuitbreaker_failure_rate{name="orange"}` | Gauge |
| MTN Failure Rate (sliding window) | `resilience4j_circuitbreaker_failure_rate{name="mtn"}` | Gauge |
| Orange p50/p95/p99 Latency | `histogram_quantile(0.95, sum by (le) (rate(payment_provider_latency_seconds_bucket{provider="ORANGE"}[5m])))` | Time series |
| MTN p50/p95/p99 Latency | Same with `provider="MTN"` | Time series |
| Orange Callback Rate (log-derived) | `sum(rate({service="skillars"} \|= "orange-callback" [5m]))` | Time series |
| MTN Callback Rate (log-derived) | `sum(rate({service="skillars"} \|= "mtn-callback" [5m]))` | Time series |
| Orange Error Logs | `{service="skillars", level="error"} \| json \| provider="ORANGE"` | Logs panel |
| MTN Error Logs | `{service="skillars", level="error"} \| json \| provider="MTN"` | Logs panel |

---

### 6.3 Fraud & Risk Dashboard

**Purpose:** Monitor fraud engine health, block rates, and velocity rule effectiveness.

**Panels:**

| Panel | Query | Visualization |
|-------|-------|---------------|
| Fraud Block Rate | `rate(payment_fraud_blocked_total[5m])` | Stat |
| Fraud Block % of Total | `rate(payment_fraud_blocked_total[5m]) / (rate(payment_success_total[5m]) + rate(payment_failed_total[5m]) + rate(payment_fraud_blocked_total[5m])) * 100` | Gauge |
| Blocks Over Time | `rate(payment_fraud_blocked_total[5m])` | Time series |
| Top Fraud Signals Firing | `sum by (fraudSignal) (count_over_time({service="skillars"} \|= "velocity_limit_exceeded" \| json [5m]))` | Bar chart |
| Risk Score Distribution | `{service="skillars"} \|= "FRAUD_BLOCKED" \| json \| unwrap riskScore \| histogram_over_time([5m])` | Histogram |
| Blocked Transactions Log | `{service="skillars"} \|= "FRAUD_BLOCKED" \| json \| line_format "..."` | Logs panel |

---

### 6.4 Transaction Lifecycle & Reconciliation

**Purpose:** Daily reconciliation health and transaction state distribution.

**Panels:**

| Panel | Query | Visualization |
|-------|-------|---------------|
| Reconciliation Job Last Run | `{service="skillars"} \|= "ReconciliationJob" \|= "completed"` | Logs panel |
| HIGH Severity Discrepancies | `{service="skillars", level="error"} \| json \| severity="HIGH"` | Table |
| Discrepancy Types | LogQL count grouped by `discrepancyType` | Bar chart |
| Webhook Delivery Failures | `count_over_time({service="skillars", level="error"} \|= "webhook_delivery" [5m])` | Stat |
| Permanently Failed Webhooks | `count_over_time({service="skillars", level="error"} \|= "FAILED_PERMANENT" [24h])` | Stat |
| Idempotency Key Collisions | `count_over_time({service="skillars"} \|= "PAYMENT_ALREADY_PROCESSING" [5m])` | Time series |

---

## 7. Alerts

All alerts should route notifications to an on-call Slack channel.

> **Alert system routing:**
> - Alerts marked **\[Prometheus\]** use only PromQL — load them via `prometheus.yml` as standard alerting rules or import into Grafana Alerting with a Prometheus datasource.
> - Alerts marked **\[Loki\]** use LogQL — they must be configured in **Grafana Alerting** with the Loki datasource. They cannot be placed in a Prometheus alerting rules file.

### 7.1 Critical Alerts (P1 — Immediate Response)

**Payment failure ratio exceeds 5% for 2 minutes** `[Prometheus]`
```yaml
alert: PaymentFailureRateHigh
expr: |
  (
    rate(payment_failed_total[5m])
    /
    (rate(payment_success_total[5m]) + rate(payment_failed_total[5m]))
  ) > 0.05
for: 2m
labels:
  severity: critical
annotations:
  summary: "Payment failure rate {{ $value | humanizePercentage }} exceeds 5%"
  runbook: "Check OrchestratorError breakdown in Loki. Verify provider health."
```

**Orange circuit breaker is OPEN** `[Prometheus]`
```yaml
alert: OrangeCircuitBreakerOpen
expr: resilience4j_circuitbreaker_state{name="orange", state="open"} == 1
for: 30s
labels:
  severity: critical
annotations:
  summary: "Orange Money circuit breaker is OPEN — new payments will fail with 503"
  runbook: "Run incident runbook: Provider Down. Check Orange API status page."
```

**MTN circuit breaker is OPEN** `[Prometheus]`
```yaml
alert: MtnCircuitBreakerOpen
expr: resilience4j_circuitbreaker_state{name="mtn", state="open"} == 1
for: 30s
labels:
  severity: critical
annotations:
  summary: "MTN MoMo circuit breaker is OPEN — new payments will fail with 503"
```

**Provider latency p99 exceeds 10 seconds** `[Prometheus]`
```yaml
alert: ProviderLatencyP99Critical
expr: |
  histogram_quantile(0.99,
    sum by (le, provider) (
      rate(payment_provider_latency_seconds_bucket[5m])
    )
  ) > 10
for: 2m
labels:
  severity: critical
annotations:
  summary: "{{ $labels.provider }} p99 latency is {{ $value }}s — SLO breach"
```

### 7.2 High Alerts (P2 — Respond Within 30 Minutes)

**Callback receipt rate drops to zero while payments are still being submitted** `[Prometheus]`
```yaml
alert: CallbackRateZero
expr: |
  rate(callback_received_total[5m]) == 0
  and
  rate(payment_success_total[5m]) > 0.1
for: 5m
labels:
  severity: high
annotations:
  summary: "No callbacks received in 5 minutes — payments may be stuck in PROCESSING"
  runbook: "Check IP whitelist config and provider webhook configuration."
```

**Provider latency p95 exceeds 5 seconds** `[Prometheus]`
```yaml
alert: ProviderLatencyP95High
expr: |
  histogram_quantile(0.95,
    sum by (le, provider) (
      rate(payment_provider_latency_seconds_bucket[5m])
    )
  ) > 5
for: 3m
labels:
  severity: high
annotations:
  summary: "{{ $labels.provider }} p95 latency is {{ $value }}s"
```

**Database connection pool above 80%** `[Prometheus]`
```yaml
alert: DbConnectionPoolHigh
expr: hikaricp_connections_active / hikaricp_connections_max > 0.80
for: 2m
labels:
  severity: high
annotations:
  summary: "DB connection pool at {{ $value | humanizePercentage }} — starvation risk"
```

**Webhook delivery failures (any permanently failed webhooks)** `[Loki]`
```yaml
# Configure in Grafana Alerting with Loki datasource — not a Prometheus rule
alert: WebhookPermanentFailure
expr: |
  count_over_time(
    {service="skillars", level="error"} |= "FAILED_PERMANENT" [5m]
  ) > 0
for: 1m
labels:
  severity: high
annotations:
  summary: "Webhook permanently failed — merchant will not receive status update"
```

### 7.3 Warning Alerts (P3 — Investigate During Business Hours)

**Fraud block rate exceeds 20% of total traffic** `[Prometheus]`
```yaml
alert: FraudBlockRateHigh
expr: |
  rate(payment_fraud_blocked_total[5m])
    /
  (rate(payment_success_total[5m]) + rate(payment_failed_total[5m]) + rate(payment_fraud_blocked_total[5m]))
  > 0.20
for: 5m
labels:
  severity: warning
annotations:
  summary: "{{ $value | humanizePercentage }} of payments are being fraud-blocked — check if rules are too aggressive"
```

**JVM heap above 85%** `[Prometheus]`
```yaml
alert: JvmHeapHigh
expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
for: 5m
labels:
  severity: warning
annotations:
  summary: "JVM heap at {{ $value | humanizePercentage }}"
```

**Reconciliation job discrepancies detected** `[Loki]`
```yaml
# Configure in Grafana Alerting with Loki datasource — not a Prometheus rule
alert: ReconciliationDiscrepancy
expr: |
  count_over_time(
    {service="skillars", level="error"} |= "severity=\"HIGH\"" [1h]
  ) > 0
for: 1m
labels:
  severity: warning
annotations:
  summary: "Reconciliation found HIGH-severity discrepancies — review ReconciliationReport"
```

**Callback failure ratio above 10%** `[Prometheus]`
```yaml
alert: CallbackFailureRatioHigh
expr: rate(callback_failed_total[5m]) / rate(callback_received_total[5m]) > 0.10
for: 5m
labels:
  severity: warning
annotations:
  summary: "{{ $value | humanizePercentage }} of callbacks reference unknown transactions"
```

---

## 8. Correlation: Loki ↔ Tempo ↔ Prometheus

### Enabling Derived Fields in Grafana

In the Loki datasource settings, add a derived field to auto-link log lines to Tempo traces:

```json
{
  "name": "TraceID",
  "matcherRegex": "\"traceId\":\"([a-f0-9]{32})\"",
  "url": "${__value.raw}",
  "urlDisplayLabel": "View Trace",
  "datasourceUid": "<tempo-datasource-uid>"
}
```

Once configured, any Loki log line containing a `traceId` field renders a **View Trace** button that opens the full waterfall in Tempo.

### Correlation Workflow Example

1. **Alert fires** → PaymentFailureRateHigh (Prometheus/Grafana)
2. **Open Operations Overview dashboard** → identify spike in time range
3. **Drill to Loki** → filter `{service="skillars", level="error"}`, narrow time range
4. **Identify error code** → e.g., `PROVIDER_ERROR` for ORANGE
5. **Click TraceID** in the log line → Tempo opens the waterfall
6. **Inspect spans** → `OrangeMoneyClient.pay` span shows HTTP 502 from Orange API, 4.2s latency
7. **Return to Prometheus** → confirm circuit breaker failure rate was climbing before the alert
8. **Check Orange health endpoint** in Grafana → `OrangePlatformHealthIndicator` shows DOWN

---

## 9. Key MDC Fields Available in Every Log Line

These fields are available in all Loki queries via `| json` extraction:

| Field | Description | Example |
|-------|-------------|---------|
| `traceId` | OpenTelemetry trace ID (links to Tempo) | `a1b2c3d4...` |
| `transactionId` | Internal transaction UUID | `uuid-v4` |
| `externalReference` | Merchant's payment reference | `ORDER-12345` |
| `tenantId` | Merchant tenant identifier | `tenant-acme` |
| `provider` | Mobile money provider | `ORANGE` or `MTN` |
| `operation` | Business operation name | `initiate_payment` |
| `errorCode` | Standardised error code | `PROVIDER_ERROR` |
| `durationMs` | Wall-clock operation duration | `1247` |
| `httpStatusCode` | HTTP status from provider API | `502` |
| `riskScore` | Fraud risk score (0–100) | `78` |
| `fraudSignal` | Fraud signal that triggered | `MSISDN_VELOCITY` |
| `severity` | Reconciliation discrepancy severity | `HIGH` |
| `discrepancyType` | Reconciliation mismatch type | `AMOUNT_MISMATCH` |

---

## 10. Environment Variables and Endpoints Reference

| Component | Default | Override |
|-----------|---------|----------|
| Loki push URL | `http://localhost:3100` | `LOKI_URL` |
| Tempo OTLP HTTP | `http://localhost:4318/v1/traces` | `management.otlp.tracing.endpoint` |
| Prometheus scrape | `http://host.docker.internal:8367/manage/prometheus` | `prometheus.yml` |
| Grafana | `http://localhost:3000` | docker-compose |
| Metrics port | `8367` | `management.server.port` |
| Tracing sampling | `1.0` (100%) | `management.tracing.sampling.probability` |
