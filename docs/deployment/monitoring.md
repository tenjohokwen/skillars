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
- Payment throughput (success rate, failure rate)
- Payment provider latency (p95, p99 by provider)
- JVM metrics (heap usage, GC activity)
- HikariCP database connection pool utilisation
- Circuit breaker state (Orange Money, MTN MoMo)

For ad-hoc log queries (Loki) or trace lookups (Tempo), use Grafana's built-in **Explore** view — select the appropriate datasource (Loki or Tempo) from the dropdown.

---

## Alert Notifications

All alerts route to the `notify-ops` contact point:
- **Email:** address configured in `GF_ALERT_NOTIFY_EMAIL`
- **Slack:** webhook configured in `GF_SLACK_WEBHOOK_URL`

Both variables must be set in `/opt/skillars/.env` for notifications to reach the operator.

**Notification policy:** group wait 30s, group interval 5m, repeat interval 4h.

All alert rules appear in Grafana under **Alerting → Alert rules → Skillars Alerts** folder.

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

#### PaymentFailureRateHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-prometheus-alerts)

**Meaning:** Payment failure rate exceeded 5% over a 5-minute window. May indicate a provider outage, misconfiguration, or an upstream fraud spike.

**Response:**

1. Check the circuit breaker state in the Skillars Dashboard. If `OrangeCircuitBreakerOpen` or `MtnCircuitBreakerOpen` is also firing, the provider is down — follow the circuit breaker response below.
2. Check app logs for `OrchestratorError`:

```bash
docker compose logs app --tail=100 | grep -i "OrchestratorError"
```

3. Check provider status pages (Orange Money, MTN MoMo).
4. If neither provider is down, check `FraudBlockRateHigh` — elevated fraud blocks count as failures in this metric.

---

#### OrangeCircuitBreakerOpen

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-prometheus-alerts)

**Meaning:** The Orange Money circuit breaker tripped OPEN. All new Orange Money payments are immediately rejected with 503.

**Response:**

1. Check the Orange Money API status page.
2. Check app logs for connection errors to Orange:

```bash
docker compose logs app --tail=100 | grep -i "orange"
```

3. If the provider is down: wait for the circuit breaker's automatic half-open retry. The breaker tests a single request after a configured wait period and closes automatically if the provider recovers.
4. If the provider is restored but the breaker has not closed: restart the app service to reset the in-memory circuit breaker state:

```bash
docker compose restart app
```

---

#### MtnCircuitBreakerOpen

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts) and `deploy/lgtm/grafana-alerts.yml` (skillars-prometheus-alerts)

**Meaning:** The MTN Mobile Money circuit breaker tripped OPEN. All new MTN payments are immediately rejected with 503.

**Response:** Same as `OrangeCircuitBreakerOpen` — substitute MTN MoMo API status page and MTN-related log entries.

---

#### ProviderLatencyP99Critical

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** A payment provider's p99 response latency exceeded 10 seconds. Payments are still succeeding but very slowly — SLO breach risk.

**Response:**

1. Identify which provider is affected from the Grafana alert label (`provider`).
2. Check the provider's status page for degraded performance.
3. Check app logs for timeout patterns:

```bash
docker compose logs app --tail=100 | grep -i "timeout"
```

4. If latency is sustained for more than 15 minutes and multi-provider failover is supported, consider temporarily routing traffic to the other provider.

---

### High Alerts

---

#### WebhookPermanentFailure

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-loki-alerts)

**Meaning:** A payment webhook exhausted all retry attempts and was moved to the dead-letter state. The merchant will not receive the payment status update automatically.

**Response:**

1. Find the affected transaction in Loki:

```
{service="skillars"} |= "FAILED_PERMANENT"
```

2. Note the `transactionId` from the log entry.
3. Check whether the merchant's webhook endpoint is reachable.
4. If the endpoint is restored, re-trigger the webhook manually via the Admin API.
5. If the endpoint is permanently unreachable, notify the merchant through an alternative channel.

---

#### CallbackRateZero

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** No payment callbacks were received in 5 minutes while payments are being actively completed (more than 0.1 successful payments/second). Payments may be stuck in PROCESSING state indefinitely.

**Response:**

1. Verify the provider webhook URL configured in the provider's portal matches the application's public callback endpoint.
2. Check whether the Node's public IP is on the provider's IP whitelist — providers only send callbacks to whitelisted addresses.
3. Check app logs for callback receipt or rejection:

```bash
docker compose logs app --tail=100 | grep -i "callback"
```

4. If callbacks are being received but rejected: check the authentication signature verification in logs.

---

#### ProviderLatencyP95High

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** A payment provider's p95 latency exceeded 5 seconds. Not yet a SLO breach but trending toward one.

**Response:**

1. Identify the affected `provider` from the Grafana alert annotation.
2. Monitor — this is a warning signal, not a critical failure.
3. If the condition persists for more than 30 minutes, follow the `ProviderLatencyP99Critical` response procedure.

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

#### ReconciliationDiscrepancy

**Source:** `deploy/lgtm/grafana-alerts.yml` (skillars-loki-alerts)

**Meaning:** The reconciliation job found a HIGH-severity discrepancy between provider records and internal payment state. At least one payment may be in an incorrect state.

**Response:**

1. Find the reconciliation report in Loki:

```
{service="skillars"} |= "severity=\"HIGH\""
```

2. Identify the affected `transactionId`(s) and the discrepancy type from the log entry.
3. Compare the affected transactions against the provider's status via the provider portal or API.
4. Manually correct the payment state via the Admin API if the internal state is wrong.
5. Monitor for repeat discrepancies — if the alert fires repeatedly, escalate to a code-level investigation of the provider sync logic.

---

#### FraudBlockRateHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** More than 20% of payment attempts are being blocked by fraud rules. Rules may be misconfigured or there is a genuine fraud spike.

**Response:**

1. Determine the pattern of blocks in Loki:

```
{service="skillars"} |= "fraud_blocked"
```

2. Determine whether blocks are from a single source (potential attack) or distributed (rules misconfiguration).
3. If the rules are too aggressive: review and adjust fraud rule thresholds — no code change required, this is a configuration concern.
4. If this is genuine fraud: no infrastructure action is required; alert the fraud or compliance team.

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

#### CallbackFailureRatioHigh

**Source:** `deploy/lgtm/alerts.yml` (skillars-alerts)

**Meaning:** More than 10% of received callbacks reference unknown transaction IDs. May be stale callbacks from a previous deployment, test callbacks from the provider, or a state management issue.

**Response:**

1. Check Loki for the failure reason:

```
{service="skillars"} |= "callback_failed"
```

2. If callbacks reference old transaction IDs: these may be from a previous payment session and are expected after a redeploy — suppress if confirmed harmless.
3. If new transactions are failing their callbacks: investigate the transaction lifecycle for state loss between the callback receipt and the lookup.

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
