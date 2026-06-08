# Story Deploy-1.2: Core Docker Compose Service Stack

Status: done

## Story

As a developer,
I want all application services defined in `docker-compose.yml` with resource limits, health checks, restart policies, network isolation, Traefik TLS termination, graceful shutdown, and Docker log rotation,
So that I can start the entire production stack with a single `docker compose up -d` and no service can consume unbounded resources or be reached directly from outside the Node.

## Acceptance Criteria

**AC-1: Core services start healthy**
- Given a Node with Docker and Docker Compose installed and `/opt/skillars/.env` in place
- When `docker compose up -d` is executed from the project root directory
- Then the Application, PostgreSQL, Redis, and Traefik services all start and pass their health checks

**AC-2: All services have resource limits, health check, restart policy, and grace period**
- Given `docker-compose.yml` is inspected
- When each service definition is read
- Then every service defines `deploy.resources.limits` (explicit CPU + memory), a `healthcheck` block, `restart: unless-stopped`, and `stop_grace_period: 30s`

**AC-3: Network isolation is enforced — no service except Traefik exposes host ports**
- Given the stack is running
- When the Node's bound ports are inspected
- Then only Traefik is bound to host ports 80 and 443
- And Application, PostgreSQL, and Redis have no `ports:` mapping — they are reachable only over the named internal network
- And no direct external connection to PostgreSQL (5432) or Redis (6379) is possible

**AC-4: Docker log rotation is configured for every service**
- Given `docker-compose.yml` is inspected
- When each service's logging configuration is read
- Then `driver: json-file` with `max-size: "10m"` and `max-file: "3"` is applied to every service (using a YAML anchor or per-service `logging:` block)

**AC-5: Traefik routes HTTPS requests and obtains Let's Encrypt certificate**
- Given Traefik is running with `DOMAIN` and `LETSENCRYPT_EMAIL` set in `.env`
- When an HTTPS request is made to the configured domain
- Then Traefik returns a valid Let's Encrypt certificate and proxies the request to the application
- And certificate renewal is fully automatic

**AC-6: `/actuator/health` is reachable via Traefik and returns the Spring Boot health payload**
- Given the application is running (management port 8367, base-path `/manage`)
- When an HTTP GET request is made to `https://<DOMAIN>/actuator/health`
- Then Traefik rewrites the path to `/manage/health`, proxies to port 8367, and the response is the Spring Boot health JSON
- (This is required so Story 2.2 Smoke Test and Story 3.3 external uptime monitor can target `/actuator/health` as the epics specify)

**AC-7: Traefik dashboard is disabled or requires auth**
- Given Traefik is running in production configuration
- When an unauthenticated request is made to any Traefik API / dashboard endpoint
- Then the response is 401 or connection refused — dashboard is not accessible (FR-13)

**AC-8: Crashed services restart automatically**
- Given `restart: unless-stopped` on all services
- When a container exits unexpectedly
- Then Docker restarts it automatically without operator intervention

## Tasks / Subtasks

- [x] Task 1: Create `docker-compose.yml` at repo root (AC: 1, 2, 3, 4, 8)
  - [x] Add top-level `x-logging` YAML anchor: `driver: json-file`, `max-size: "10m"`, `max-file: "3"` — reference it in every service's `logging:` key
  - [x] Define `networks.skillars-internal` (bridge driver, no `internal: true` — app needs outbound internet for OTLP/email)
  - [x] Add `app` service: image `${APP_IMAGE}`, environment vars for datasource and Redis using Docker service names, health check on port 8367, resource limits, Traefik labels, `stop_grace_period: 30s`, `restart: unless-stopped`, `logging: *default-logging`, depends_on postgres+redis
  - [x] Add `postgres` service: `postgres:17-alpine`, bind mount `/opt/skillars/data/postgres:/var/lib/postgresql/data`, env vars `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` from `.env`, health check via `pg_isready`, resource limits, `stop_grace_period: 30s`, `restart: unless-stopped`, `logging: *default-logging`
  - [x] Add `redis` service: `redis:7-alpine`, command `redis-server --appendonly yes`, named volume `redis-data:/data`, health check via `redis-cli ping`, resource limits, `stop_grace_period: 30s`, `restart: unless-stopped`, `logging: *default-logging`
  - [x] Add `traefik` service: `traefik:v3.3`, bind mounts for Docker socket (`:ro`) + `/opt/skillars/traefik/acme.json:/etc/traefik/acme.json` + `./deploy/traefik/traefik.yml:/etc/traefik/traefik.yml:ro`, ports `80:80` and `443:443`, command arg `--certificatesresolvers.letsencrypt.acme.email=${LETSENCRYPT_EMAIL}`, health check via ping, resource limits, `stop_grace_period: 30s`, `restart: unless-stopped`, `logging: *default-logging`
  - [x] Add `volumes:` section for `redis-data` named volume
  - [x] Add comment block at end of file: `# ============ LGTM Stack (added in Story 1.3) ============`

- [x] Task 2: Add Traefik labels on `app` service for two routers (AC: 5, 6, 7)
  - [x] Main app router `app-main`: matches `Host(\`${DOMAIN}\`) && !Path(\`/actuator/health\`)`, entrypoint `websecure`, TLS cert resolver `letsencrypt`, backend service `app-main-svc` on port 9990
  - [x] Health router `app-health`: matches `Host(\`${DOMAIN}\`) && Path(\`/actuator/health\`)`, entrypoint `websecure`, TLS cert resolver `letsencrypt`, applies middleware `health-rewrite`, backend service `app-health-svc` on port 8367
  - [x] Middleware `health-rewrite`: `replacepathregex` with `regex=/actuator/(.*)` and `replacement=/manage/$$1` (double `$$` in Compose label to escape `$`)
  - [x] HTTP → HTTPS redirect router `app-http`: handled by Traefik static config entrypoint redirect (web → websecure permanent 301)

- [x] Task 3: Create `deploy/traefik/traefik.yml` static config (AC: 5, 7)
  - [x] Set `global.checkNewVersion: false` and `global.sendAnonymousUsage: false`
  - [x] Set `log.level: ERROR`
  - [x] Set `ping: {}` (enables Traefik healthcheck endpoint at `:8080/ping`)
  - [x] Set `api.insecure: false` and `api.dashboard: false`
  - [x] Define `entryPoints.web` (`:80`) with HTTP→HTTPS redirect to `websecure`
  - [x] Define `entryPoints.websecure` (`:443`)
  - [x] Define `certificatesResolvers.letsencrypt.acme.httpChallenge.entryPoint: web` and `storage: /etc/traefik/acme.json` (email provided via CLI arg in compose)
  - [x] Set `providers.docker.exposedByDefault: false` and `providers.docker.network: skillars-internal`

- [x] Task 4: Create `.env.example` at repo root (AC: all)
  - [x] Document every required env var with description and example format (no real values):
    - `APP_IMAGE` — GHCR image tag (e.g., `ghcr.io/org/repo:sha-abc123`)
    - `DOMAIN` — Production domain (e.g., `api.example.com`)
    - `LETSENCRYPT_EMAIL` — Email for Let's Encrypt certificate notifications
    - `POSTGRES_DB` — Database name (e.g., `skillars`)
    - `POSTGRES_USER` — Database username
    - `POSTGRES_PASSWORD` — Database password (generate with `openssl rand -base64 32`)
    - `SPRING_DATASOURCE_URL` — will be `jdbc:postgresql://postgres:5432/${POSTGRES_DB}?TimeZone=UTC`
    - Any app-specific secrets (JWT secret, SMTP, Bunny.net API key, etc.)
  - [x] Add note: "Place this file as `/opt/skillars/.env` on the Node with `chmod 600 /opt/skillars/.env`"
  - [x] Add note: "Create acme.json before first run: `touch /opt/skillars/traefik/acme.json && chmod 600 /opt/skillars/traefik/acme.json`"
  - [x] Verify `.env` is in `.gitignore` (confirmed present — `.env` and `.env.*` in .gitignore, `!.env.example` excludes the template)

- [x] Task 5: Create `deploy/traefik/README.md` (AC: 5)
  - [x] Document pre-flight checklist: create acme.json with mode 600, place .env, set DOMAIN and LETSENCRYPT_EMAIL
  - [x] Note that traefik.yml is the static config; dynamic config comes from Docker container labels

## Dev Notes

### This story is infrastructure-only — no Java code changes

Files to create:
- `docker-compose.yml` (new, at repo root — this is the **production** compose file)
- `deploy/traefik/traefik.yml` (new)
- `deploy/traefik/README.md` (new)
- `.env.example` (new, at repo root)

**Do NOT touch:** `docker-compose-lgtm.yaml`, `prometheus.yml`, `alerts.yml`, `grafana-*.yml`, `tempo.yml` — these are dev/local LGTM tooling. Production LGTM services are added in Story 1.3.

---

### Critical: Application Has Two Ports

From `src/main/resources/application.yaml`:
- **Line 3:** `server.port: "${port:9990}"` → main application HTTP port
- **Lines 255–262:** `management.server.port: 8367`, `management.endpoints.web.base-path: /manage`

Health endpoint inside Docker network: `http://app:8367/manage/health`
**NOT** `http://app:9990/actuator/health`

Stories 2.2 and 3.3 require the Smoke Test and uptime monitor to target `/actuator/health`. This story MUST create the Traefik path-rewrite routing to satisfy that requirement.

---

### Critical: Traefik `/actuator/health` Path Rewrite

The `app` service needs exactly these labels (copy-paste ready):

```yaml
labels:
  - "traefik.enable=true"

  # Main app router → port 9990
  - "traefik.http.routers.app-main.rule=Host(`${DOMAIN}`) && !Path(`/actuator/health`)"
  - "traefik.http.routers.app-main.entrypoints=websecure"
  - "traefik.http.routers.app-main.tls.certresolver=letsencrypt"
  - "traefik.http.routers.app-main.service=app-main-svc"
  - "traefik.http.services.app-main-svc.loadbalancer.server.port=9990"

  # Health rewrite router → port 8367
  - "traefik.http.routers.app-health.rule=Host(`${DOMAIN}`) && Path(`/actuator/health`)"
  - "traefik.http.routers.app-health.entrypoints=websecure"
  - "traefik.http.routers.app-health.tls.certresolver=letsencrypt"
  - "traefik.http.routers.app-health.middlewares=health-rewrite"
  - "traefik.http.routers.app-health.service=app-health-svc"
  - "traefik.http.services.app-health-svc.loadbalancer.server.port=8367"

  # Middleware: /actuator/health → /manage/health
  - "traefik.http.middlewares.health-rewrite.replacepathregex.regex=/actuator/(.*)"
  - "traefik.http.middlewares.health-rewrite.replacepathregex.replacement=/manage/$$1"
  # NOTE: $$1 (double dollar) is required in Docker Compose label strings to prevent variable interpolation
```

---

### Traefik Static Config (`deploy/traefik/traefik.yml`)

```yaml
global:
  checkNewVersion: false
  sendAnonymousUsage: false

log:
  level: ERROR

ping: {}  # required for healthcheck CMD: traefik healthcheck --ping

api:
  insecure: false
  dashboard: false   # satisfies FR-13

entryPoints:
  web:
    address: ":80"
    http:
      redirections:
        entryPoint:
          to: websecure
          scheme: https
          permanent: true
  websecure:
    address: ":443"

certificatesResolvers:
  letsencrypt:
    acme:
      # email is injected via CLI arg: --certificatesresolvers.letsencrypt.acme.email=...
      storage: /etc/traefik/acme.json
      httpChallenge:
        entryPoint: web

providers:
  docker:
    exposedByDefault: false
    network: skillars-internal  # must match the network name in docker-compose.yml
```

**Why email via CLI, not in traefik.yml:** `traefik.yml` does not support `${ENV_VAR}` interpolation reliably across versions. Pass `LETSENCRYPT_EMAIL` via the Traefik service `command:` in docker-compose.yml to allow env var substitution.

---

### Critical: `acme.json` Must Pre-Exist on Server with mode 600

Traefik refuses to start if `acme.json` does not exist OR has permissions other than 600. Story 1.1's `provision.sh` creates `/opt/skillars/traefik/` but NOT `acme.json`. Must be created manually before first `docker compose up -d`:

```bash
touch /opt/skillars/traefik/acme.json && chmod 600 /opt/skillars/traefik/acme.json
```

Document this in `.env.example` and `deploy/traefik/README.md`. Failing to do this is a common first-run failure point.

---

### Application Service: Override `localhost` to Docker Service Names

`application.yaml` defaults use `localhost` for datasource and Redis host. In Docker Compose, services communicate via service name. The `app` service MUST override:

```yaml
environment:
  - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB:-skillars}?TimeZone=UTC
  - SPRING_DATASOURCE_USERNAME=${POSTGRES_USER:-postgres}
  - SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}
  - SPRING_DATA_REDIS_HOST=redis
  - SPRING_DATA_REDIS_PORT=6379
  # Pass any additional secrets from .env
```

Spring Boot honors `SPRING_DATASOURCE_URL` and `SPRING_DATA_REDIS_HOST` as environment variable overrides for `spring.datasource.url` and `spring.data.redis.host` respectively (Spring's relaxed binding converts `.` → `_` and upper-cases).

---

### Resource Limits Budget (CX32: 4 vCPU / 8GB RAM)

Story 1.3 will add LGTM stack. Budget with that in mind:

| Service | `cpus` | `memory` | Rationale |
|---------|--------|----------|-----------|
| `app` | `"2.0"` | `2g` | JVM needs headroom for GC |
| `postgres` | `"1.0"` | `1536m` | Shared buffer + work_mem |
| `redis` | `"0.25"` | `256m` | Cache only |
| `traefik` | `"0.25"` | `256m` | Proxy, very lightweight |
| *Reserved for LGTM (1.3)* | *~0.5* | *~4g* | Prometheus + Grafana + Loki + Tempo |

Use `deploy.resources.limits` syntax (Compose v3+), NOT the deprecated top-level `mem_limit:` / `cpus:`:
```yaml
deploy:
  resources:
    limits:
      cpus: "2.0"
      memory: 2g
```

---

### Health Check Commands (ready to use)

```yaml
# App — use wget (available in most JRE base images); start_period must cover JVM warmup
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:8367/manage/health || exit 1"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 60s

# PostgreSQL
healthcheck:
  test: ["CMD", "pg_isready", "-U", "${POSTGRES_USER:-postgres}", "-d", "${POSTGRES_DB:-skillars}"]
  interval: 10s
  timeout: 5s
  retries: 5
  start_period: 20s

# Redis
healthcheck:
  test: ["CMD", "redis-cli", "ping"]
  interval: 10s
  timeout: 5s
  retries: 3

# Traefik (requires ping: {} in traefik.yml)
healthcheck:
  test: ["CMD", "traefik", "healthcheck", "--ping"]
  interval: 10s
  timeout: 5s
  retries: 3
```

---

### Log Rotation: Use YAML Anchor for DRY

```yaml
x-logging: &default-logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"

services:
  app:
    logging: *default-logging
  postgres:
    logging: *default-logging
  redis:
    logging: *default-logging
  traefik:
    logging: *default-logging
```

This is the standard YAML anchor pattern — define once, reference everywhere. Do not repeat the `logging:` block per service.

---

### Network: One Named Bridge (No `internal: true`)

The application needs outbound internet access for: OTLP exporter, email sending (SMTP), Bunny.net API calls. Setting `internal: true` on the network would block this. Use a named bridge without `internal: true`:

```yaml
networks:
  skillars-internal:
    driver: bridge
    # Do NOT set internal: true — app needs outbound internet
```

All services connect to `skillars-internal`. Only Traefik exposes host ports (`ports: ["80:80", "443:443"]`). PostgreSQL and Redis have no `ports:` declaration at all — they are network-isolated from the host.

---

### PostgreSQL Volume: Bind Mount (Not Named Volume)

PostgreSQL data MUST be on the Hetzner Volume at `/opt/skillars/data/postgres`. Use a bind mount, NOT a named Docker volume (which would store data on the ephemeral root SSD):

```yaml
postgres:
  volumes:
    - /opt/skillars/data/postgres:/var/lib/postgresql/data
```

`/opt/skillars/data/postgres` was created by `provision.sh` (story 1.1). The Hetzner Volume must be mounted and the directory must exist before `docker compose up -d`.

---

### Redis: Named Volume (Not Bind Mount)

Redis uses append-only persistence (`redis-server --appendonly yes`). Data goes to a named Docker volume `redis-data` (not the Hetzner Volume — Redis data is cache and does not require the same durability guarantees as PostgreSQL):

```yaml
redis:
  command: redis-server --appendonly yes
  volumes:
    - redis-data:/data

volumes:
  redis-data:
```

---

### Story Scope Boundary

This story covers: App + PostgreSQL + Redis + Traefik only.  
**Story 1.3** adds: Prometheus, Grafana, Loki, Tempo.

Leave a clear comment block at the end of `docker-compose.yml`:
```yaml
  # ============================================================
  # LGTM Stack services (Prometheus, Grafana, Loki, Tempo)
  # will be added here in Story 1.3
  # ============================================================
```

Story 1.3 will directly edit `docker-compose.yml` (not use a separate override file) per its acceptance criteria wording ("added to docker-compose.yml").

---

### Traefik v3 vs v2 Differences

Use `traefik:v3.3` (current stable). Relevant v3 notes:
- Docker label syntax for routers/services/middlewares is **unchanged** from v2 — copy-paste from v2 examples works
- `ReplacePathRegex` middleware syntax is the same: `replacepathregex.regex` / `replacepathregex.replacement`
- The `$$1` double-dollar for Docker Compose label env-var escaping applies to both v2 and v3
- `ping: {}` in static config works the same way

---

### Traefik Docker Socket Security Note

Traefik requires `/var/run/docker.sock` read access. Mount as `:ro` (read-only):
```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock:ro
```
This is the standard production pattern for single-node Docker setups. A more hardened alternative (Tecnativa Docker Socket Proxy) adds complexity out of scope for this project.

---

### Project Structure Notes

- `docker-compose.yml` at repo root — this is the production compose file
- `deploy/traefik/traefik.yml` — Traefik v3 static config (mounted into container)
- `deploy/traefik/README.md` — pre-flight notes
- `.env.example` at repo root — template for server `.env`, committed to git (no secret values)
- `deploy/` directory already exists (created in story 1.1)
- `deploy/traefik/` directory: create it in this story

The `deploy/provision.sh` (story 1.1) already prepares `/opt/skillars/traefik/` on the server. No changes needed to `provision.sh`.

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 1.2 — Acceptance Criteria]
- [Source: _bmad-output/implementation-artifacts/deploy-1-1-server-provisioning-script-iac-firewall.md#Dev Notes — critical health endpoint flag, directory structure, existing files to not modify]
- [Source: src/main/resources/application.yaml#Lines 1-3 — server.port=9990]
- [Source: src/main/resources/application.yaml#Lines 255-283 — management.server.port=8367, base-path=/manage]
- [Source: _bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/addendum.md#Implementation Values — stop_grace_period, log rotation values, .env mode 600]
- [Source: requirements/deployment/deployment-proposal.md#Resource Constraints, #Secrets Management]
- [Source: _bmad-output/planning-artifacts/prds/prd-javatemplate-2026-06-03/epics.md#FR-7, FR-8, FR-9, FR-10, FR-12, FR-13, FR-20]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

None — infrastructure-only story, no debugging required.

### Completion Notes List

- Created production `docker-compose.yml` with 4 services (app, postgres, redis, traefik), all satisfying AC-2 requirements: resource limits, healthchecks, `restart: unless-stopped`, `stop_grace_period: 30s`.
- Used YAML anchor `x-logging: &default-logging` for DRY log rotation across all services (AC-4).
- Network isolation achieved: only Traefik exposes host ports 80/443; app/postgres/redis are internal-only (AC-3).
- Traefik label split-routing: `app-main` → port 9990, `app-health` → port 8367 with `replacepathregex` middleware mapping `/actuator/health` → `/manage/health` (AC-6). `$$1` double-dollar escapes Compose variable interpolation.
- HTTP→HTTPS redirect handled by Traefik static config `entryPoints.web.http.redirections` (cleaner than a per-router label).
- `traefik.yml` has `api.dashboard: false` and `api.insecure: false` (AC-7 / FR-13).
- `LETSENCRYPT_EMAIL` passed via Compose `command:` arg (not traefik.yml) because traefik.yml does not interpolate env vars reliably across versions.
- PostgreSQL uses bind mount to `/opt/skillars/data/postgres` (Hetzner Volume); Redis uses named Docker volume `redis-data` (cache-only, no Hetzner Volume needed).
- `.env.example` documents all required vars including app secrets (JWT, SMTP, Bunny.net); acme.json pre-flight note included.
- `deploy/traefik/README.md` covers all pre-flight steps: acme.json creation, DNS requirement, first-run verification.

### File List

- `docker-compose.yml` (new)
- `deploy/traefik/traefik.yml` (new)
- `deploy/traefik/README.md` (new)
- `.env.example` (new)

## Change Log

- 2026-06-03: Implemented story Deploy-1.2 — created production docker-compose.yml (app, postgres, redis, traefik), Traefik static config, .env.example, and deploy/traefik/README.md. All 8 ACs satisfied.
