# Story Deploy-1.3: LGTM Observability Stack

Status: done

## Story

As a developer,
I want Prometheus, Grafana, Loki, and Tempo added to the Docker Compose stack with version-controlled retention configuration and resource limits,
so that application metrics and logs are queryable from day one without disk exhaustion risk.

## Acceptance Criteria

**AC-1: All LGTM services start healthy with resource limits, restart policy, grace period**
- Given the LGTM services are added to `docker-compose.yml`
- When `docker compose up -d` is executed
- Then Prometheus, Grafana, Loki, and Tempo all start and pass their health checks
- And every LGTM service defines `deploy.resources.limits` (explicit CPU + memory), a `healthcheck` block, `restart: unless-stopped`, and `stop_grace_period: 30s`

**AC-2: Loki 30-day and Prometheus 15-day retention are enforced in version-controlled config**
- Given `deploy/lgtm/loki.yml` and the Prometheus command args are inspected
- When the retention configuration is read
- Then Loki compactor is configured with `retention_period: 720h` (30 days) in `deploy/lgtm/loki.yml`
- And Prometheus retention is set via `--storage.tsdb.retention.time=15d` in the `command:` block in `docker-compose.yml`
- And neither limit is set only in a UI — both are in version-controlled files

**AC-3: Application logs are queryable via Loki in Grafana**
- Given the application is running with `LOKI_ENABLED=true` and `LOKI_URL=http://loki:3100`
- When a developer opens Grafana's Explore view with the Loki data source
- Then application logs appear and are filterable

**AC-4: Application metrics are visible in Grafana via Prometheus**
- Given Prometheus is scraping `app:8367` at `/manage/prometheus`
- When a developer opens the Grafana dashboard
- Then application metrics (e.g., `http_server_requests_seconds_count`) are visible

**AC-5: No raw LGTM port is exposed on the host network**
- Given the LGTM stack is running
- When the Node's bound ports are inspected
- Then no Prometheus (9090), Loki (3100), Tempo (3200/4317/4318), or Grafana (3000) port is bound on the host
- And external access to Grafana goes only via Traefik at `https://${MONITORING_DOMAIN}`

**AC-6: Grafana is accessible through Traefik with HTTPS and requires authentication**
- Given Traefik is running and `MONITORING_DOMAIN` is set
- When a browser navigates to `https://${MONITORING_DOMAIN}`
- Then Grafana serves a login form (not anonymous access)
- And the admin credentials are `GF_SECURITY_ADMIN_USER` / `GF_SECURITY_ADMIN_PASSWORD` from `.env`

**AC-7: LGTM data is stored on the Hetzner Volume (not root SSD)**
- Given the Hetzner Volume is mounted at `/opt/skillars/data`
- When LGTM services are running and generating data
- Then Prometheus writes to `/opt/skillars/data/prometheus`, Loki to `/opt/skillars/data/loki`, Tempo to `/opt/skillars/data/tempo`, and Grafana to `/opt/skillars/data/grafana` via bind mounts

---

## Tasks / Subtasks

- [x] Task 1: Create `deploy/lgtm/` production config directory with all LGTM config files (AC: 2, 3, 4)
  - [x] Create `deploy/lgtm/prometheus.yml` — scrapes `app:8367` at `/manage/prometheus` (NOT `host.docker.internal`); includes `rule_files: [alerts.yml]`
  - [x] Create `deploy/lgtm/alerts.yml` — copy from root `alerts.yml` (same content, production-mounted copy)
  - [x] Create `deploy/lgtm/loki.yml` — full Loki config with `retention_period: 720h`, compactor retention enabled, data path `/loki`
  - [x] Create `deploy/lgtm/tempo.yml` — Tempo config with OTLP gRPC/HTTP receivers and storage path `/var/tempo` (bind-mounted from Hetzner Volume)
  - [x] Create `deploy/lgtm/grafana-datasources.yml` — adapted from root `grafana-datasources.yml`; service URLs use Docker names (`prometheus:9090`, `loki:3100`, `tempo:3200`) — identical content as root file
  - [x] Copy `deploy/lgtm/grafana-alerts.yml` — from root `grafana-alerts.yml`
  - [x] Copy `deploy/lgtm/grafana-dashboard-config.yml` — from root `grafana-dashboard-config.yml`, update `path` to `/etc/grafana/provisioning/dashboards`
  - [x] Copy `deploy/lgtm/skillars-dashboard.json` — from root `skillars-dashboard.json`

- [x] Task 2: Add LGTM services to `docker-compose.yml` (replacing the placeholder comment block) (AC: 1, 2, 5)
  - [x] Add `prometheus` service: `prom/prometheus:v3`, bind-mount `./deploy/lgtm/prometheus.yml:/etc/prometheus/prometheus.yml:ro` and `./deploy/lgtm/alerts.yml:/etc/prometheus/alerts.yml:ro`, data bind-mount `/opt/skillars/data/prometheus:/prometheus`, command `--config.file=...` + `--storage.tsdb.retention.time=15d`, no `ports:`, health check, resource limits `cpus: "0.5" memory: 768m`, `restart: unless-stopped`, `stop_grace_period: 30s`, `logging: *default-logging`, `networks: [skillars-internal]`
  - [x] Add `loki` service: `grafana/loki:3.4.2`, bind-mount `./deploy/lgtm/loki.yml:/etc/loki/loki.yml:ro`, data bind-mount `/opt/skillars/data/loki:/loki`, command `-config.file=/etc/loki/loki.yml`, no `ports:`, health check, resource limits `cpus: "0.5" memory: 768m`, `restart: unless-stopped`, `stop_grace_period: 30s`, `logging: *default-logging`, `networks: [skillars-internal]`
  - [x] Add `tempo` service: `grafana/tempo:2.9.0`, bind-mount `./deploy/lgtm/tempo.yml:/etc/tempo.yaml:ro`, data bind-mount `/opt/skillars/data/tempo:/var/tempo`, command `-config.file=/etc/tempo.yaml`, no `ports:`, health check, resource limits `cpus: "0.25" memory: 512m`, `restart: unless-stopped`, `stop_grace_period: 30s`, `logging: *default-logging`, `networks: [skillars-internal]`
  - [x] Add `grafana` service: `grafana/grafana:11.4.0`, env vars `GF_SECURITY_ADMIN_USER`, `GF_SECURITY_ADMIN_PASSWORD`, `GF_SERVER_ROOT_URL=https://${MONITORING_DOMAIN}`, `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_AUTH_DISABLE_LOGIN_FORM=false`, bind-mount all `deploy/lgtm/grafana-*.yml` and `skillars-dashboard.json` at provisioning paths, data bind-mount `/opt/skillars/data/grafana:/var/lib/grafana`, Traefik labels (see Dev Notes), no direct `ports:`, health check, resource limits `cpus: "0.25" memory: 256m`, `restart: unless-stopped`, `stop_grace_period: 30s`, `logging: *default-logging`, `networks: [skillars-internal]`

- [x] Task 3: Update `app` service in `docker-compose.yml` with LGTM env vars (AC: 3, 4)
  - [x] Add to `app` service `environment:` block:
    - `LOKI_URL=http://loki:3100`
    - `LOKI_ENABLED=true`
    - `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`
  - [x] Add optional soft deps on loki and tempo to app's `depends_on:` (condition: `service_started` — not healthy, to avoid blocking startup if LGTM is slow)

- [x] Task 4: Update `deploy/provision.sh` — create LGTM data dirs on Hetzner Volume (AC: 7)
  - [x] In the "Recreate sub-directories on mounted volume" section (line ~126), add:
    ```bash
    mkdir -p "${MOUNT_POINT}/prometheus"
    mkdir -p "${MOUNT_POINT}/loki"
    mkdir -p "${MOUNT_POINT}/tempo"
    mkdir -p "${MOUNT_POINT}/grafana"
    ```
  - [x] Update the next-steps log message to include "Deploy the LGTM stack (Story 1.3)"

- [x] Task 5: Update `.env.example` with LGTM-related variables (AC: 5, 6)
  - [x] Add section `# LGTM Observability Stack` with:
    - `MONITORING_DOMAIN` — subdomain for Grafana (e.g., `monitoring.api.example.com`)
    - `GF_SECURITY_ADMIN_USER=admin`
    - `GF_SECURITY_ADMIN_PASSWORD` — generate with `openssl rand -base64 24`
    - `LOKI_URL=http://loki:3100` (copied into app container env)
    - `LOKI_ENABLED=true`
    - `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`

### Review Findings

- [x] [Review][Patch] YAML structure: LGTM services placed under `volumes:` block, not `services:` — entire stack cannot start [docker-compose.yml:141-251]
- [x] [Review][Patch] Loki 3.4.2 incompatible with `boltdb-shipper` + `schema: v11`; must use `tsdb` store + `schema: v13` [deploy/lgtm/loki.yml:21-27]
- [x] [Review][Patch] Missing `uid:` fields on Grafana datasources; dashboard panel UIDs and cross-datasource links will not resolve [deploy/lgtm/grafana-datasources.yml]
- [x] [Review][Patch] Grafana unified alert rules use `queryType: range` for Loki; must be `instant` — rules will never fire [deploy/lgtm/grafana-alerts.yml:17,34]
- [x] [Review][Patch] Missing `traefik.http.routers.grafana.service=grafana-svc` label; Traefik router cannot reach Grafana on port 3000 [docker-compose.yml]
- [x] [Review][Patch] Tempo has no retention or compaction policy; trace storage grows unboundedly on the Hetzner Volume [deploy/lgtm/tempo.yml]
- [x] [Review][Patch] provision.sh creates data dirs as root; Grafana (UID 472), Loki/Tempo (UID 10001), Prometheus (UID 65534) will fail to write — add `chown` after each `mkdir -p` [deploy/provision.sh:127-130]
- [x] [Review][Patch] Loki compactor missing `shared_store: filesystem` — retention deletion silently skipped [deploy/lgtm/loki.yml:32-38]
- [x] [Review][Patch] `GF_SECURITY_ADMIN_PASSWORD` has no `:-` fallback default; unset env var passes empty string to Grafana [docker-compose.yml:220]
- [x] [Review][Patch] Grafana memory limit is 256MB; documented minimum is 512MB — will OOM under any real alerting/dashboard load [docker-compose.yml]
- [x] [Review][Patch] `prom/prometheus:v3` is a floating major-version tag; all other images are pinned to exact versions [docker-compose.yml:145]
- [x] [Review][Defer] Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh, PaymentFailureRateHigh) [deploy/lgtm/alerts.yml] — deferred, pre-existing in root `alerts.yml`; copied per spec
- [x] [Review][Defer] `DbConnectionPoolHigh` alert has no label selector — aggregates across all pools [deploy/lgtm/alerts.yml] — deferred, pre-existing in root `alerts.yml`
- [x] [Review][Defer] TraceID regex only matches lowercase hex; may miss uppercase OTel trace IDs [deploy/lgtm/grafana-datasources.yml] — deferred, pre-existing in root `grafana-datasources.yml`
- [x] [Review][Defer] `spanStartTimeShift`/`spanEndTimeShift` of 1h is extremely wide; causes heavy Loki queries on every trace click [deploy/lgtm/grafana-datasources.yml] — deferred, pre-existing in root `grafana-datasources.yml`
- [x] [Review][Defer] Prometheus has no `depends_on: app` — cold-start scrape failures on first boot [deploy/lgtm/prometheus.yml] — deferred, design choice; acceptable operational gap
- [x] [Review][Defer] LGTM data `mkdir -p` calls gated inside Hetzner Volume device check — consistent with existing postgres pattern [deploy/provision.sh:127-130] — deferred, pre-existing pattern

### Review Findings — Round 2

- [x] [Review][Patch] `GF_SECURITY_ADMIN_PASSWORD` passes empty string when unset — no `:?` guard; change to `${GF_SECURITY_ADMIN_PASSWORD:?GF_SECURITY_ADMIN_PASSWORD must be set}` [docker-compose.yml]
- [x] [Review][Patch] `MONITORING_DOMAIN` has no fail-loud guard — unset var produces `GF_SERVER_ROOT_URL=https://` and empty Traefik `Host()` rule [docker-compose.yml]
- [x] [Review][Patch] Grafana has no `depends_on` for Prometheus, Loki, or Tempo — provisioned alert rules evaluate at startup (interval: 1m) and immediately error with "datasource not found" [docker-compose.yml]
- [x] [Review][Patch] `max_block_bytes: 100_000_000` uses YAML-invalid underscore numeric literal — parsed as string, causing Tempo startup error or silent use of default [deploy/lgtm/tempo.yml]
- [x] [Review][Patch] Grafana unified alert Prometheus rules use `queryType: range` — should be `instant` (Patch 4 fixed Loki rules but left Prometheus rules unchanged) [deploy/lgtm/grafana-alerts.yml]
- [x] [Review][Patch] `compacted_block_retention: 10m` too aggressive — Tempo default is 1h; at 10m, an OOM kill during compaction can cause permanent trace gaps [deploy/lgtm/tempo.yml]
- [x] [Review][Defer] `chown` runs unconditionally on every provision.sh execution — safe for first run, re-running against a live system can corrupt in-progress container writes [deploy/provision.sh] — deferred, operational concern
- [x] [Review][Defer] `${MOUNT_POINT}/postgres` has no `chown` after `mkdir -p` — Postgres container (UID 999) will fail to write on a fresh volume [deploy/provision.sh:126] — deferred, pre-existing from Story 1.2
- [x] [Review][Defer] Duplicate logical alert definitions in `alerts.yml` (Prometheus) and `grafana-alerts.yml` (Grafana unified) for PaymentFailureRateHigh, OrangeCircuitBreakerOpen, MtnCircuitBreakerOpen — different notification paths; no Alertmanager currently configured — deferred, by design


---

## Dev Notes

### This story is infrastructure-only — no Java code changes

Files to create:
- `deploy/lgtm/prometheus.yml`
- `deploy/lgtm/alerts.yml`
- `deploy/lgtm/loki.yml`
- `deploy/lgtm/tempo.yml`
- `deploy/lgtm/grafana-datasources.yml`
- `deploy/lgtm/grafana-alerts.yml`
- `deploy/lgtm/grafana-dashboard-config.yml`
- `deploy/lgtm/skillars-dashboard.json`

Files to UPDATE (not create new):
- `docker-compose.yml` — replace placeholder comment block with 4 LGTM service definitions; add env vars to `app` service
- `.env.example` — add LGTM variable section
- `deploy/provision.sh` — add LGTM data directory creation on Hetzner Volume

---

### CRITICAL: Dev configs vs Production configs

The root-level YAML files (`prometheus.yml`, `alerts.yml`, `docker-compose-lgtm.yaml`, etc.) are **development/local tools only**. They reference `host.docker.internal:8367` which does not work inside Docker networking.

**DO NOT reuse or reference root-level LGTM files in production docker-compose.yml.** All production LGTM configs live under `deploy/lgtm/`.

---

### Production Prometheus Config (`deploy/lgtm/prometheus.yml`)

```yaml
global:
  scrape_interval: 45s

rule_files:
  - "alerts.yml"

scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/manage/prometheus'
    scrape_interval: 45s
    static_configs:
      - targets: ['app:8367']
```

Key differences from root `prometheus.yml`:
- Target is `app:8367` (Docker service name), NOT `host.docker.internal:8367`
- `alerts.yml` is bind-mounted alongside it at `/etc/prometheus/alerts.yml`

---

### Production Loki Config (`deploy/lgtm/loki.yml`)

```yaml
auth_enabled: false

server:
  http_listen_port: 3100
  grpc_listen_port: 9096

common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    instance_addr: 127.0.0.1
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2020-10-24
      store: boltdb-shipper
      object_store: filesystem
      schema: v11
      index:
        prefix: index_
        period: 24h

limits_config:
  retention_period: 720h

compactor:
  working_directory: /loki/compactor
  compaction_interval: 10m
  retention_enabled: true
  retention_delete_delay: 2h
  retention_delete_worker_count: 150
```

The `/loki` path is bind-mounted from `/opt/skillars/data/loki` (Hetzner Volume). Retention is configured **in this file**, satisfying FR-17 and NFR-10 (version-controlled, not UI-only).

---

### Production Tempo Config (`deploy/lgtm/tempo.yml`)

Adapt from root `tempo.yml` — key difference: storage path `/var/tempo` (bind-mounted from Hetzner Volume, not `/tmp`):

```yaml
server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318

storage:
  trace:
    backend: local
    local:
      path: /var/tempo/blocks
```

The `/var/tempo` path is bind-mounted from `/opt/skillars/data/tempo`.

---

### Grafana: Production Auth (CRITICAL — no anonymous access)

Dev `docker-compose-lgtm.yaml` has:
```yaml
GF_AUTH_ANONYMOUS_ENABLED=true
GF_AUTH_DISABLE_LOGIN_FORM=true
```

**Do NOT use these in production.** Production Grafana must require login:

```yaml
environment:
  - GF_SECURITY_ADMIN_USER=${GF_SECURITY_ADMIN_USER:-admin}
  - GF_SECURITY_ADMIN_PASSWORD=${GF_SECURITY_ADMIN_PASSWORD}
  - GF_SERVER_ROOT_URL=https://${MONITORING_DOMAIN}
  - GF_AUTH_ANONYMOUS_ENABLED=false
  - GF_AUTH_DISABLE_LOGIN_FORM=false
```

---

### Grafana Traefik Labels (Traefik v3)

Grafana needs Traefik labels to be accessible externally. Use `MONITORING_DOMAIN` env var:

```yaml
labels:
  - "traefik.enable=true"
  - "traefik.http.routers.grafana.rule=Host(`${MONITORING_DOMAIN}`)"
  - "traefik.http.routers.grafana.entrypoints=websecure"
  - "traefik.http.routers.grafana.tls.certresolver=letsencrypt"
  - "traefik.http.services.grafana-svc.loadbalancer.server.port=3000"
```

Traefik will issue a separate Let's Encrypt certificate for `${MONITORING_DOMAIN}`. Ensure DNS has an A record for `MONITORING_DOMAIN` pointing to the same Node IP.

---

### Grafana Datasources Config

`deploy/lgtm/grafana-datasources.yml` is identical to root `grafana-datasources.yml` — service names (`prometheus:9090`, `loki:3100`, `tempo:3200`) already match Docker service names. Copy as-is.

---

### Grafana Dashboard Config (`deploy/lgtm/grafana-dashboard-config.yml`)

Adapt from root `grafana-dashboard-config.yml`. The `path` field must match the container mount path:

```yaml
apiVersion: 1
providers:
  - name: 'skillars'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /etc/grafana/provisioning/dashboards
```

---

### Health Checks for LGTM Services

```yaml
# Prometheus
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:9090/-/healthy || exit 1"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s

# Loki
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:3100/ready || exit 1"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s

# Tempo
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:3200/ready || exit 1"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s

# Grafana
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:3000/api/health || exit 1"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s
```

All use `wget` (available in the official images). `start_period: 30s` is sufficient — LGTM services boot faster than the JVM.

---

### Resource Limits Budget

Story 1.2 reserved `~0.5 cpu / ~4g` for LGTM. The actual production allocations (all 4 LGTM services):

| Service | `cpus` | `memory` | Rationale |
|---------|--------|----------|-----------|
| `prometheus` | `"0.5"` | `768m` | TSDB query + storage; 15-day retention bounds memory usage |
| `loki` | `"0.5"` | `768m` | Chunk cache + compactor; 30-day retention bounds disk usage |
| `tempo` | `"0.25"` | `512m` | Trace ingestion only; local backend is lightweight |
| `grafana` | `"0.25"` | `256m` | UI only; very low footprint |

Total LGTM: 1.5 cpu / 2.3g  
Total all services: ~5 cpu / ~6.3g on a 4 vCPU / 8 GB node  
CPU limits are burst ceilings (not reservations) — services won't all peak simultaneously.

Use `deploy.resources.limits` syntax (same as story 1.2):
```yaml
deploy:
  resources:
    limits:
      cpus: "0.5"
      memory: 768m
```

---

### App Service: Override OTLP and Loki Env Vars

From `application.yaml`:
- `loki.url: ${LOKI_URL:http://localhost:3100}` → override with `LOKI_URL=http://loki:3100`
- `loki.enabled: ${LOKI_ENABLED:false}` → override with `LOKI_ENABLED=true`
- `management.otlp.tracing.endpoint: "http://localhost:4318/v1/traces"` → override with `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`

Spring Boot relaxed binding converts `MANAGEMENT_OTLP_TRACING_ENDPOINT` → `management.otlp.tracing.endpoint`. These must be added to the `app` service `environment:` block in `docker-compose.yml`.

---

### Provision.sh: Add LGTM Data Directories on Hetzner Volume

`deploy/provision.sh` already creates `/opt/skillars/lgtm` on the root SSD. That directory is for config files — data must go on the Hetzner Volume at `/opt/skillars/data/`.

In the Hetzner Volume section of `provision.sh` (around line 126–127), after `mkdir -p "${MOUNT_POINT}/postgres"`, add:

```bash
mkdir -p "${MOUNT_POINT}/prometheus"
mkdir -p "${MOUNT_POINT}/loki"
mkdir -p "${MOUNT_POINT}/tempo"
mkdir -p "${MOUNT_POINT}/grafana"
```

The Hetzner Volume is mounted at `${DEPLOY_ROOT}/data` which is `/opt/skillars/data`. This ensures Prometheus TSDB, Loki chunks, Tempo blocks, and Grafana state survive Node reimaging.

---

### LGTM Service Image Versions

Use fixed tags — not `latest`:
- `prom/prometheus:v3` (matches dev `docker-compose-lgtm.yaml`)
- `grafana/loki:3.4.2` (current stable; dev uses `latest` — lock it for production)
- `grafana/tempo:2.9.0` (matches dev `docker-compose-lgtm.yaml`)
- `grafana/grafana:11.4.0` (current stable; dev uses `latest` — lock it for production)

---

### Network: All LGTM Services on `skillars-internal`

All four LGTM services join `skillars-internal` (same as all other services). No service exposes `ports:` to the host. Grafana gets Traefik labels instead.

The app service communicates to Loki and Tempo by Docker service name:
- `loki:3100` — Logback Loki appender endpoint
- `tempo:4317` / `tempo:4318` — OTLP gRPC / HTTP for traces

Prometheus communicates to the app:
- `app:8367` — Spring Boot management port for Prometheus scrape

These all resolve via the Docker bridge network (`skillars-internal`) without any host port exposure.

---

### Story Scope Boundary

This story covers: Prometheus, Grafana, Loki, Tempo only.  
Story 1.4 adds: Security hardening (SSH, fail2ban, .env permissions, Traefik dashboard auth).  
Story 1.5 adds: First-time setup documentation.

Do NOT attempt security hardening or documentation changes in this story.

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 1.3 — Acceptance Criteria]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-17 — LGTM stack in docker-compose with resource limits, health checks, restart policies]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-10 — Loki 30 days, Prometheus 15 days, version-controlled]
- [Source: _bmad-output/implementation-artifacts/deploy-1-2-core-docker-compose-service-stack.md#Resource Limits Budget — LGTM reserved ~0.5 cpu / ~4g]
- [Source: _bmad-output/implementation-artifacts/deploy-1-2-core-docker-compose-service-stack.md#Story Scope Boundary — Story 1.3 adds LGTM directly to docker-compose.yml]
- [Source: docker-compose-lgtm.yaml — dev LGTM baseline; adapt for production (no host ports, no anonymous auth)]
- [Source: prometheus.yml — dev scrape config; production replaces host.docker.internal with Docker service name]
- [Source: grafana-datasources.yml — datasource config; same URLs work in production (Docker service names)]
- [Source: src/main/resources/application.yaml#Lines 149-151 — loki.url / loki.enabled env var keys]
- [Source: src/main/resources/application.yaml#Lines 289-291 — management.otlp.tracing.endpoint env var key]
- [Source: deploy/provision.sh#Lines 125-131 — Hetzner Volume subdir pattern; add lgtm subdirs here]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

### Completion Notes List

### File List
