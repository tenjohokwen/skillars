# Local Deployment Guide

**Prefer an automated walkthrough?** Run
[`./setup.sh`](./setup.sh) from this folder — it hand-holds through every step
below (Docker, `.env.local`, the compose override, storage, `/etc/hosts`,
building the image), creating or fixing what it can and telling you exactly
what to do by hand otherwise. Re-run it any time; once everything checks out
it just prints the one command that starts the app
([`start.sh`](./start.sh); [`stop.sh`](./stop.sh) stops it). The rest of this
document is the detailed reference behind what that script automates.

Run Skillars on your own machine using Docker — no domain or TLS required. This
trims the production [`docker-compose.yml`](../../../docker-compose.yml) down to
the services the app needs to run (`app`, `postgres`, `redis`, `storage` — a
local S3-compatible store for file uploads, running SeaweedFS, see
[Step 3](#step-3-the-local-compose-override) — with `loki` and `tempo` coming
along automatically too, see [Notes](#notes)), plus `prometheus` and `grafana`
so you can browse metrics, traces and logs locally instead of relying solely
on `docker logs` (see [Step 6.1](#step-61-browse-grafana-optional) and the
[Logs](#logs) section).

The local stack runs the Spring **`dev`** profile (there is no `local` profile).
The prod/uat-only boot validators (`DeployedSecretsValidator`,
`DeployedMediaScanningValidator`, which refuse dev-default secrets, a non-https
storage endpoint, or switched-off image scanning) are `@Profile({"prod","uat"})`
and never run here, so none of their requirements apply to this guide. Last
verified end to end on 2026-11-02 against master `01770303` (Flyway migrations
through `V223`).

For the full production stack (Traefik/TLS, backups), see
[`first-time-setup.md`](../first-time-setup.md) instead.

---

## Prerequisites

- Docker Engine + the Docker Compose v2 plugin (`docker compose version`). The
  steps were verified with Compose v2.2.3; anything newer works too.
- A local clone of this repository. Compose names containers after the checkout
  directory (`skillars-app-1` below assumes the directory is called `skillars`;
  use `dcl ps` / `dcl logs` if yours differs).
- Free host ports: `9990` (app), `8367` (health/actuator), `3000` (Grafana),
  `9500` (S3 storage). Redis and Postgres are deliberately **not** published
  (see [Step 3](#step-3-the-local-compose-override)).
- `127.0.0.1 storage` in `/etc/hosts` (see [Step 3](#step-3-the-local-compose-override)).
- Nothing else for Mode A: the image build compiles the backend (Java 17) and
  the Quasar frontend (Node 22.16.0) **inside Docker**. You only need a local
  Java 17 + Maven 3.9 and Node 22 (`^20 || ^22 || ^24`, CI uses 22.16.0) for the
  host-run "Mode B" in [`manual-testing.md`](./manual-testing.md#which-run-mode).

---

## Step 1: Build the image

The production `docker-compose.yml` only references `image: ${APP_IMAGE}` (pulled
from GHCR) — there's no `build:` section, so build the image yourself first
(a cold build, Maven plus npm plus `quasar build`, takes several minutes):

```bash
docker build -t skillars:local .
```

`docker-compose.local.yml` — checked into this repo already (see
[Step 3](#step-3-the-local-compose-override), it's not a file you create
yourself) — also carries a `build:` block, so once you're using it you can
rebuild with `docker compose -f docker-compose.yml -f docker-compose.local.yml
--env-file .env.local build app` instead. Note that **`docker compose build app`
without the local override silently does nothing and exits 0** — the base file
has no `build:` key — so if a change you just made appears to have no effect,
check which command you rebuilt with before debugging the code.

---

## Step 2: Create your local env file

```bash
cp .env.example .env.local
```

(`./setup.sh` does this and fills in the values below for you.)

Use `.env.local`, not `.env`. `first-time-setup.md` (Step 5) also does
`cp .env.example .env` at this same repo root, as a staging file it later
`scp`s to the production Node. If you use this clone for both local dev and
prepping a production deploy, a shared `.env` means whichever you run second
overwrites the other's values. `.env.local` is a separate file, loaded
explicitly via `--env-file` below, so the two never collide. It's already
covered by the `.env.*` line in `.gitignore`.

For local use you only need to set the values below — everything else in
`.env.example` (domain, TLS email, Bunny CDN, Grafana SMTP alerting, Hetzner
backup credentials, Stripe keys) is for the production stack and can be left
as-is or removed.

| Variable | Required? | Local value |
|---|---|---|
| `APP_IMAGE` | Yes | `skillars:local` (the tag from Step 1) |
| `POSTGRES_PASSWORD` | Yes | Any value, e.g. `localdev` — no default is provided |
| `GF_SECURITY_ADMIN_PASSWORD` | Yes | This is your real Grafana `admin` login password (see [Step 6.1](#step-61-browse-grafana-optional)) — pick something you'll remember, e.g. `localdev` |
| `MONITORING_DOMAIN` | Yes (placeholder only) | Any value, e.g. `unused` |
| `GF_ALERT_NOTIFY_EMAIL` | Yes (placeholder only) | Any non-empty email-shaped value, e.g. `alerts@localhost` |
| `GF_SLACK_WEBHOOK_URL` | Yes (placeholder only) | Any non-empty URL, e.g. `https://hooks.slack.com/services/unused/unused/unused` |
| `POSTGRES_DB` | No | `.env.example` sets `skillars` (also the compose default) |
| `POSTGRES_USER` | No | `.env.example` sets **`skillars`** (the compose default, if you delete the line, is `postgres`). This is the role every `psql -U` below uses |
| `APP_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` / `_PHONE` | Optional | Creates the first `ADMIN` on app start, see [Step 5](#step-5-create-accounts) |

`GF_SECURITY_ADMIN_PASSWORD` and `MONITORING_DOMAIN` use Compose's hard-required
`${VAR:?error}` syntax on the `grafana` service, so both must resolve to
*something* or the command fails outright with `invalid interpolation format`.
`MONITORING_DOMAIN` only feeds `GF_SERVER_ROOT_URL` and Traefik labels
(`traefik` itself never starts here), so it can stay a throwaway placeholder.

`GF_ALERT_NOTIFY_EMAIL` and `GF_SLACK_WEBHOOK_URL` aren't Compose-required,
but *are* required in practice: `deploy/lgtm/grafana-alerts.yml` provisions an
email + Slack contact point for infra alerts using these two variables, and
Grafana validates each contact point at startup — an email integration with a
blank `addresses` or a Slack integration with a blank `url` both fail
validation and crash the container in a restart loop. Any non-empty
placeholder satisfies the validation; neither is ever actually sent anywhere
locally.

Everything else the app reads resolves to safe placeholder defaults **only**
when the Spring `dev` profile is active — which is what the override file in
the next step turns on. You do not need to set `JWT_SIGNING_KEY` (the JWT
signing key comes from that env var and is no longer stored in the database)
or `REDIS_PASSWORD` (redis now refuses to start without one): 
`docker-compose.local.yml` carries clearly-labelled dev-only values for both,
which `DeployedSecretsValidator` rejects on prod/uat. The other security-round
settings also have local-safe defaults: `ARACHNID_ENABLED=false` (image
scanning off, see [`manual-testing.md`](./manual-testing.md#media-scanning-and-video-privacy-s-18)),
`GEMINI_MODE=developer` (no warning outside prod) and `GEMINI_API_KEY=dev-key`.

**Important:** `.env.local` only feeds `${VAR}` placeholders written directly
in the compose YAML files — it is *not* automatically injected into a
container's process environment. A variable only reaches the app's JVM if
`docker-compose.yml` or `docker-compose.local.yml` explicitly lists it under
that service's `environment:`. If you ever need to pass some *other* Spring
property through to the app, add it to `docker-compose.local.yml`'s
`app.environment` list (Step 3) — adding it only to `.env.local` silently does
nothing.

**Shell variables beat `.env.local`.** Compose resolves `${VAR}` from your shell
environment before `--env-file`. A stray `export GMX_PASSWORD=...`,
`GMAIL_PASSWORD`, `GEMINI_API_KEY` or `ENVIRONMENT` in your shell leaks straight
into the containers; run `docker compose ... config | grep -n <NAME>` when a
value looks wrong.

---

## Step 3: The local compose override

**`docker-compose.local.yml` is already checked into this repo at the repo
root** — it's a real, git-tracked file the team maintains, not a personal
override you create or a `.gitignore`d scratch file (only `.env.local` is
that). If you cloned the repo, you already have it; there's nothing to do in
this step beyond understanding what it contains. (If it's ever missing, restore
it with `git checkout -- docker-compose.local.yml`. `setup.sh` embeds a
fallback copy, kept identical to the file apart from one header comment line,
and uses it only when the file is neither in the working tree nor tracked.)

`docker-compose.yml` doesn't publish the app's ports to your host (only
Traefik does that in production) and doesn't set `SPRING_PROFILES_ACTIVE`.
`docker-compose.local.yml` fixes both. The base file also mounts data
directories from fixed host paths that `provision.sh` creates on the production
Node (`/opt/skillars/data/{postgres,redis,loki,tempo,prometheus,grafana}`).
None of those paths exist on your machine, and Docker Desktop refuses to
bind-mount a host directory it hasn't been granted access to, so the override
replaces all of them with plain named volumes, publishes Grafana's port, and
adds a local S3-compatible store (`storage`, `app`'s newest hard dependency)
plus a one-shot bucket creator. The file's comments explain each line; in
short:

| Part | What and why |
|---|---|
| `app.build` | `docker compose build app` needs a `build:` block, or it exits 0 having built nothing |
| `app.environment` | Turns on the `dev` profile and restates `application-dev.yaml`'s own defaults, because the base file passes each variable with an empty `${VAR:-}` default and an empty value overrides a profile default: `APP_FRONTEND_URL`, `APP_STORAGE_*`, `APP_VIDEO_*_SECRET`, `PLATFORM_PIN_ENCRYPTION_SECRET`, `PLATFORM_REGISTRATION_VERIFICATION_SECRET`, `GMAIL_PASSWORD`/`GMX_PASSWORD`, `APP_SES_FROM_ADDRESS`, `ALLOWED_CLIENTS`, `GEMINI_API_KEY` |
| `app.environment` (startup blockers) | `APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder`, `APP_VIDEO_BUNNY_LIBRARY_ID=123456`, `MANAGEMENT_HEALTH_MAIL_ENABLED=false` (the last is now a harmless no-op) |
| `app.environment` (security round) | `JWT_SIGNING_KEY` (the published dev key) and `SPRING_DATA_REDIS_PASSWORD` plus `redis`'s `REDIS_PASSWORD` (`dev-redis-password-NOT-for-prod`). Redis sits alone with the app on the `internal: true` network `skillars-data`; it is never published to the host |
| `app.volumes`, `ports` | `./target/mails` for the dev email dump; publishes `9990` (app) and `8367` (management) |
| `postgres`, `redis`, `loki`, `tempo`, `prometheus`, `grafana` | Named volumes instead of the `/opt/skillars/data/*` bind mounts; publishes Grafana on `3000` |
| `storage`, `storage-init` | SeaweedFS S3 API on `9500` (credentials `minioadmin`/`minioadmin123` from `deploy/seaweedfs/s3-identity.json`), and a one-shot job that creates the `skillars-dev` bucket and its CORS policy |
| `volumes:` | The named volumes the above use |

Compose merges `volumes:` entries by matching container target path, so each
of these overrides only replaces the matching production bind mount — the
other mounts on `loki`/`tempo`/`prometheus`/`grafana` (their read-only
`./deploy/lgtm/*.yml` config files, relative paths) are untouched. `traefik`
has the same kind of fixed host-path dependency, but since it never starts in
this trimmed stack it doesn't need an override.

**Why `storage` runs SeaweedFS, not MinIO.** `quay.io/minio/minio` started
returning 401 on anonymous pulls on 2026-09-24, so `chrislusf/seaweedfs:3.97`
replaced it here, in the UAT compose files and in the Testcontainers setup.
The service is called `storage` (not `minio`); `amazon/aws-cli` stands in for
`mc` in `storage-init`. Credentials come from the mounted
`deploy/seaweedfs/s3-identity.json` (already in the repo), not env vars, because
SeaweedFS's own env-var credential path has a live upstream reliability issue
([seaweedfs/seaweedfs#7311](https://github.com/seaweedfs/seaweedfs/issues/7311)).

**Browser-side uploads need one `/etc/hosts` entry.** The app container reaches
this service over the compose network as `storage:9500`, and the presigned
upload URLs it hands back to the browser use that same host — but your
browser runs on the host machine, not inside the compose network, so it can't
resolve `storage` on its own. Add this once:

```
127.0.0.1 storage
```

That, plus the published `9500:9500` port, makes `http://storage:9500/...`
resolve to this container from both the app and your browser. (An old
`127.0.0.1 minio` line from before the rename is unused and can be removed.)

The `app.environment` entries that restate `application-dev.yaml` defaults are
required rather than tidy. Found by running it: an empty
`APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` aborts startup in
`BunnyVideoProviderAdapter` ("webhookSigningSecret must not be blank"), and an
empty `APP_STORAGE_S3_ACCESS_KEY` silently drops storage back onto the AWS
default credential chain, breaking every upload. Keep that block in step with
`application-dev.yaml`.

Three entries fix real startup crash loops (not hypothetical ones) and are
the ones to remember if you ever run the app outside this compose file:

- **`APP_VIDEO_BUNNY_LIBRARY_ID=123456`** — the `videoProviderAdapter` bean
  eagerly validates `app.video.bunny.library-id` as numeric. It defaults to an
  empty string and `application-dev.yaml` does not set it, so the *entire app
  context* fails to start over an unused video setting
  (`app.video.bunny.library-id must be numeric, got: `). Verified again on
  2026-11-02 when running the app on the host without it.
- **`APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder`** —
  `PaymentConfig.configureStripe()` throws `AppSetupException` when the key is
  blank, and blank is what it resolves to locally. Any test-shaped placeholder
  works: the guard in the same method only rejects `^(sk|rk)_live_` keys outside
  the `prod` profile, so a local environment can never charge real money. Note
  the placeholder is non-blank, so Stripe-backed features that only check "is a
  key configured" consider Stripe set up (see the age-verification scenario in
  `manual-testing.md`).
- **`MANAGEMENT_HEALTH_MAIL_ENABLED=false`** — kept as a belt-and-braces no-op.
  The dev profile runs `app.email.transport: log`, which has no health
  indicator and sends nothing.

It's deliberately named `docker-compose.local.yml` and passed explicitly with
`-f`, rather than `docker-compose.override.yml`, which Compose auto-loads. The
deploy scripts in `first-time-setup.md` run `docker compose up -d` with no
`-f` flags on the production Node — an auto-loaded override sitting in the
repo would silently leak the `dev` profile and exposed ports into that deploy.

---

## Step 4: Start the stack

Either run `docs/deployment/local/start.sh` (it creates `target/mails`, runs the
command below and prints the URLs), or do it by hand. Every command from here
on needs the same three flags — `-f docker-compose.yml -f
docker-compose.local.yml --env-file .env.local` — so it's worth an alias:

```bash
alias dcl='docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local'
```

This is the single command that brings everything up:

```bash
dcl up -d app postgres redis storage-init grafana
```

Compose will also start `loki`, `tempo`, and `storage` automatically — `app`
declares a hard `depends_on` on all three (`loki`/`tempo` just *started*,
`storage` actually *healthy*), so they come up even though only `storage-init`
was named explicitly. Naming `grafana` similarly pulls in `prometheus`.
`traefik` and `node_exporter` are not dependencies of anything named here and
stay stopped.

`storage-init` has to be named explicitly — unlike `storage` itself, nothing
declares a hard dependency on it, so leaving it off the command means the
`skillars-dev` bucket is never created and every upload fails with a
"no such bucket" error the first time you try one.

Give it a minute or two, then confirm everything is healthy:

```bash
dcl ps
```

`app` runs its database migrations (Flyway, `V138` through `V223`) on this first
startup, which is why the next step can't happen until the container is up.

---

## Step 5: Create accounts

There is nothing to seed to get the app running: the JWT signing key comes from
`JWT_SIGNING_KEY` (with a dev-only default in `docker-compose.local.yml` and
`application-dev.yaml`) and every role registration needs is seeded by Flyway.
**Do not run** `src/test/resources/sql/secData.sql` or `initTestData.sql`: they
only write a now-unused legacy `main.sec` row, and `initTestData.sql` still
assumes a `local` Spring profile that no longer exists.

Register coach / parent / adult-player accounts through the UI (or the API, see
[`manual-testing.md`](./manual-testing.md#creating-the-accounts)), then use
[`seed-accounts.sql`](./seed-accounts.sql) to give them paid state without Stripe.
The ones that cannot be registered:

- **Admin.** There is no admin registration. Put `APP_BOOTSTRAP_ADMIN_EMAIL`,
  `APP_BOOTSTRAP_ADMIN_PASSWORD` and `APP_BOOTSTRAP_ADMIN_PHONE` in `.env.local`
  (the base compose file already passes them to the app) and `dcl up -d app`.
  `AdminBootstrapRunner` creates one `ROLE_ADMIN` account, already activated;
  it never elevates an existing user, so use an address no one registered with.
  Example values: `admin@example.com`, a throwaway password such as
  `Adm1n-Local-Only!`, `+491701234599`.
- **Limited admin (`ROLE_LTD_ADMIN`).** Bootstrap an account as above, then
  demote it with `seed-accounts.sql -v ltd_admin_email=<that address>` (or the
  runbook SQL, [Create a limited administrator](../runbook.md#create-a-limited-administrator-role_ltd_admin)).
  To have both tiers, bootstrap the limited one first, change the
  `APP_BOOTSTRAP_ADMIN_*` values to a second address and `dcl up -d app` again.
  Verified: the second boot creates the full admin and leaves the demoted one alone.

For the full manual-testing path — getting a paid coach and a paid
parent/player through every workflow without Stripe — see
[`manual-testing.md`](./manual-testing.md).

---

## Step 6: Verify

```bash
curl -s http://localhost:8367/manage/health
# {"status":"UP","groups":["smoke"]}
```

Details are hidden from anonymous callers (they need an admin session), so you
only ever see `UP` or `DOWN` here. Other URLs that should answer: the app and
UI at `http://localhost:9990` (200), Grafana at `http://localhost:3000/login`
(200), and the S3 port `http://storage:9500/` (403 is correct: it wants
signed requests).

`management.server.port` (8367) runs as a separate embedded server from the
main app port (9990), so health/actuator endpoints are only reachable on 8367
— not under `/manage` or `/actuator` on 9990. Application API traffic (e.g.
login) goes to `http://localhost:9990`. The Docker healthcheck polls the
narrower `/manage/health/smoke` group; the default group also includes Redis,
so it goes `DOWN` (503) while the container still reports healthy if the
app cannot authenticate to Redis.

---

## Step 6.1: Browse Grafana (optional)

Open `http://localhost:3000` and log in with user `admin` and whatever you set
`GF_SECURITY_ADMIN_PASSWORD` to in `.env.local`. The `Loki`, `Tempo`, and
`Prometheus` datasources are already provisioned (via
`deploy/lgtm/grafana-datasources.yml`), along with the dashboards under
`deploy/lgtm/dashboards/` — traces (Tempo), metrics (Prometheus) and logs
(Loki) are all queryable through the UI. Try `{app="skillars"}` in Explore
against the `Loki` datasource; see the [Logs](#logs) section below. Grafana is
pinned to 11.6.16.

`GF_SERVER_ROOT_URL` is still set to `https://${MONITORING_DOMAIN}` (whatever
placeholder you gave it in Step 2) since that variable is shared with the
production stack. This only affects things like OAuth callback URLs, which
this guide doesn't use — plain username/password login at
`http://localhost:3000` works regardless.

The base compose file defaults `ENVIRONMENT` to `prod`, so every local log line
and metric is labelled `environment="prod"`. That is only a label (nothing in the
app branches on it); dashboards filtered on `environment="prod"` will show your
local data.

---

## Tearing down

```bash
docs/deployment/local/stop.sh      # same as: dcl down
```

Add `-v` (`dcl down -v`) to also delete every named volume (Postgres, Redis,
storage, Loki, Tempo, Prometheus, Grafana) and start completely fresh next time.
You will then need to register your accounts again (and re-run
`seed-accounts.sql`). A bootstrap admin from `.env.local` is recreated on the
next boot.

To start over without a full wipe, prefer `down -v` to hand-written `DELETE`s:
once an account has been seeded, rows in the `payment` schema reference it and a
plain `DELETE FROM main."user"` fails on those foreign keys. For a single
account use the admin GDPR erasure flow instead.

---

## Troubleshooting

**`Bind for 0.0.0.0:9990 failed: port is already allocated` (or 8367 / 3000 /
9500)** — something else on the host owns the port; stop it or find it with
`lsof -iTCP:9990 -sTCP:LISTEN`. The same applies to `5432` if you publish
Postgres for Mode B (the Mode B override defaults to `5433` for that reason).

**`psql: FATAL: role "postgres" does not exist`** — your `.env.local` came from
`.env.example`, which sets `POSTGRES_USER=skillars`. Use `-U skillars` (or
`-U "$POSTGRES_USER"` inside the container shell).

**Wrong or changed Redis password** — the app still boots (login keeps working)
but `curl http://localhost:8367/manage/health` flips to `503 DOWN` with a
`RedisConnectionFailureException: Unable to connect to Redis` warning in the
app log, while `/manage/health/smoke` and the Docker healthcheck stay `UP`.
Keep `SPRING_DATA_REDIS_PASSWORD` (app) and `REDIS_PASSWORD` (redis) identical;
both are set to `dev-redis-password-NOT-for-prod` in `docker-compose.local.yml`.

**`REDIS_PASSWORD is not set; refusing to start redis`** (redis container exits)
— you started the base file without `docker-compose.local.yml`. Always pass all
three flags (use `dcl` or `start.sh`).

**Grafana restart-loops** — `GF_ALERT_NOTIFY_EMAIL` or `GF_SLACK_WEBHOOK_URL` is
blank in `.env.local` (see Step 2).

**Shell-exported secrets leaking in** (`GMX_PASSWORD`, `GEMINI_API_KEY`,
`ENVIRONMENT`, ...) — see the note at the end of Step 2.

**`no space left on device` in container logs (Postgres/Loki/Tempo/Prometheus/Redis/storage
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
*anonymous* volumes — it never touches this project's named volumes
(`skillars_skillars-local-postgres`, etc.). After freeing space, `dcl down`
then `dcl up -d app postgres redis storage-init grafana` again — everything
recovers cleanly from a disk-full crash on restart, no need to wipe volumes
with `-v` unless something still looks broken afterward.

---

## Notes

- This guide intentionally does not touch the checked-in `docker-compose.yml`
  — it stays exactly as production uses it. All local-only behavior (ports,
  `dev` profile) lives in `docker-compose.local.yml`, which you pass in
  explicitly and never gets picked up by the production deploy.
- You'll see `WARN: The "DOMAIN" variable is not set. Defaulting to a blank
  string.` — harmless (`DOMAIN` only feeds the unused `traefik` labels).
- `loki`, `tempo`, and `storage` start regardless of the service list you pass
  to `up`, because `app`'s `depends_on` requires them (`loki`/`tempo` just
  *started*, `storage` actually *healthy*); `prometheus` similarly starts because
  `grafana` depends on it. This is harmless locally, but explains why you'll see
  extra containers and why `app` won't report `running` until SeaweedFS's
  healthcheck passes (up to ~60s on a cold start).
- At boot the app logs one `ERROR ... platform.admin_alert_email config is
  blank` line from `VideoModerationEmailListener`. It only matters if image
  scanning is switched on; ignore it locally.
- To run the app on the host instead of in Docker (debugger, hot reload), see
  "Mode B" in [`manual-testing.md`](./manual-testing.md#which-run-mode). It needs
  a small extra compose file, because Postgres and Redis are not published.

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

**Grep for a specific thing** (e.g. an outbound email, a specific endpoint):

```bash
docker logs skillars-app-1 --since 30m 2>&1 | grep -i "Email sent\|player/register"
```

(`"Email sent"` is logged by `MailService` after every successful send, regardless of which transport is
active — the `log` transport's own `LoggingEmailSender` additionally logs `"Sending email (log
transport)"`, which is transport-specific — and it is exactly what `dev` runs since ses-1.5, so that line is the
way to find the file name of the email you just triggered.)

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

Logs reach Loki because `docker-compose.yml` sets `LOKI_ENABLED=true`; there is
nothing to switch on locally. Open Grafana Explore at `http://localhost:3000`
(Step 6.1) with the `Loki` datasource and query `{app="skillars"}`. Streams carry
`app`, `environment` (always `prod` locally, see Step 6.1) and `level` labels,
and each line is the full JSON event including `traceId`/`spanId`/`requestId`,
so you can pivot from a Tempo trace to its logs and back. To query Loki directly
(it has no published host port), run curl inside the observability network. The
network is named after the checkout directory (`docker network ls`):

```bash
docker run --rm --network skillars_skillars-observability curlimages/curl -s \
  -G 'http://loki:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={app="skillars"}' --data-urlencode 'limit=5'
```

Expect a delay before new lines appear: loki4j batches with a 60-second default
timeout, so a request you just made can sit in the appender's buffer for up to a
minute. `docker logs` shows it immediately. Querying `{service="skillars"}`
returns nothing: the label is `app`.

### Tomcat access logs

These are in Loki too, emitted through SLF4J rather than written to a file:

| Logger | Server |
|---|---|
| `skillars.access` | main app, port 9990 |
| `skillars.access.management` | actuator, port 8367 |

The management logger is a child of the main one, so setting `skillars.access` to
`OFF` in `src/main/resources/config/logback-spring.xml` silences both, while
`skillars.access.management` can be silenced on its own (the actuator port
carries the container healthcheck every 30 seconds plus Prometheus scrapes, the
bulk of access-log volume in a quiet environment). `APP_ACCESS_LOG_ENABLED=false`
turns both off. Tokens in logged URLs are redacted.

## Manual migration

Spring Boot runs the Flyway migrations itself when the app starts, but they can be
applied by hand (the history starts at `V138`, the squashed baseline; nothing
below it exists):

```shell
dcl exec -T postgres psql -U skillars -d skillars -v ON_ERROR_STOP=1 \
  < src/main/resources/db/migration/V223__coach_safeguarding_check.sql
```

(Substitute the migration you need: `ls src/main/resources/db/migration | sort -V | tail -1`
is the latest. Applying files by hand bypasses Flyway's bookkeeping, so only do
it on a throwaway database. The seed script was verified against a database built
by applying `V138` ... `V223` in order with `psql`.)

## Data persistence

* Every stateful service keeps its data in a named volume (`skillars-local-postgres`,
  `-redis`, `-storage`, `-loki`, `-tempo`, `-prometheus`, `-grafana`, shown as
  `skillars_skillars-local-*` in `docker volume ls`), independent of the container
  lifecycle.
* Plain `docker compose down` / `up`, container recreates and image rebuilds are all
  safe: Postgres (which stores the `FileStorageObject` metadata row) and the storage
  bucket stay in sync.
* What wipes them: `docker compose down -v`, `docker volume rm skillars_skillars-local-storage`
  (or any other of the volumes) directly, or pruning them manually. Wiping only
  `storage` leaves Postgres rows pointing at objects that no longer exist.

For pointing a deployed environment at real object storage, see
[`uat-deployment.md`](../uat-deployment.md) and [`secrets-reference.md`](../secrets-reference.md):
prod/uat require an `https://` `APP_STORAGE_ENDPOINT_URL`.
