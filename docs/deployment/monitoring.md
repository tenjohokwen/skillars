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
