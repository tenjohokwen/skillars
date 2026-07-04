# Local Deployment Guide

Run Skillars on your own machine using Docker — no domain or TLS required. This
trims the production [`docker-compose.yml`](../../docker-compose.yml) down to
the services the app needs to run (`app`, `postgres`, `redis`, with `loki` and
`tempo` coming along automatically — see [Notes](#notes) below), plus
`prometheus` and `grafana` so you can browse metrics/traces locally instead of
relying solely on `docker logs` (see [Step 6.1](#step-61-browse-grafana-optional)
and the [Logs](#logs) section — app logs specifically aren't wired up to
Grafana yet, see below).

For the full production stack (Traefik/TLS, backups), see
[`first-time-setup.md`](first-time-setup.md) instead.

---

## Prerequisites

- Docker Engine + Docker Compose plugin (`docker compose version`)
- A local clone of this repository

---

## Step 1: Build the image

The production `docker-compose.yml` only references `image: ${APP_IMAGE}` (pulled
from GHCR) — there's no `build:` section, so build the image yourself first:

```bash
docker build -t skillars:local .
```

---

## Step 2: Create your local env file

```bash
cp .env.example .env.local
```

Use `.env.local`, not `.env`. `first-time-setup.md` (Step 5) also does
`cp .env.example .env` at this same repo root, as a staging file it later
`scp`s to the production Node. If you use this clone for both local dev and
prepping a production deploy, a shared `.env` means whichever you run second
overwrites the other's values. `.env.local` is a separate file, loaded
explicitly via `--env-file` below, so the two never collide. It's already
covered by the `.env.*` line in `.gitignore`.

For local use you only need to set the values below — everything else in
`.env.example` (domain, TLS email, Bunny CDN, Grafana/SMTP alerting, Hetzner
backup credentials) is for the production stack and can be left as-is or removed.

| Variable | Required? | Local value |
|---|---|---|
| `APP_IMAGE` | Yes | `skillars:local` (the tag from Step 1) |
| `POSTGRES_PASSWORD` | Yes | Any value, e.g. `localdev` — no default is provided |
| `GF_SECURITY_ADMIN_PASSWORD` | Yes | This is your real Grafana `admin` login password (see [Step 6.1](#step-61-browse-grafana-optional)) — pick something you'll remember, e.g. `localdev` |
| `MONITORING_DOMAIN` | Yes (placeholder only) | Any value, e.g. `unused` |
| `GF_ALERT_NOTIFY_EMAIL` | Yes (placeholder only) | Any non-empty email-shaped value, e.g. `alerts@localhost` |
| `GF_SLACK_WEBHOOK_URL` | Yes (placeholder only) | Any non-empty URL, e.g. `https://hooks.slack.com/services/unused/unused/unused` |
| `POSTGRES_DB` | No | Defaults to `skillars` |
| `POSTGRES_USER` | No | Defaults to `postgres` |

`GF_SECURITY_ADMIN_PASSWORD` and `MONITORING_DOMAIN` use Compose's hard-required
`${VAR:?error}` syntax on the `grafana` service, so both must resolve to
*something* or the command fails outright with `invalid interpolation format`.
This guide now starts `grafana` locally (see Step 4), so
`GF_SECURITY_ADMIN_PASSWORD` is a real, actually-used value — set it to
whatever you want your local Grafana login to be. `MONITORING_DOMAIN` only
feeds `GF_SERVER_ROOT_URL` and Traefik labels (`traefik` itself never starts
here), so it can stay a throwaway placeholder.

`GF_ALERT_NOTIFY_EMAIL` and `GF_SLACK_WEBHOOK_URL` aren't Compose-required,
but *are* required in practice: `deploy/lgtm/grafana-alerts.yml` provisions an
email + Slack contact point for infra alerts using these two variables, and
Grafana validates each contact point at startup — an email integration with a
blank `addresses` or a Slack integration with a blank `url` both fail
validation and crash the container in a restart loop (found by actually
running this locally, not a hypothetical). Any non-empty placeholder value
satisfies the validation; neither is ever actually sent to anywhere locally.
Once both are set, you'll reach Grafana at
`http://localhost:3000` regardless of what it's set to.

Everything else the app reads (mail credentials, video/payment/AI provider
keys, PIN encryption secret) resolves to safe placeholder defaults **only**
when the Spring `dev` profile is active — which is what the override file in
the next step turns on. You do not need to set `JWT_SECRET`; the JWT signing
key is stored in the database, not read from an environment variable (see
Step 4).

**Important:** `.env.local` only feeds `${VAR}` placeholders written directly
in the compose YAML files — it is *not* automatically injected into a
container's process environment. A variable only reaches the app's JVM if
`docker-compose.yml` or `docker-compose.local.yml` explicitly lists it under
that service's `environment:`. All four variables above qualify because
`docker-compose.yml` already references them (`${APP_IMAGE}`,
`${POSTGRES_PASSWORD}`, etc.). If you ever need to pass some *other* Spring
property through to the app, add it to `docker-compose.local.yml`'s
`app.environment` list (Step 3) — adding it only to `.env.local` silently does
nothing, which is exactly what happened during this guide's own testing (see
Step 3's `APP_VIDEO_BUNNY_LIBRARY_ID`).

---

## Step 3: Add a local compose override

`docker-compose.yml` doesn't publish the app's ports to your host (only
Traefik does that in production) and doesn't set `SPRING_PROFILES_ACTIVE`.
Create `docker-compose.local.yml` next to it:

It also mounts data directories from fixed host paths that `provision.sh`
creates on the production Node — `postgres` (`/opt/skillars/data/postgres`),
`loki` and `tempo` (`/opt/skillars/data/{loki,tempo}`, pulled in as
dependencies of `app` — see [Notes](#notes)), and `prometheus`/`grafana`
(`/opt/skillars/data/{prometheus,grafana}`, pulled in as a dependency of
`grafana`). None of those paths exist on your machine, and Docker Desktop
refuses to bind-mount a host directory it hasn't been granted access to.
Replace all five with plain named volumes, and publish Grafana's port so you
can reach its UI from the host:

```yaml
services:
  app:
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - APP_VIDEO_BUNNY_LIBRARY_ID=123456
      - MANAGEMENT_HEALTH_MAIL_ENABLED=false
    ports:
      - "9990:9990"   # main app
      - "8367:8367"   # actuator/health (management port)
  postgres:
    volumes:
      - skillars-local-postgres:/var/lib/postgresql/data
  loki:
    volumes:
      - skillars-local-loki:/loki
  tempo:
    volumes:
      - skillars-local-tempo:/var/tempo
  prometheus:
    volumes:
      - skillars-local-prometheus:/prometheus
  grafana:
    ports:
      - "3000:3000"   # Grafana UI
    volumes:
      - skillars-local-grafana:/var/lib/grafana

volumes:
  skillars-local-postgres:
  skillars-local-loki:
  skillars-local-tempo:
  skillars-local-prometheus:
  skillars-local-grafana:
```

Compose merges `volumes:` entries by matching container target path, so each
of these overrides only replaces the matching production bind mount — the
other mounts on `loki`/`tempo`/`prometheus`/`grafana` (their read-only
`./deploy/lgtm/*.yml` config files, relative paths, not a problem) are
untouched. The top-level `volumes:` block adds the five new named volumes
alongside `redis-data` (already declared in `docker-compose.yml`) rather than
replacing it. `traefik` has the same kind of fixed host-path/config
dependency, but since it never starts in this trimmed stack, it's never
evaluated and doesn't need an override.

The two extra `app.environment` entries fix real startup failures found by
actually running this stack, not hypothetical ones:

- **`APP_VIDEO_BUNNY_LIBRARY_ID=123456`** — `VideoProviderConfig`'s
  `videoProviderAdapter` bean eagerly validates `app.video.bunny.library-id`
  as numeric. It defaults to an empty string, and that bean sits behind a
  non-lazy dependency chain (`DrillLibraryResource` → `DrillLibraryService`),
  so the *entire app context* fails to start over an unused Bunny video
  setting. `123456` is the same placeholder the integration test suite
  already uses (`src/test/resources/application-test.yaml`).
- **`MANAGEMENT_HEALTH_MAIL_ENABLED=false`** — Spring Boot Actuator's
  `MailHealthIndicator` doesn't just check config, it opens a real SMTP
  connection to `mail.gmx.net` and authenticates with whatever mail password
  is configured. The `dev` profile's password is a placeholder
  (`dev_mail_password`), so that connection always fails authentication,
  which drags the aggregate `/manage/health` to `DOWN` (HTTP 503) — and
  since `docker-compose.yml`'s healthcheck treats any non-2xx response as
  failure, the container never reports `healthy`. Disabling this one
  indicator sidesteps a check that was never going to succeed locally
  anyway.

Keep this as a separate file passed explicitly with `-f` (rather than naming
it `docker-compose.override.yml`, which Compose auto-loads). The deploy
scripts in `first-time-setup.md` run `docker compose up -d` with no `-f` flags
on the production Node — an auto-loaded override sitting in the repo would
silently leak `dev` profile and exposed ports into that deploy.

---

## Step 4: Start the stack

Every command from here on needs the same three flags — `-f docker-compose.yml
-f docker-compose.local.yml --env-file .env.local` — so it's worth an alias:

```bash
alias dcl='docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local'
```

This is the single command that brings everything up:

```bash
dcl up -d app postgres redis grafana
```

Compose will also start `loki` and `tempo` automatically — `app` declares a
hard `depends_on` on both (`condition: service_started`), so they come up even
though they weren't named explicitly. Naming `grafana` similarly pulls in
`prometheus`, since `grafana` depends on it (`condition: service_started`).
`traefik` and `node_exporter` are not dependencies of anything named here and
stay stopped.

Give it a minute, then confirm everything is healthy:

```bash
dcl ps
```

`app` runs its database migrations (Flyway) on this first startup, which is
why the next step can't happen until the container is up.

---

## Step 5: Seed required bootstrap data

The app stores its JWT signing key as an encrypted row in the `main.sec`
table rather than an environment variable. **Without this row, login and any
authenticated request fail** with `AppSetupException: JWT secret key has not
been set in DB`.

Seed it using the same fixture the integration tests use
(`src/test/resources/sql/secData.sql`):

```bash
dcl exec -T postgres psql -U postgres -d skillars < src/test/resources/sql/secData.sql
```

(Substitute `-U`/`-d` if you changed `POSTGRES_USER`/`POSTGRES_DB` in `.env.local`.)

### Optional: sample login users

`src/test/resources/sql/initTestData.sql` seeds the same JWT secret row
**plus** roles and test accounts (including an admin). If you want ready-made
logins instead of registering through the API, run this file instead of
`secData.sql` (not both — both insert the same `main.sec` row and the second
will fail on the unique constraint):

```bash
dcl exec -T postgres psql -U postgres -d skillars < src/test/resources/sql/initTestData.sql
```

Notable accounts it creates (password `admin*123!` for all):

| Login | Notes |
|---|---|
| `me@yahoo.com` | Regular activated user (`ROLE_USER`) |
| `queb@yahoo.com` | Admin (`ROLE_ADMIN`, `ROLE_USER`, `ROLE_LTD_ADMIN`) |
| `not-activated@yahoo.com` | Inactive — for testing the activation flow |
| `locked@yahoo.com` | Locked — for testing the locked-account flow |

---

## Step 6: Verify

```bash
curl -s http://localhost:8367/manage/health
# {"status":"UP"}
```

This whole flow (build → `.env.local` → override file → `up` → seed → this
health check) was run end-to-end while writing this guide, including chasing
down each failure above by reading actual container logs rather than
guessing — not just assembled from reading the config.

`management.server.port` (8367) runs as a separate embedded server from the
main app port (9990), so health/actuator endpoints are only reachable on 8367
— not under `/manage` or `/actuator` on 9990. Application API traffic (e.g.
login, once you've seeded data) goes to `http://localhost:9990`. The frontend,
if you run it separately, expects the API there too (see
`custom.cors.allowed-origins` in `application.yaml`).

---

## Step 6.1: Browse Grafana (optional)

Open `http://localhost:3000` and log in with user `admin` and whatever you set
`GF_SECURITY_ADMIN_PASSWORD` to in `.env.local`. The `loki`, `tempo`, and
`prometheus` datasources are already provisioned (via
`deploy/lgtm/grafana-datasources.yml`), along with the dashboards under
`deploy/lgtm/skillars-dashboard.json` — traces (Tempo) and metrics
(Prometheus) are queryable through the UI. Logs are the exception — see the
[Logs](#logs) section below for why `Loki` currently returns nothing.

`GF_SERVER_ROOT_URL` is still set to `https://${MONITORING_DOMAIN}` (whatever
placeholder you gave it in Step 2) since that variable is shared with the
production stack. This only affects things like OAuth callback URLs, which
this guide doesn't use — plain username/password login and browsing at
`http://localhost:3000` both work fine regardless of what `MONITORING_DOMAIN`
is set to.

---

## Tearing down

```bash
dcl down
```

Add `-v` to also delete the Postgres data volume and start fresh next time
(you'll need to repeat Step 5 after doing this).

---

## Troubleshooting

**`no space left on device` in container logs (Postgres/Loki/Tempo/Prometheus/Redis
crash-looping, `node_exporter` permission errors)** — this is Docker
Desktop's own virtual disk, not your Mac's disk; check both:

```bash
df -h /                # host disk — probably fine
docker system df        # Docker's virtual disk — probably the real problem
```

Rebuilding the image repeatedly (`docker build -t skillars:local .`) without
cleanup accumulates dangling image layers and build cache over time until
Docker Desktop's fixed-size virtual disk fills up. Reclaim the safe stuff
first (dangling images and build cache are never referenced by anything and
are always safe to remove):

```bash
docker builder prune -af
docker image prune -af
```

If you need more, `docker volume prune -f` (no `--all`) removes only
*anonymous* volumes (random-hex names, orphaned once their container was
removed) — it never touches this project's named volumes
(`skillars_skillars-local-postgres`, etc.) or any other project's named
volumes, even while stopped. Skip `--all` unless you specifically mean to
remove named-but-unattached volumes too, since those could belong to other
projects.

After freeing space, `dcl down` then `dcl up -d app postgres redis grafana`
again — Postgres/Loki/Tempo/Prometheus/Redis all recover cleanly from a
disk-full crash on restart (WAL/AOF replay), no need to wipe volumes with `-v`
unless something still looks broken afterward.

---

## Notes

- This guide intentionally does not touch the checked-in `docker-compose.yml`
  — it stays exactly as production uses it. All local-only behavior (ports,
  `dev` profile) lives in `docker-compose.local.yml`, which you pass in
  explicitly and never gets picked up by the production deploy.
- You'll see `WARN: The "DOMAIN" variable is not set. Defaulting to a blank
  string.` — harmless. Unlike the two Grafana variables above, `DOMAIN` isn't
  declared required (no `:?`), so it just warns and interpolates to an empty
  string in the (unused) `traefik` labels.
- `loki` and `tempo` start regardless of the service list you pass to `up`,
  because `app`'s `depends_on` requires them to be *started* (not
  necessarily healthy) before it boots; `prometheus` similarly starts because
  `grafana` depends on it. This is harmless locally — all are lightweight —
  but explains why you'll see extra containers you didn't ask for by name.
- If you'd rather run the app outside Docker for a tighter debug loop (e.g.
  attaching a remote debugger), see the commented steps at the top of
  `src/test/resources/sql/initTestData.sql`, which assume `postgres` and
  `redis` running locally (e.g. via `dcl up -d postgres redis`) and the app
  started with `mvn spring-boot:run -Dspring-boot.run.profiles=dev`.
- `tempo`'s healthcheck in `docker-compose.yml` used to be broken
  independently of anything in this guide: `CMD-SHELL` requires `/bin/sh`,
  but the `grafana/tempo` image has no shell at all (only a BusyBox `wget` at
  a non-standard path) — so the healthcheck could never pass, in production
  either. Fixed in `docker-compose.yml` to call `wget --spider` directly via
  the exec-form `CMD` (no shell needed). Two similar bugs — `loki.yml`
  missing `compactor.delete_request_store` and `tempo.yaml`'s compactor
  fields needing to nest under `compaction:` — were fixed the same way,
  in `deploy/lgtm/loki.yml` and `deploy/lgtm/tempo.yaml`, since both would
  have crash-looped identically on a fresh production deploy.



## Logs

Two ways to check logs locally: directly via Docker, or through Grafana (see
[Step 6.1](#step-61-browse-grafana-optional)) now that it's part of the local
stack. Docker is faster for a quick one-off look; Grafana is better once
you're correlating logs across a whole request or comparing against
metrics/traces.

### Via Docker

Logs are structured JSON (one line per log event) written to stdout.

**Basic tail:**

```bash
docker logs skillars-app-1 -f --tail 100
```

(`-f` follows in real time, like `tail -f`.)

Or with your `dcl` alias (defined in Step 4): `dcl logs -f app`

**Time-boxed** (avoid dumping the whole history):

```bash
docker logs skillars-app-1 --since 10m
```

**Pretty-print / filter with `jq`** (recommended, since each line is JSON):

```bash
docker logs skillars-app-1 --since 10m 2>&1 | jq -r 'select(.level=="ERROR")'
docker logs skillars-app-1 --since 10m 2>&1 | jq -r '"\(.["@timestamp"]) \(.level) \(.logger_name) \(.message)"'
```

**Grep for a specific thing** (e.g. email suppression, a specific endpoint):

```bash
docker logs skillars-app-1 --since 30m 2>&1 | grep -i "NoOp SES\|player/register"
```

**Follow one request end-to-end** — every log line for a request carries the
same `traceId`/`requestId`, so once you spot one, you can pull the whole
lifecycle:

```bash
docker logs skillars-app-1 --since 30m 2>&1 | jq -r 'select(.traceId=="3305fe25da99d40a66e25deb150faed0")'
```

Key fields to know in the JSON: `level`, `logger_name`, `message`,
`traceId`/`spanId`/`requestId` (for correlating a request), `operation` +
`httpStatus` (on `request_start`/`request_end` lines from `LoggingFilter`).

Other containers work the same way — swap the container name:
`skillars-postgres-1`, `skillars-redis-1`, `skillars-loki-1`,
`skillars-tempo-1`, `skillars-prometheus-1`, `skillars-grafana-1`.

### Via Grafana

**Not wired up yet** — `docker logs`/`dcl logs` above is currently the only
way to see app logs, local or production. `Loki` is provisioned as a
datasource and the container runs, but nothing actually pushes logs into it:
`logback-spring.xml` only declares a console (`JSON`) appender; despite the
`loki.url`/`loki.enabled` Spring properties it reads (and a
`loki-logback-appender` dependency already sitting in `pom.xml`), no Loki
appender is ever declared or attached to the root logger, so `LOKI_URL`/
`LOKI_ENABLED` on the `app` container currently do nothing. Confirmed by
querying `{service="skillars"}` in Grafana Explore against the `Loki`
datasource — zero results — while `docker logs skillars-app-1` shows plenty
of output for the same window. Wiring up the appender (so Explore/LogQL
actually work) is a separate follow-up, not done as part of this guide.