# Provisioning and Go-Live Checklist (PROD and UAT)

> **Keep this current.** A PR that adds a config property, an environment variable, a Stripe or
> Bunny webhook event, a third-party integration, a scheduled job or a migration that can fail on
> existing data must update this checklist in the same PR and add a line to the
> [Changelog](#changelog) at the bottom.

This is the single list an operator works through from top to bottom when provisioning a fresh
environment, and again before each release. It does not repeat the procedures. Each item links to
the guide that has the detail and cites the source file it was taken from, so you can re-check it
against the code.

- **PROD**: `docker-compose.yml` with `SPRING_PROFILES_ACTIVE=prod`, env file `/opt/skillars/.env`,
  deployed by `.github/workflows/deploy.yml`.
- **UAT**: `docker-compose.yml` + `docker-compose.uat.yml` (Hetzner/netcup box, Traefik, SeaweedFS),
  or `docker-compose.uat-hostwinds.yml` (stripped-down box behind nginx). Profile `uat`, env file
  `.env.uat`.
- Items marked **(confirm)** cannot be verified from the repository. Someone has to check them in
  the provider's dashboard or decide them.
- Section 0 lists blocking issues found while compiling this checklist. Clear them before the first
  production go-live.

Verified against master `fea08bae` (2026-10-29).

---

## Contents

0. [Open issues found while compiling this checklist](#0-open-issues-found-while-compiling-this-checklist)
1. [Infrastructure](#1-infrastructure)
2. [Secrets and environment variables](#2-secrets-and-environment-variables)
3. [Database](#3-database)
4. [Stripe](#4-stripe)
5. [Email, SMS, video, object storage and other third parties](#5-email-sms-video-object-storage-and-other-third-parties)
6. [Scheduled jobs and ShedLock](#6-scheduled-jobs-and-shedlock)
7. [Application config and policy keys](#7-application-config-and-policy-keys)
8. [Accounts and seed data](#8-accounts-and-seed-data)
9. [Release and post-deploy smoke tests](#9-release-and-post-deploy-smoke-tests)
10. [Changelog](#changelog)

---

## 0. Open issues found while compiling this checklist

These items cannot be closed by configuration alone, or they contradict an existing doc. Each
needs an owner decision or a code or doc fix before PROD serves real users.

- [x] ~~**Stripe needs two webhook endpoints, but the code verifies only one signing secret.**~~
  Resolved by `fix/payment-stripe-connect-webhook-endpoint`: the Connect endpoint now has its own
  path (`/api/payment/webhooks/stripe/connect`) and secret (`APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET`),
  and the onboarding status is also read from Stripe (`Account.retrieve`) when the coach returns
  from OAuth and while it is not yet `COMPLETE`. Provision both endpoints as described in
  [section 4.3](#43-webhook-endpoints-and-exact-event-lists).
- [ ] **Never seed PROD's JWT signing key from `src/test/resources/sql/secData.sql`.** The passthrough
  gap is fixed by `fix/prod-readiness-gaps`: `APP_BOOTSTRAP_JWT_SECRET_ENABLED` (default `false`) is now
  in `docker-compose.yml`, so the `.env` procedure in `secrets-reference.md` → "JWT signing key" works.
  Use it for one boot of a fresh PROD database, then remove it. The rest of this item still applies.
  `uat-hostwinds-deployment.md` Step 8 and `local/deployment.md` Step 5 seed `main.sec` from that
  fixture. Its encrypted key is committed to the repository, so anyone with repo access could forge
  tokens for an environment seeded from it. The supported generator, `JwtSecretBootstrapRunner`
  (`app.bootstrap.jwt-secret.enabled`), is meant for local use only. It does accept the private
  compose-network address, so a PROD boot with the flag set creates a unique `(v1, jot)` row. Verify
  it with the query in [section 8](#8-accounts-and-seed-data).
- [x] ~~**Production object storage has no service in `docker-compose.yml`.**~~ Resolved by
  `infra/prod-seaweedfs-storage`: `docker-compose.yml` now runs single-node SeaweedFS (`storage` +
  one-shot `storage-init`) on `STORAGE_DOMAIN`, with data on the Hetzner Volume. See
  [section 5 → Object storage](#object-storage-filestorage-module-over-infrastructureblobstore).
  Moving to a managed provider later: `runbook.md` → "Moving object storage to a managed provider".
- [x] ~~**Deploys take no pre-migration backup.**~~ Resolved by `fix/prod-readiness-gaps`: `deploy.yml`
  now runs `deploy/backup/pg-backup.sh` on the node before pulling the image (step "Pre-deploy database
  dump"). A failed dump stops the deploy, and the dump's object key is printed in the run summary as the
  rollback pointer. The auto-revert still swaps the image only; after a migration, restore from that
  dump (`rollback.md` → "Rollback After Migrations Have Run"). The `skip_backup` input skips it (emergency
  only). The dump needs the `HOS_*` backup variables in `/opt/skillars/.env` and an `SSH_USER` that can
  read that file and run `docker`.
- [ ] **No SMS gateway exists.** `SmsRegistrationStrategy` sends nothing (it is a TODO stub), and
  `security.registration.phone-otp-required` is seeded `false` (`V139`). Keep it `false` on PROD
  until an SMS provider is integrated, or phone verification blocks every registration **(confirm
  product decision)**. Since `fix/prod-readiness-gaps`, `PUT /api/config` refuses to set it to `true`
  (400) and a stored `true` logs an ERROR at boot (`config.value.misconfigured`, reason
  `no_sms_gateway`). Flip `ConfigService.SMS_GATEWAY_AVAILABLE` when a gateway ships.
- [ ] **Recent migrations assume "prod is not live".** V186, V192, V193, V195, V198, V199, V200, V205, V206, V208, V209, V210, V212, V213, V214 and V215 use
  validating `CHECK` swaps, non-concurrent unique indexes or a same-release column + first write,
  justified in their headers by "no production deployment exists yet". Once PROD holds real data,
  the next migration must follow the full online-safety rules in `migration-conventions.md`, without that exemption.

---

## 1. Infrastructure

Detail: [`first-time-setup.md`](first-time-setup.md) (PROD), [`uat-deployment.md`](uat-deployment.md)
and [`uat-hostwinds-deployment.md`](uat-hostwinds-deployment.md) (UAT).

### Hosts and Docker

- [ ] Server created with enough resources (`first-time-setup.md` → "Resource Requirements"). PROD
  container limits sum to about 5.4 GB (`deploy.resources.limits` in `docker-compose.yml`:
  app 2g, postgres 1536m, prometheus 768m, loki 768m, tempo 512m, grafana 512m, redis 256m,
  traefik 256m, node_exporter 64m).
  - UAT (Hostwinds): app heap capped at `-Xmx256m` with SerialGC, plus swap
    (`docker-compose.uat-hostwinds.yml`, `uat-hostwinds-deployment.md` Steps 2 and "Resource budget").
- [ ] Persistent data volume mounted at `/opt/skillars/data`. Every stateful service bind-mounts
  under it (`docker-compose.yml`: postgres, redis, traefik `acme.json`, prometheus, loki, tempo,
  grafana). Check: `first-time-setup.md` Step 7 ("Expected output: mounted").
- [ ] `deploy/provision.sh` run to completion. It installs packages and Docker Compose, configures
  ufw, creates directories and a deploy user, and enforces mode 600 on `.env`. Re-run it after
  placing `.env`.
- [ ] Repository checkout at `/opt/skillars/app`, env file at `/opt/skillars/.env`, a sibling of the
  checkout (`runbook.md` → "Repository cleanup safety"; `deploy.yml` uses both paths).
- [ ] Exactly **one** app container per database (`deploy-guide.md` → "Single instance only"). SSE
  is in-memory. Watch `skillars_app_instances_live` and the `MULTIPLE_INSTANCES` WARN
  (`InstanceHeartbeatMonitor`, `main.app_instance_heartbeat`, V187).
- [ ] Container time zone is UTC: `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` (`Dockerfile`). The
  Hostwinds override sets its own `JAVA_TOOL_OPTIONS` and must keep `-Duser.timezone=UTC`. Every
  cron below without an explicit zone relies on it.

### Network and firewall

- [ ] Provider firewall applied: `deploy/firewall/apply-firewall.sh` with `firewall-rules.json`
  (`first-time-setup.md` Step 4, `deploy/firewall/README.md`). Inbound 22/80/443 only, outbound
  open (SMTP/SES, Let's Encrypt, Stripe, Bunny, Gemini).
  - UAT (Hostwinds): also open the storage port 9500 (`uat-hostwinds-deployment.md` Step 6).
- [ ] Compose networks: `skillars-internal` (has egress) and `skillars-observability`
  (`internal: true`, no egress) (`docker-compose.yml` → `networks:`; `first-time-setup.md`
  troubleshooting section).
- [ ] Management port 8367 (actuator, Prometheus) is **not** published. Only `/actuator/health` is
  routed, rewritten to `/manage/health/smoke` (Traefik labels `app-health` / `health-rewrite` in
  `docker-compose.yml`).

### DNS and TLS

- [ ] A records resolve to the node **before** the first `up` (`first-time-setup.md` Step 2):

  | Record | PROD | UAT |
  |---|---|---|
  | `DOMAIN` | yes | yes |
  | `MONITORING_DOMAIN` (Grafana) | yes (`docker-compose.yml` fails interpolation without it) | yes (full UAT); not used on Hostwinds |
  | `STORAGE_DOMAIN` (S3 endpoint the browser uses) | yes (`docker-compose.yml` → `storage` router; uploads fail without its certificate) | yes (`docker-compose.uat.yml` → `storage` router); not used on Hostwinds |

- [ ] `LETSENCRYPT_EMAIL` set. `/opt/skillars/data/traefik/acme.json` exists with mode 600
  (`docker-compose.yml` → `traefik`, [`traefik-tls.md`](traefik-tls.md)). Note the Let's Encrypt
  rate limits before you recreate `acme.json` (`traefik-tls.md` → Scenario C).
- [ ] Certificates issued for every host. Check expiry with `traefik-tls.md` → "Checking the
  Current Certificate Status".
  - UAT (Hostwinds): nginx in front, plain HTTP on the host name (`uat-hostwinds-deployment.md`).
    Stripe requires HTTPS webhook URLs in live mode. Test mode may accept HTTP **(confirm)**.

### Backups (configured and a restore tested)

Detail: [`backup-restore.md`](backup-restore.md), `deploy/backup/`.

- [ ] Off-server backup target chosen: `BACKUP_STORAGE_TYPE=hetzner` (`HOS_*`) or
  `s3-compatible` (`S3_BACKUP_*`). PROD only (`secrets-reference.md`).
- [ ] Crons installed: `bash /opt/skillars/app/deploy/backup/install-crons.sh`, once per node and
  idempotent. It installs `pg-backup.sh` every 6 h (`0 */6 * * *`), `volume-backup.sh` daily at
  02:00 and `prune-backups.sh` at 03:30 (`deploy/backup/install-crons.sh`).
- [ ] Retention set or defaults accepted: `BACKUP_RETENTION_DAYS=14`, `BACKUP_RETENTION_MIN_KEEP=8`,
  `VOLUME_BACKUP_RETENTION_DAYS=14`, `VOLUME_BACKUP_RETENTION_MIN_KEEP=4`.
- [ ] First manual `pg-backup.sh` run ends with `Upload verified`.
- [ ] **A restore has been tested**: `restore-from-dump.sh` (`backup-restore.md` Section A) into a
  scratch database, then Section C integrity checks. Record it in `deploy/backup/drill-log.md`. Repeat
  quarterly (`backup-restore.md` → "Quarterly Restore Drill Reminder").
- [ ] Object-storage backup: PROD's SeaweedFS data (`/opt/skillars/data/storage`) is in the daily
  `volume-backup.sh` archive (all of `data/` except `postgres/`). That is a full archive each day, so
  its size and Object Storage cost grow with the media library. Separately,
  `app.storage.replication.enabled` is `false` by default (`application.yaml`);
  `APP_STORAGE_BACKUP_*` configure a secondary bucket. Decide whether PROD also replicates uploaded
  files **(confirm)**.

### Uptime monitoring

- [ ] External monitor on `https://<DOMAIN>/actuator/health` with keyword `UP`, email and Slack
  contacts ([`uptime-monitor.md`](uptime-monitor.md); `first-time-setup.md` Step 8, marked
  "Required"). PROD required. UAT optional.

### Logs and metrics stack

Detail: [`monitoring.md`](monitoring.md), `deploy/lgtm/`.

- [ ] PROD runs prometheus, loki, tempo, grafana and node_exporter (`docker-compose.yml`). The app
  ships logs with `LOKI_ENABLED=true` and traces to `http://tempo:4318/v1/traces`. Both are hardcoded
  on the `app` service, not set from `.env`.
  - UAT (Hostwinds): `LOKI_ENABLED=false`, no LGTM containers. Logs come from `docker logs` only
    (`docker-compose.uat-hostwinds.yml`, `uat-hostwinds-deployment.md` → "Logging").
- [ ] `GF_SECURITY_ADMIN_PASSWORD` and `MONITORING_DOMAIN` set. Compose fails with `:?` if either
  is missing.
- [ ] At least one Grafana alert channel: `GF_ALERT_NOTIFY_EMAIL` (requires `GF_SMTP_ENABLED=true`
  and `GF_SMTP_*`) or `GF_SLACK_WEBHOOK_URL`. `provision.sh` exits 1 if both are blank
  (`secrets-reference.md`).
- [ ] Grafana loads at `https://<MONITORING_DOMAIN>`, and the Prometheus, Loki and Tempo datasources
  return data (`uat-deployment.md` Step 7).
- [ ] Trace sampling: `management.tracing.sampling.probability: 1.0` (`application.yaml`).
  Decide whether PROD should sample less **(confirm)**.

---

## 2. Secrets and environment variables

Detail: [`secrets-reference.md`](secrets-reference.md) is authoritative for format and generation.
Read its callout "How `.env` reaches the application" first. A variable reaches the JVM only if it
is listed under the `app` service's `environment:` in compose. An **empty** value also overrides a
Spring `${X:default}` default.

### 2.1 Required, no safe default (boot fails or the feature is dead)

| Variable | PROD | UAT | Fails how | Source |
|---|---|---|---|---|
| `APP_IMAGE` | set by `deploy.yml` | set manually | compose cannot pull | `docker-compose.yml` |
| `DOMAIN`, `LETSENCRYPT_EMAIL` | required | required | no routing / no TLS | `docker-compose.yml` |
| `POSTGRES_PASSWORD` | required | required | DB will not init | `docker-compose.yml` |
| `APP_PAYMENT_STRIPE_API_KEY` | `sk_live_`/`rk_live_` | `sk_test_` only | boot aborts if blank; a live key aborts outside `prod` | `PaymentConfig` |
| `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` | live `whsec_` of the **account** endpoint | test `whsec_` of the account endpoint | every subscription/invoice/dispute/refund webhook gets 400 | `StripeWebhookService.processWebhook` |
| `APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET` | live `whsec_` of the **Connect** endpoint | test `whsec_` of the Connect endpoint | every `account.updated` gets 400 (logged `STRIPE_CONNECT_WEBHOOK_SECRET_MISSING` if blank); coach status then moves only through the slower `Account.retrieve` fallback | `StripeWebhookService.processConnectWebhook` |
| `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` | `pk_live_` | `pk_test_` | card entry, SetupIntent and pack purchase broken | `SessionPackPaymentResource` `/api/payment/stripe/config` |
| `APP_PAYMENT_STRIPE_OAUTH_CLIENT_ID` | live `ca_` | test `ca_` | coach Connect onboarding broken | `StripeOnboardingService` |
| `APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL` | `https://<DOMAIN>/api/payment/coaches/me/stripe/callback` | same with UAT host (**required**: the UAT compose files use `:?`) | Stripe rejects the relative compose default, which would also override `application-uat.yaml`'s absolute one | `docker-compose.yml`, `application-uat.yaml` |
| `APP_SES_FROM_ADDRESS` | required (`transport: ses`) | n/a (`transport: smtp`) | `SesPropertiesValidator` aborts boot | `application-prod.yaml` |
| `GMX_PASSWORD`, `GMAIL_PASSWORD` | not checked (SES) | **both required** | the UAT compose files require both (`:?`), so `docker compose up` stops with a message instead of a boot loop in `ProviderConfigsValidator` | `application-uat.yaml`, `docker-compose.uat.yml`, `docker-compose.uat-hostwinds.yml` |
| `PLATFORM_REGISTRATION_VERIFICATION_SECRET` | required; the dev default is rejected | required; the dev default is rejected | `RegistrationVerificationTokenService` aborts boot; `DeployedSecretsValidator` rejects the dev default | `application.yaml` |
| `PLATFORM_PIN_ENCRYPTION_SECRET` | required; rotating it invalidates stored PINs; the dev default `S3CR3TW0RD` is rejected | required; same | fail-fast: `Cryptopher` on blank, `DeployedSecretsValidator` on blank or the dev default | `application.yaml` |
| `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | required (base64, ≥ 32 bytes); the dev/test value is rejected | required; same | fail-fast at boot in `prod`/`uat` (`DeployedSecretsValidator`: blank, not Base64, < 32 bytes, or the dev default); previously only failed on the first playback token | `application.yaml` |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` | required; the dev default is rejected | required; same | fail-fast (`BunnyVideoProviderAdapter` on blank, `DeployedSecretsValidator` on the dev default) | `application.yaml` |
| `BUNNY_API_KEY`, `BUNNY_LIBRARY_ID`, `BUNNY_CDN_HOSTNAME` | required for video | test library | compose defaults the library ID to the placeholder `123456` | `docker-compose.yml` |
| `APP_STORAGE_BUCKET` | set it (compose default `skillars-prod`) | set it | the UAT compose files pass it without a default | `docker-compose.yml` |
| `APP_STORAGE_ENDPOINT_URL` | not from `.env`: compose sets `https://${STORAGE_DOMAIN}` | same (override) | n/a | `docker-compose.yml`, `docker-compose.uat.yml` |
| `APP_STORAGE_S3_ACCESS_KEY`/`SECRET_KEY`, `APP_STORAGE_S3_PATH_STYLE_ACCESS` | not from `.env`: compose sets `STORAGE_ROOT_USER`/`PASSWORD` and `true` | same | uploads fail | `docker-compose.yml`, `application.yaml` |
| `STORAGE_ROOT_USER`, `STORAGE_ROOT_PASSWORD`, `STORAGE_DOMAIN` | required | required | `storage` refuses to start on blank credentials; no `STORAGE_DOMAIN` means no certificate and broken presigned URLs | `docker-compose.yml` |
| `APP_FRONTEND_URL` | defaults to `https://skillars.com` | **required**: the UAT compose files use `:?` (the base default would override `application-uat.yaml`) | email links point at PROD | `docker-compose.yml`, `docker-compose.uat*.yml` |
| `ENVIRONMENT` | `prod` (default) | `uat` (the UAT compose files now default it) | logs and Loki labelled `prod` | `docker-compose.yml`, `docker-compose.uat*.yml` |
| `ALLOWED_CLIENTS` | blank (no machine client) unless a real integration needs one | set it if testers call the API with `X-Client-Id` (`uat-deployment.md` 8.3) | a request with an unlisted `X-Client-Id` gets 403 | `application.yaml` `allowed.clients`, `docker-compose.yml` |
| `GF_SECURITY_ADMIN_PASSWORD`, `MONITORING_DOMAIN` | required | required where Grafana runs | compose `:?` error | `docker-compose.yml` |
| `GF_ALERT_NOTIFY_EMAIL` or `GF_SLACK_WEBHOOK_URL` | at least one | at least one where Grafana runs | `provision.sh` exits 1 | `deploy/provision.sh` |
| Backup `HOS_*` / `S3_BACKUP_*` | required | n/a | no off-site backups | `deploy/backup/*.sh` |
| `APP_BOOTSTRAP_ADMIN_EMAIL`/`PASSWORD`/`PHONE` | first deploy only, then remove the password | same | setting email and password without the phone aborts boot | `AdminBootstrapRunner` |

### 2.2 GitHub Actions secrets (PROD deploy workflow)

- [ ] `SSH_DEPLOY_KEY`, `SSH_HOST`, `SSH_USER`, `SSH_KNOWN_HOST` (all `ssh-keyscan` lines),
  `GHCR_PAT` (`read:packages`), `SLACK_WEBHOOK_URL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`,
  `SMTP_PASSWORD`, `NOTIFY_EMAIL` (`.github/workflows/deploy.yml`, `secrets-reference.md` → "GitHub
  Actions Secrets").

### 2.3 Placeholders in `application*.yaml` that are missing from `secrets-reference.md`

Found by grepping every `${...}` in `src/main/resources/application*.yaml` and the compose files
and diffing against `secrets-reference.md`. Since `fix/prod-readiness-gaps` each one is documented in
`secrets-reference.md` → "Optional application variables", and all but `APP_STORAGE_LOCAL_BASE_DIR`
(unused by the S3 backend PROD runs) are passed through `docker-compose.yml` with defaults that mirror
`application.yaml`.

| Placeholder | Default | Note |
|---|---|---|
| `APP_PAYMENT_STRIPE_CALLBACK_SUCCESS_URL` | `/coach/payment-settings` | Post-OAuth redirect inside the SPA. The relative default is probably fine **(confirm)** |
| `APP_BOOTSTRAP_ADMIN_FIRST_NAME` / `_LAST_NAME` | `Platform` / `Administrator` | Display name of the bootstrapped admin |
| `APP_STORAGE_BACKUP_ENDPOINT_URL`, `_BUCKET`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY` | blank / `us-east-1` | Secondary bucket for `app.storage.replication`. Disabled by default |
| `APP_STORAGE_LOCAL_BASE_DIR` | `/tmp/skillars-storage` | Only used when `app.storage.provider=local` |
| `ARACHNID_API_BASE_URL` | `https://api.c3p.ca` | Only with `features.toggles.arachnid-enabled` (`ARACHNID_API_KEY` is documented) |
| `VIDEOINTEL_PROJECT_ID`, `VIDEOINTEL_CREDENTIALS_PATH` | blank | Only with `features.toggles.videointel-enabled` |
| ~~`PLATFORM_NOTIFICATION_EMAIL`~~ | removed | The property had no reader and a personal default; `fix/prod-readiness-gaps` deleted it. Setting it does nothing |
| `APP_BOOTSTRAP_JWT_SECRET_ENABLED` | `false` | Now passed through compose (see [section 0](#0-open-issues-found-while-compiling-this-checklist)) |

---

## 3. Database

Detail: [`migration-conventions.md`](migration-conventions.md),
[`migration-rebaseline.md`](migration-rebaseline.md), [`backup-restore.md`](backup-restore.md).

### 3.1 Flyway and schema ownership

- [ ] Flyway runs at application boot: `spring.flyway.enabled: true`, `defaultSchema: main`,
  `validateMigrationNaming: true`, `baseline-on-migrate: true` (`application.yaml`). The history
  table is `main.flyway_schema_history`.
- [ ] **A fresh environment starts from an empty database.** `baseline-on-migrate: true` would
  silently baseline a non-empty database that has no history table and skip the V138 schema
  baseline. History starts at `V138__baseline_schema.sql` / `V139__baseline_seed_data.sql`. A UAT
  database from before the rebaseline must be rebuilt (`migration-rebaseline.md`).
- [ ] `custom.flyway.check-schema: true` (`application.yaml`). `DbSchemaChecker` refuses to start
  while migrations are pending.
- [ ] Roles: one database role. The app connects as `POSTGRES_USER` (compose default `postgres`,
  `secrets-reference.md` suggests `skillars`), which is the container's initial superuser, and
  Flyway runs DDL as that role. It therefore owns every schema (`admin`, `booking`, `development`,
  `main`, `marketplace`, `messaging`, `payment`, `reviews`, `session`; V138 is dumped with
  `--no-owner --no-privileges`). No separate migration or read-only role exists. Decide whether
  PROD wants a least-privilege runtime role **(confirm)**.
- [ ] Hikari: `maximum-pool-size: 25`, `auto-commit: false` (`application.yaml`). This stays
  inside Postgres's default 100 connections with one app instance.
- [ ] Find the current version before each release:
  ```sql
  SELECT version, description, success FROM main.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
  -- any success = false row must be repaired before deploying
  ```

### 3.2 Pre-migration data checks

New environments have no data, so these checks apply when **upgrading an existing database** (UAT
now, PROD from its second release on). Run every check whose migration is newer than the current
`flyway_schema_history` version. **Each query must return 0 rows.** If one does not, fix the data
first and get the fix reviewed.

The strongest gate is a rehearsal: restore the latest dump into a scratch Postgres
(`backup-restore.md` Section A), point the target image (or the Flyway CLI with
`src/main/resources/db/migration` from the target commit) at it, and let it migrate. Any CHECK or
unique index that fails will fail there first **(confirm procedure)**.

**V195: `uq_spp_stripe_payment_intent_id`** (partial unique index, `V195__session_pack_integrity.sql`)
```sql
SELECT stripe_payment_intent_id, count(*)
FROM payment.session_pack_purchases
WHERE stripe_payment_intent_id IS NOT NULL
GROUP BY 1 HAVING count(*) > 1;
-- non-zero: deduplicate the double-inserted packs for the same PaymentIntent first
```

**V205: payout recovery** (`V205__payout_recovery.sql`). Additive only: new nullable
`original_*_amount` columns plus defaulted `topup_amount` / `debt_offset_amount` on
`payment.coach_payouts`, and a new empty `payment.coach_payout_debts` table. Nothing to check before it
runs. It backfills `original_*` from the current amounts, so a payout already cut by a dispute before
this release keeps that cut as its "original" (an `UPHOLD_COACH` on it restores only to the cut net). To
list those rows after the migration:
```sql
SELECT booking_id, status, net_amount, last_error
FROM payment.coach_payouts
WHERE last_error LIKE 'DISPUTE_RESOLVED_%' OR last_error LIKE 'CHARGEBACK_LOST -%';
```
Rows in `REVERSAL_FAILED` from before this release are not converted to coach debt; settle them as the
runbook says (Scenario 5).

**V200: pack refund settlement** (`V200__pack_refund_settlement.sql`). It adds no constraint to an
existing table, so there is nothing to check before it runs. It backfills `payment.pack_session_refunds`
and the new `refund_sessions_issued` / `refund_gross_issued` counters from the existing expired-pack
`BOOKING_REFUND` ledger rows. **After** it runs, this query must return 0 rows. A row means a pack has
paid out plus refunded more than it was charged, and must be reconciled by hand:
```sql
SELECT purchase_id, amount_charged, payout_gross_allocated, refund_gross_issued
FROM payment.session_pack_purchases
WHERE amount_charged IS NOT NULL
  AND payout_gross_allocated + refund_gross_issued > amount_charged;
```

**V213: completion and dispute integrity** (`V213__completion_dispute_integrity.sql`). Adds
`booking.bookings.confirmation_requested_at` (nullable) and `payment.coach_cancellation_history.after_start`
(`NOT NULL DEFAULT false`, catalog-only) and `booking.session_completion_data.awaiting_wrap_up` (same), and replaces the partial unique index
`idx_disputes_unique_open_per_booking` with `idx_disputes_unique_open_per_booking_side` (one open dispute per
booking per side). The new index is looser than the old one, so every existing row satisfies it: nothing to
check before it runs. It is a non-concurrent index build on the small `admin.disputes` table. After deploy:
bookings already in COMPLETED_PENDING_CONFIRMATION have no `confirmation_requested_at`. Their family is emailed
when the coach submits the wrap-up, as before. A family whose coach never submits one is not emailed before the
stuck-completion sweeper completes the booking. To see that backlog:
```sql
SELECT count(*) FROM booking.bookings b
WHERE b.status = 'COMPLETED_PENDING_CONFIRMATION'
  AND NOT EXISTS (SELECT 1 FROM booking.session_completion_data s WHERE s.booking_id = b.id);
```

**V209: booking completion integrity** (`V209__booking_completion_integrity.sql`). Two nullable columns on
`booking.bookings` (`auto_ended_at`, `unresolved_alerted_at`) and the `admin_alerts_type_check` rebuilt with
`SESSION_NEVER_STARTED`. Nothing to check before it runs (the CHECK only widens). After deploy, the new
`UnresolvedSessionSweeperService` picks up sessions that already ended unsettled, 200 per 5-minute tick:
live sessions (IN_PROGRESS / PAUSED) whose end is more than `booking.unresolvedSession.graceMinutes` (120)
ago move to COMPLETED_PENDING_CONFIRMATION, and ended never-started UPCOMING sessions each raise one
`SESSION_NEVER_STARTED` alert (payment-integrity bucket). To see that backlog before deploying:
```sql
SELECT status, count(*) FROM booking.bookings
WHERE status IN ('IN_PROGRESS', 'PAUSED', 'UPCOMING')
  AND requested_end_time < now() - interval '120 minutes'
GROUP BY 1;
```
The booking expiry job now also declines REQUESTED bookings whose start time has passed (not only those
older than `booking.request_expiry_hours`), so the first run after deploy can expire a backlog of them.

**V198: `uq_pcl_booking_refund_reversal`** (`V198__forfeit_payouts_and_dispute_clawback.sql`). The
type is new in V198, so this can only fail if rows were hand-inserted:
```sql
SELECT reference_id, count(*) FROM payment.parent_credit_ledger
WHERE reference_id IS NOT NULL AND type = 'BOOKING_REFUND_REVERSAL'
GROUP BY 1 HAVING count(*) > 1;
```

**V198: `chk_ledger_type` and `chk_ledger_amount_sign`** (rebuilt as validating CHECKs)
```sql
SELECT tx_id, type, amount FROM payment.parent_credit_ledger
WHERE type NOT IN ('BOOKING_DEDUCTION','BOOKING_DEDUCTION_REVERSAL','BOOKING_REFUND','BOOKING_REFUND_REVERSAL',
                   'CASH_OUT_DEBIT','STRIPE_FEE_DEBIT','CASH_OUT_REVERSAL')
   OR NOT (
        (type IN ('BOOKING_DEDUCTION','CASH_OUT_DEBIT','STRIPE_FEE_DEBIT','BOOKING_REFUND_REVERSAL') AND amount < 0)
     OR (type NOT IN ('BOOKING_DEDUCTION','CASH_OUT_DEBIT','STRIPE_FEE_DEBIT','BOOKING_REFUND_REVERSAL') AND amount > 0));
-- note: amount = 0 violates the sign rule for every type
```

**V198: `disputes_resolution_check`**
```sql
SELECT id, resolution FROM admin.disputes
WHERE resolution IS NOT NULL
  AND resolution NOT IN ('FULL_CREDIT','PARTIAL_CREDIT','NO_ACTION','COACH_WARNING','UPHOLD_COACH');
```

**V185 / V189 / V198: `envelope_entity_email_template_check`** (rebuilt three times. The V198 list
is the current union.)
```sql
SELECT email_template, count(*) FROM main.envelope_entity
WHERE email_template IS NOT NULL AND email_template NOT IN (
 'NONE','ACTIVATION','CREATION_DUP','PASSWORD_RESET','SEND_OTP','COACH_EMAIL_VERIFY','COACH_OTP',
 'PARENT_EMAIL_VERIFY','PARENT_OTP','PLAYER_EMAIL_VERIFY','PLAYER_OTP','EMAIL_CHANGE','PROFILE_CHANGE',
 'BOOKING_REQUESTED','BOOKING_CONFIRMED','BOOKING_DECLINED','BOOKING_PAYMENT_UNRESOLVED','BOOKING_EXPIRED',
 'BOOKING_REMINDER','BOOKING_QUICK_COMPLETE_CONFIRM','BOOKING_RESCHEDULE_REQUESTED','BOOKING_RESCHEDULE_ACCEPTED',
 'BOOKING_RESCHEDULE_DECLINED','BOOKING_RESCHEDULE_REQUESTED_BY_COACH','BOOKING_RESCHEDULE_DECLINED_BY_PARENT',
 'BOOKING_DUPLICATE_PROPOSED','BOOKING_BATCH_REQUESTED','BOOKING_BATCH_ACCEPTED','SESSION_PACK_EXPIRY_WARNING',
 'SESSION_PACK_EXPIRED','BOOKING_CANCELLED_DUE_TO_PAUSE','PACK_PAUSED','PERFORMANCE_REPORT_SHARED',
 'VIDEO_MODERATION_ADMIN_ALERT','VIDEO_MODERATION_OWNER_FLAGGED','BOOKING_CANCELLED_BY_PARENT',
 'BOOKING_CANCELLED_BY_COACH','COACH_NO_SHOW','PLAYER_NO_SHOW','COACH_VISIBILITY_REDUCED','SES_CUTOVER_PREFLIGHT',
 'VIDEO_APPROVAL_REQUESTED','VIDEO_APPROVAL_APPROVED','VIDEO_APPROVAL_REJECTED','COACH_SUSPENDED','COACH_REINSTATED',
 'COACH_WARNING_ISSUED','DISPUTE_RESOLVED','DISPUTE_DISMISSED','GDPR_EXPORT_READY','REVIEW_APPROVED','REVIEW_BLOCKED',
 'BOOKING_CANCELLED_COACH_UNAVAILABLE','BOOKING_CANCELLED_BY_SKILLARS','DISPUTE_COACH_UPHELD')
GROUP BY 1;
```

**V185 / V186 / V193: `admin_alerts_type_check` and `admin_alerts_reference_type_check`** (V193
list is the current union)
```sql
SELECT alert_id, type, reference_type FROM admin.admin_alerts
WHERE type NOT IN ('MESSAGE_REPORT','CONVERSATION_REPORT','REVIEW_FLAG','STRIKE_THRESHOLD','DISPUTE_RAISED',
                   'MODERATION_UNRESOLVED','GDPR_ERASURE_DEADLINE','SUBSCRIPTION_ORPHANED','SAFETY_HOLD_BOOKING',
                   'CASH_OUT_UNRESOLVED','CHARGEBACK','EXTERNAL_REFUND','PAYOUT_HELD','PAYOUT_REVERSAL_FAILED')
   OR (reference_type IS NOT NULL AND reference_type NOT IN
       ('MESSAGE','CONVERSATION','REVIEW','COACH','BOOKING','GDPR_REQUEST','CASH_OUT','PAYMENT_INTENT'));
```

**V189: `admin_action_log_action_type_check`**
```sql
SELECT log_id, action_type FROM admin.admin_action_log
WHERE action_type NOT IN ('MESSAGE_APPROVE','MESSAGE_BLOCK','CONVERSATION_UNBLOCK','REVIEW_APPROVE','REVIEW_BLOCK',
  'COACH_SUSPEND','COACH_REINSTATE','COACH_STRIKE_ISSUED','COACH_STRIKE_DELETED','DISPUTE_RESOLVE',
  'BOOKING_FORCE_COMPLETE','COACH_TIER_CHANGE','USER_SUSPEND','USER_REACTIVATE','USER_LOGIN_UNLOCK',
  'DISPUTE_DISMISS','APPEAL_UPHOLD','APPEAL_OVERTURN','COACH_SAFETY_HOLD','BOOKING_ADMIN_CANCEL');
```

**V186: `chk_coach_payouts_status`**
```sql
SELECT status, count(*) FROM payment.coach_payouts
WHERE status NOT IN ('PENDING_RELEASE','RELEASED','REVERSED','REVERSAL_FAILED','CANCELLED','HOLD',
                     'FAILED_PERMANENT','FROZEN')
GROUP BY 1;
```

**V192: `chk_bp_status`**
```sql
SELECT status, count(*) FROM payment.booking_payments
WHERE status NOT IN ('CAPTURE_PENDING','CAPTURED','CHARGE_FAILED','FROZEN','CAPTURE_ABANDONED','REFUNDED_TO_CREDIT')
GROUP BY 1;
```

**Release check, no migration (PR #353): non-pack bookings without `agreed_price`.** Since #353,
accept and batch settlement charge only the price locked at request time, never the coach's current
price. These rows predate V192, or were requested while the coach had no pricing. They are not
blocked by a migration. Instead, accept refuses them with `booking.priceNotLocked`: the coach declines
and the family re-requests. Run this before deploying to an existing database and tell the affected
coaches and parents. It is informational, so a non-zero result does not block the deploy:
```sql
SELECT id, status, coach_id, parent_id, batch_id, created_at
FROM booking.bookings
WHERE session_pack_purchase_id IS NULL AND agreed_price IS NULL
  AND status IN ('REQUESTED', 'PAYMENT_PENDING');
-- REQUESTED: accept now fails with booking.priceNotLocked (decline, then re-request).
-- PAYMENT_PENDING with a batch_id: settlement declines it and charges nothing.
```

**V197: `chk_pcs_status` / `chk_pps_status`** are added `NOT VALID`, so the migration itself
cannot fail. Run this before anyone adds a later `VALIDATE CONSTRAINT`:
```sql
SELECT 'coach' AS t, status, count(*) FROM payment.coach_subscriptions
 WHERE status NOT IN ('ACTIVE','PAST_DUE','CANCELLED','TRIALLING','INCOMPLETE','UNPAID','PAUSED') GROUP BY 2
UNION ALL
SELECT 'player', status, count(*) FROM payment.player_subscriptions
 WHERE status NOT IN ('ACTIVE','PAST_DUE','CANCELLED','TRIALLING','INCOMPLETE','UNPAID','PAUSED') GROUP BY 2;
```

**V199: `chk_ledger_type` / `chk_ledger_amount_sign` and the chargeback-credit unique indexes**
(`V199__chargeback_credit_trace_and_lost_dispute_payouts.sql`). Both CHECKs are rebuilt as validating
supersets of V198 (adding `CHARGEBACK_CREDIT_REVERSAL`, negative, and `CHARGEBACK_CREDIT_RESTORE`,
positive). The V198 check above still covers every existing row. The two new types are new in V199, so
the unique indexes can only fail if rows were hand-inserted:
```sql
SELECT reference_id, parent_id, type, count(*) FROM payment.parent_credit_ledger
WHERE type IN ('CHARGEBACK_CREDIT_REVERSAL','CHARGEBACK_CREDIT_RESTORE')
GROUP BY 1, 2, 3 HAVING count(*) > 1;
```

**V199 follow-up (not a migration blocker): payouts parked by a dispute lost before completion.** Before
this release, a session that completed after its charge lost a card dispute was written `HOLD` /
`CHARGEBACK_LOST` and nothing released or cancelled it. These rows are not migrated. Settle each one by
hand (cut or cancel it, `runbook.md` Scenario 5):
```sql
SELECT booking_id, coach_id, net_amount, updated_at FROM payment.coach_payouts
WHERE status = 'HOLD' AND last_error = 'CHARGEBACK_LOST';
```

**V206: `booking_batches_status_check`** (`V206__booking_request_consent.sql`, rebuilt as a validating
CHECK with `ACCEPTING` added). It is the union of the old values, so every existing row passes; this
must return 0 rows:
```sql
SELECT id, status FROM booking.booking_batches
WHERE status NOT IN ('PENDING', 'ACCEPTING', 'FULLY_ACCEPTED', 'PARTIALLY_ACCEPTED', 'DECLINED');
```
**V206 follow-up (informational, not a blocker):** coach "repeat next week" bookings created before
V206 get `proposed_by_coach = false`, so the coach can still accept them. List any still waiting; decline
them, or let them expire, if the family never asked for them:
```sql
SELECT b.id, b.coach_id, b.parent_id, b.requested_start_time FROM booking.bookings b
WHERE b.status = 'REQUESTED' AND b.batch_id IS NULL
  AND EXISTS (SELECT 1 FROM booking.bookings o WHERE o.status = 'COMPLETED' AND o.coach_id = b.coach_id
              AND o.player_id = b.player_id AND o.requested_start_time = b.requested_start_time - interval '7 days');
```

**V208: spend-only wallet credit, refund kinds, alert type** (`V208__wallet_spend_only_credit_and_refund_kinds.sql`).
It adds `parent_credit_ledger.spend_only_amount` (`NOT NULL DEFAULT 0`, catalog-only) with
`chk_ledger_spend_only`, rebuilds `chk_ledger_type` with `DISPUTE_CREDIT` and `BATCH_SHARE_REFUND`, rebuilds
`admin_alerts_type_check` with `BOOKING_ACTION_REQUIRED`, and adds the `uq_pcl_batch_share_refund` and
`idx_bp_stripe_payment_intent` indexes. Every CHECK is a superset of the old one and every existing row has
`spend_only_amount = 0`, so nothing can fail before it runs. **No backfill**: the ledger is append-only
(`trg_ledger_no_update`), so expired-pack parent-cancel refunds written since #355 stay cashable. After it
runs on an upgraded UAT/PROD database, list them (informational, not a blocker; prod is not live, so PROD
returns none) and decide by hand whether any needs a correcting entry:
```sql
SELECT tx_id, parent_id, amount, created_at FROM payment.parent_credit_ledger
WHERE type = 'BOOKING_REFUND' AND description = 'Parent cancellation >24h — expired pack refund';
```
Admin dispute credits written before V208 are `BOOKING_REFUND` rows and stay so (UPHOLD_COACH can still
claw one back); new ones are `DISPUTE_CREDIT`.

**V210: session pack pause / dispute freeze / revoke** (`V210__session_pack_pause_freeze_revoke.sql`).
Four nullable columns on `payment.session_pack_purchases` and two validating CHECKs
(`chk_spp_pause_window`, `chk_spp_revoked_reason`) over columns that are new in V210, so every existing
row passes. A pause recorded before V210 has no `paused_from` and keeps its old meaning (paused until
`paused_until`). Informational, after the deploy: packs whose pause was scheduled for a later date were
blocked from the moment it was scheduled; from V210 on, a new pause is only in force from its start.
```sql
SELECT purchase_id, parent_id, paused_until FROM payment.session_pack_purchases
WHERE paused_from IS NULL AND paused_until > now();
```

**V217: Stripe OAuth state + payout account hold** (`V217__stripe_oauth_state_and_payout_account_hold.sql`).
Additive only: a new empty `payment.stripe_oauth_states` table (single-use OAuth `state` nonces, hashed) and a
nullable `coach_stripe_accounts.payout_hold_until`. Nothing to check before it runs. Onboarding links minted
before the deploy carry the old state (the coach id) and are rejected by the callback; the coach just clicks
"Connect" again.

**V214: open pack-reservation index** (`V214__bookings_open_pack_reservation_index.sql`).
One partial btree index, `idx_bkg_open_pack_reservation` on `booking.bookings (session_pack_purchase_id)`
`WHERE session_pack_purchase_id IS NOT NULL AND status IN ('REQUESTED','ACCEPTED','PAYMENT_PENDING')`, for
the request-time pack reservation count. Built non-concurrently (marker `allow-blocking-index`) with
`lock_timeout = 5s`: it blocks writes to `booking.bookings` while it builds, so on a database with real
traffic run the deploy in a quiet window; if the migration times out on the lock, re-run the deploy. No
data check needed.

V187, V188, V191 and V193's new columns and tables are additive (new tables, `NOT NULL DEFAULT`
constants, nullable columns) and need no data check. V186's backfill `UPDATE` of
`session_pack_purchases.session_count` cannot violate a constraint.

---

## 4. Stripe

Detail: [`uat-deployment.md`](uat-deployment.md) Steps 2 and 5, [`runbook.md`](runbook.md)
Scenarios 4 and 5, `docs/dev-docs/payment/index.html`.

### 4.1 Keys and modes

- [ ] PROD uses **live** mode keys. UAT and every other environment use **test** mode keys only.
  `PaymentConfig` refuses an `sk_live_`/`rk_live_` key unless the `prod` profile is active.
- [ ] The secret key, publishable key, Connect client ID (`ca_`), webhook secrets and price IDs all
  come from the **same** mode. Test and live objects are not interchangeable.
- [ ] Publishable key: the frontend has no build-time Stripe config (`src/frontend` reads no
  `import.meta.env`). The SPA fetches the key at runtime from `GET /api/payment/stripe/config`
  (`SessionPackPaymentResource`), so `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` on the backend is the only
  place to set it.
- [ ] Webhook endpoint API version matches the SDK: `stripe-java` 28.4.0 (`pom.xml`) pins API
  version `2025-02-24.acacia`. The handlers use `deserializeUnsafe`, but a mismatch is what the
  `STRIPE_WEBHOOK_DESERIALIZE_FAILED` retry loop in `StripeWebhookService` warns about **(confirm)**.
- [ ] Stripe SDK timeouts (`PaymentConfig`, optional): `APP_PAYMENT_STRIPE_CONNECT_TIMEOUT_MS` (10000),
  `APP_PAYMENT_STRIPE_READ_TIMEOUT_MS` (30000), `APP_PAYMENT_STRIPE_MAX_NETWORK_RETRIES` (2). The defaults
  are right for PROD and UAT. Do not raise them much: a booking charge holds the payer's wallet lock for
  the whole call.

### 4.2 Connect platform settings

- [ ] Connect enabled with **OAuth for Standard accounts**. The app builds `OAuth.authorizeUrl`
  with `scope=read_write` and exchanges the code with `OAuth.token` (`StripeOnboardingService`).
- [ ] Redirect URI registered under Connect → Onboarding/OAuth settings. It must equal
  `APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL` exactly (`https://<DOMAIN>/api/payment/coaches/me/stripe/callback`,
  `StripeOnboardingResource`), once per mode.
- [ ] Money flow is "separate charges and transfers": the platform charges, and coach payouts are
  `Transfer.create` calls after `payment.payout.hold_hours` (`StripeClient.createTransfer`,
  `CoachPayoutOutboxSupport`). Connected accounts therefore need the `transfers` capability,
  stored as `coach_stripe_accounts.transfers_capability` (V193) **(confirm the Connect settings
  request it)**.
- [ ] Connect branding (platform name, icon, colours) set for the OAuth screen **(confirm)**.
- [ ] Platform payout schedule and bank account (live) **(confirm)**.

### 4.3 Webhook endpoints and exact event lists

Stripe needs **two** endpoints per mode, because a connected account's events (coaches are OAuth
Standard accounts, `StripeOnboardingService`, `scope=read_write`) are delivered only to an endpoint
that listens to "events on connected accounts", and Stripe gives every endpoint its own `whsec_`
signing secret. Both paths are served by `StripeWebhookResource` (permit-all, 1 MB body cap) and
share the idempotency table. Event types handled, taken from the `equals("…")` branches in
`StripeWebhookService.handleEventAtomically` / `handleConnectEventAtomically` (anything else is
logged at DEBUG and acknowledged):

| Endpoint (per mode) | Dashboard setting | URL | Events (exact) | Secret env var |
|---|---|---|---|---|
| **Account** endpoint | "Events on your account" | `https://<DOMAIN>/api/payment/webhooks/stripe` | `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, `invoice.payment_failed`, `charge.dispute.created`, `charge.dispute.closed`, `charge.refunded` | `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` |
| **Connect** endpoint | "Events on connected accounts" | `https://<DOMAIN>/api/payment/webhooks/stripe/connect` | `account.updated`, `account.application.deauthorized` (`StripeWebhookService.CONNECT_EVENT_TYPES`) | `APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET` |

- [ ] Both endpoints created for PROD (live mode) and UAT (test mode): four endpoints in total.
- [ ] Each endpoint's own signing secret copied into its env var (above). Swapping them makes both
  fail with 400 `payment.webhookSignatureInvalid`.
- [ ] Do **not** subscribe the Connect endpoint to subscription, invoice, charge or dispute events.
  For a Standard account those are the coach's own business, not this platform's. The Connect
  path records and ignores them, so subscribing to them only adds noise.
- [ ] Do not point the Connect endpoint at `/api/payment/webhooks/stripe` (the old "same URL"
  advice): that path verifies only the account secret.
- [ ] Easy to miss:
  - `invoice.paid` returns a recovered `PAST_DUE` subscription to `ACTIVE` and resets the grace
    clock. Without it, a paying subscriber can be cancelled at grace expiry.
  - `charge.dispute.created` / `charge.dispute.closed` freeze and unfreeze the coach payouts the
    charge funded (`ChargeDisputeWebhookService`, `payment.charge_disputes`, V193).
    Since V199 they also take back wallet credit the disputed charge had funded (a booking refunded
    to credit), refuse cash-out while the dispute is open, restore the credit on a won dispute, and
    cut or cancel the payout of a session that completes after a lost one. No new event types.
  - `charge.refunded` covers refunds made in the Dashboard (raises an `EXTERNAL_REFUND` admin
    alert).
  - `account.updated` (Connect endpoint) is the primary path that marks a coach's Connect account
    `COMPLETE` or `RESTRICTED` (and records the `transfers` capability that gates payouts). The
    fallback reads the account from Stripe when the coach returns from OAuth and, at most every
    30 s per coach, while `GET /api/payment/coaches/me/stripe/status` is called on a non-`COMPLETE`
    account. `CoachStripeAccountResyncScheduler` (daily 07:00) re-reads every connected `COMPLETE`
    account, so a missed `account.updated` that restricts a coach is applied within a day.
  - `account.application.deauthorized` (Connect endpoint): the coach disconnected Skillars from their
    Stripe account. The account becomes `RESTRICTED`, not payable (`transfers_capability =
    'deauthorized'`), and one coach-level `PAYOUT_HELD` admin alert is raised (reason
    `STRIPE_DEAUTHORIZED`). Pending payouts then go to `HOLD` instead of retrying; the coach sees a
    reconnect button. After the coach reconnects through OAuth, release the held payouts by hand
    (`runbook.md` → `HOLD`). Without this event, the app only learns of the disconnect when a
    transfer fails.
  - UAT may subscribe to all events (`uat-deployment.md` Step 5). PROD should subscribe to the
    exact list above.
- [ ] Deliveries are idempotent per event ID (`payment.stripe_webhook_events`,
  `insertIfAbsent`), so you can safely resend from the Dashboard.

### 4.4 Subscription products and price IDs

- [ ] Stripe products and recurring prices created in currency `platform.payment.currency`
  (seeded `eur`, `V139`), in each mode.
- [ ] Price IDs stored in `main.platform_config` (seeded **blank** in `V139`; read by
  `SubscriptionService` as `subscription.coach.<tier>.monthly.priceId` and
  `subscription.player.<tier>.<interval>.priceId`). Set them with
  `PUT /api/config/values/{key}` as admin (`ConfigResource`). A blank value gives
  `payment.subscription.priceNotConfigured`.

  | Key | PROD (live `price_…`) | UAT (test `price_…`) |
  |---|---|---|
  | `subscription.coach.instructor.monthly.priceId` | [ ] | [ ] |
  | `subscription.coach.academy.monthly.priceId` | [ ] | [ ] |
  | `subscription.player.athlete.monthly.priceId` | [ ] | [ ] |
  | `subscription.player.athlete.quarterly.priceId` | [ ] | [ ] |
  | `subscription.player.athlete.yearly.priceId` | [ ] | [ ] |
  | `subscription.player.semi_pro.yearly.priceId` | [ ] | [ ] |
  | `subscription.player.pro.yearly.priceId` | [ ] | [ ] |

  The Scout coach tier is free and has no price key (`payment.subscription.cannotSubscribeToFreeTier`).
- [ ] Session packs use PaymentIntents directly, so they need no Stripe product.
- [ ] Customer Portal: **not used**. The code has no `billingportal` or Checkout Session calls.
  Payment methods are saved with SetupIntents. Nothing to configure unless that changes.
- [ ] Radar rules, dispute notification emails, statement descriptor and public business details
  **(confirm)**. None of these is configured from the repository.
- [ ] Fee model keys match the Stripe pricing actually contracted: `payment.stripe.feeRate`
  `0.025`, `payment.stripe.feeFixed` `0.25` (`V139`) **(confirm)**.

---

## 5. Email, SMS, video, object storage and other third parties

### Email

| | PROD | UAT |
|---|---|---|
| Transport | `ses` (`application-prod.yaml`) | `smtp`, round-robin over `gmx` and `gmail` (`application-uat.yaml`) |
| Health group | `/manage/health/notification` includes `ses` | includes `smtp` |

- [ ] PROD: SES production access granted **before** the first prod boot. A sandboxed account
  reports `notification` DOWN (`SesHealthIndicator`; `secrets-reference.md` → "Phase-5 ordering").
- [ ] PROD: IAM principal allows `ses:SendEmail` **and** `ses:GetAccount`.
- [ ] PROD: sender identity verified for `APP_SES_FROM_ADDRESS`. Domain SPF, DKIM and DMARC records
  set **(confirm)**. `APP_SES_CONFIGURATION_SET` matches `^[a-zA-Z0-9_-]{1,64}$` if set.
- [ ] PROD: run the SES cutover preflight `POST /v1/admin/ses/preflight` before real users
  (`runbook.md` → "Pre-production release gate: SES cutover"). It must report `ready: true`.
- [ ] No inbound bounce or complaint handling exists in code (no SNS endpoint). Monitor bounces in
  the SES console **(confirm)**.
- [ ] UAT: `GMX_PASSWORD` and `GMAIL_PASSWORD` set ([section 2.1](#21-required-no-safe-default-boot-fails-or-the-feature-is-dead)).
  Hosts and usernames are literals in `application.yaml` / `application-uat.yaml`.
- [ ] `email.retry.enabled: true` on PROD (`application-prod.yaml`, `EmailRetryScheduler`).

### SMS and push

- [ ] **No SMS gateway and no push provider is integrated.** `SmsRegistrationStrategy` is a stub,
  and the repository has no FCM, APNs or web-push code. Phone OTP stays off
  (`security.registration.phone-otp-required=false`, [section 0](#0-open-issues-found-while-compiling-this-checklist)).

### Video: Bunny.net Stream

- [ ] Stream library created per environment. `BUNNY_API_KEY`, `BUNNY_LIBRARY_ID` and
  `BUNNY_CDN_HOSTNAME` set (passed as `APP_VIDEO_BUNNY_*`, `docker-compose.yml`).
- [ ] Library webhook URL set to `https://<DOMAIN>/api/video/webhooks/bunny`
  (`VideoWebhookResource`, header `X-BunnyStream-Signature`), with its signing secret in
  `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET`.
- [ ] Read `runbook.md` → "Pre-production release gate: queued webhook events" before the first
  PROD deploy.
- [ ] `platform.video.playback.ip_binding_enabled` (seeded `false`). Decide for PROD **(confirm)**.

### Object storage (`filestorage` module over `infrastructure.blobstore`)

- [ ] PROD and UAT run SeaweedFS (`storage`, `chrislusf/seaweedfs:3.97`) behind Traefik on
  `STORAGE_DOMAIN`. Presigned upload and download URLs are built from `https://${STORAGE_DOMAIN}`
  and must be reachable by the browser (`docker-compose.yml` → `app` storage block).
- [ ] `STORAGE_ROOT_USER`/`STORAGE_ROOT_PASSWORD` set (random, ≥ 24 chars); the container exits on
  blank values. The same pair is the app's S3 key (passed by compose, not read from `APP_STORAGE_S3_*`).
- [ ] After the first `up`, `docker compose ps -a storage-init` shows `Exited (0)`: bucket
  `APP_STORAGE_BUCKET` created and CORS set to `https://${DOMAIN}`. Otherwise browser uploads 403 at
  the preflight. Verify: `curl -s -o /dev/null -w '%{http_code}' https://<STORAGE_DOMAIN>/` → `403`.
- [ ] Disk: SeaweedFS goes read-only at 10% free on the Hetzner Volume (`-volume.minFreeSpace=10`),
  so uploads stop before Postgres runs out of space. Alert on disk usage in Grafana.
- [ ] `deploy.yml` recreates `app` only. After changing `storage` or rotating `STORAGE_ROOT_*`, run
  `docker compose up -d storage storage-init app` on the node.
- [ ] UAT (Hostwinds): published port 9500 instead of Traefik (`docker-compose.uat-hostwinds.yml`).
- [ ] Limits accepted: per-user quota `platform.filestorage.quota.default-bytes` 10 GiB, max file
  100 MiB, MIME/extension allowlist (`application.yaml` → `platform.filestorage`).
  `application/octet-stream`/`bin` are no longer allowed; `allowed-entities` limits upload keys to
  `coach_profile`, `coach_branding`, `coach_gallery`. `confirmUpload` checks image/PDF magic bytes and deletes a
  rejected object. A never-confirmed upload leaves an orphan object (no DB row): add a bucket lifecycle rule that
  expires unreferenced objects if storage growth matters.

### AI moderation and safety (optional, off by default)

- [ ] `GEMINI_API_KEY`: message and review moderation and AI narrative features
  (`infrastructure.gemini`, model `gemini-2.0-flash`). Decide for PROD **(confirm)**.
- [ ] `features.toggles.arachnid-enabled: false` (Project Arachnid / C3P, `ARACHNID_API_KEY`). If
  you enable it, `platform.admin_alert_email` must be set or startup aborts (`runbook.md` →
  "Admin alert recipient").
- [ ] `features.toggles.videointel-enabled: false` (Google Video Intelligence,
  `VIDEOINTEL_PROJECT_ID`, `VIDEOINTEL_CREDENTIALS_PATH`). Neither is passed through compose.

### Identity, maps and analytics

- [ ] No third-party login (no OAuth2 client, no social login) and no maps or analytics SDK in
  backend or frontend. The only OAuth flow is Stripe Connect ([section 4.2](#42-connect-platform-settings)).
- [ ] API client allowlist: `allowed.clients` (`application.yaml`, read by
  `SecurityConfiguration.allowedClients`, header `X-Client-Id`) is now `${ALLOWED_CLIENTS:}`: no
  machine client is accepted unless the environment lists one. Only the dev profile keeps the old
  placeholders (`myClientId,...`). The browser SPA does not send the header. If a real integration
  needs one, set a long random id in `.env` **(confirm)**.

---

## 6. Scheduled jobs and ShedLock

Detail: [`scheduler-lock-tuning.md`](scheduler-lock-tuning.md), `docs/dev-docs/scheduled-jobs/index.html`.

- [ ] Scheduling is on unless `app.scheduling.enabled=false` (`SchedulingConfig`,
  `matchIfMissing = true`). Leave it unset on PROD and UAT.
- [ ] ShedLock is always on: JDBC provider (`ShedLockConfig`) with `defaultLockAtMostFor = PT10M`
  (`AsyncConfig`). Scheduler pool size 8 (`spring.task.scheduling.pool.size`).
- [ ] Cron expressions without `zone` run in the JVM zone, which is UTC (`Dockerfile`).
- [ ] If you lower a cadence property, raise the matching lock-floor property too
  (`scheduler-lock-tuning.md` table). `ConfigStartupAssertion` checks lock ordering at boot.

| Job (class.method) | Cadence | Tunable by |
|---|---|---|
| `OutboxService` sweep | 5 min | `app.outbox.sweep-ms` |
| `OutboxPollerScheduler` / `DeletionSchedulerService` (filestorage) | 5 s | `app.storage.poller.fixed-delay-ms` |
| `ConfigService` cache refresh | 300 s | `app.config.cache-ttl-seconds`: config edits take up to 5 min to apply |
| `BookingExpiryScheduler`, `BookingReminderScheduler`, `QuickCompleteTimeoutService`, `StuckCompletionSweeperService` | 5 min | hard-coded |
| `UnresolvedSessionSweeperService` | 5 min | hard-coded; grace `booking.unresolvedSession.graceMinutes` (120). Ends live sessions left open past their end (to COMPLETED_PENDING_CONFIRMATION) and raises one `SESSION_NEVER_STARTED` alert per ended, never-started session |
| `PaymentPendingSweeper` | 15 min | hard-coded |
| `ChargeOutcomeReconciler` | 10 min | hard-coded cadence; age threshold `booking.payment.charge_reconcile_after_minutes` (10). Settles or declines booking charges whose Stripe response was lost; ERROR tags `[CHARGE_RECONCILE_AMOUNT_MISMATCH]` / `[CHARGE_RECONCILE_BOOKING_MOVED]` need an operator |
| `CashOutReconciliationService` | 15 min | hard-coded |
| `SessionPackForfeitureScheduler` | 60 min | hard-coded |
| `SessionPackExpiryNotifier` | daily 08:00 | hard-coded |
| `SubscriptionChangeApplicator` | daily 02:00 | hard-coded |
| `SubscriptionGracePeriodChecker` | daily 03:00 | hard-coded |
| `SubscriptionTierReconciliationScheduler` | daily 04:00 | hard-coded |
| `StripeSubscriptionReconciliationScheduler` | daily 05:00 | hard-coded |
| `GdprErasureRetryScheduler` | daily 06:00 | hard-coded |
| `CoachStripeAccountResyncScheduler` | daily 07:00 | hard-coded; re-reads each connected `COMPLETE` Stripe account (`Account.retrieve`, 200 per page, at most 25 pages per run) and applies it like `account.updated` |
| `StalePayoutAlertScheduler` | daily 06:30 | hard-coded cron; age threshold `payment.payout.stale_alert_days` (14). Alerts only (`PAYOUT_HELD` for FROZEN/HOLD payouts unchanged that long). Pages through every stale row (200 per page, at most 50 pages per run) |
| `MessageRetentionScheduler` | daily 02:00 | hard-coded; deletes per `platform.message_retention_months` |
| `InAppNotificationRetentionScheduler` | daily 03:30 | `platform.notification.in-app-retention-days` (90) |
| `UserAdminService` cleanup | daily 01:00 | hard-coded |
| `AuthCleanupService` | hourly and every 30 min | hard-coded |
| `MessageModerationSweeper`, `ReviewModerationSweeper` | 10 min | hard-coded |
| `MessagingEmitterRegistry` | 30 s | hard-coded |
| `RateLimitingService` bucket eviction | 1 h | hard-coded |
| `InstanceHeartbeatMonitor` | 60 s | `platform.instance-heartbeat.interval-ms` |
| `AlertEvaluationService` / `AlertRuleCache` | 30 s / 60 s | `alert.evaluation-interval-ms`, `alert.rule-cache.refresh-interval-ms` |
| `EmailRetryScheduler` | 60 s | `email.retry.interval-ms` |
| `BandwidthResetService` | monthly, 1st 00:00 UTC | hard-coded |
| `QuotaReservationTimeoutService` | 60 s | `app.video.reservation-check-interval-ms` |
| `VideoSubscriptionLifecycleListener` | 60 s | hard-coded |
| `ModerationSlaMonitorService` | 5 min | `app.video.moderation.sla-monitor-delay-ms` |
| `VideoDeletionOutboxProcessor` | 60 s | `platform.video.deletion.outbox_poll_delay_ms` |
| `ReconciliationWorkerScheduler` reconcile / orphan sweep | 60 s / 5 min | `app.video.reconciliation.fixed-delay-ms`, `app.video.orphan-asset.sweep-delay-ms` |
| `UploadSessionExpiryScheduler` | 30 s | `app.video.upload.expiry-scheduler-delay-ms` |
| `WebhookEventProcessorScheduler` (Bunny) | 5 s | `app.video.webhook.processor-delay-ms` |
| `VideoLifecycleScheduler` | daily 03:00 | `app.video.lifecycle.cron` |
| `RadarCompositeDlqProcessor` | 60 s | `platform.development.radar_composite_dlq.poll_delay_ms` |
| `NeglectedSkillDetectionService` | Mondays 06:00 | `app.development.neglected-detection-cron` |
| `SluSnapshotAppliedRetentionService` | daily 03:30 | `app.slu.snapshot-applied.prune-cron` |

Source: `git grep -n "@Scheduled(" src/main/java`.

- [ ] **UAT vs PROD:** nothing *must* differ. The defaults are the same on both profiles, and no
  scheduler property appears in `application-uat.yaml` or `application-prod.yaml`. The only
  difference is that `email.retry.enabled: true` is set on PROD only.
- [ ] The daily jobs between 02:00 and 05:00 UTC overlap with `pg-backup.sh` (every 6 h, including
  00:00 and 06:00) and `prune-backups.sh` (03:30). No conflict is known.

---

## 7. Application config and policy keys

Business policy lives in `main.platform_config` (seeded by `V139`, `V156`, `V158`, `V199`). It is edited at
runtime with `PUT /api/config/values/{key}` (admin) and cached for 300 s. Bounds and code defaults
are in `ConfigBounds`. `ConfigStartupAssertion` refuses to boot (outside `dev`) on an out-of-range
`failFast` key (`monitoring.md` → "Platform config value ranges"). "Code default" means no seed
row: the coded default applies until an admin stores a value (`ConfigBounds.HAS_CODE_DEFAULT`).

| Key | Default | Source | PROD action |
|---|---|---|---|
| `platform.admin_alert_email` | **blank** | V139 | **Must set** on PROD and UAT. While blank, admin alerts are dropped (`runbook.md` → "Admin alert recipient"). Outside `dev`, every boot logs an ERROR while it is blank (`config.value.misconfigured`, reason `launch_blank`) |
| `subscription.*.priceId` (7 keys) | **blank** | V139 | **Must set** ([section 4.4](#44-subscription-products-and-price-ids)). Same boot ERROR while blank |
| `booking.lateCancel.graceMinutes` | 30 (code default, range 5–1440) | `ConfigBounds`, V191 | Owner decision 2026-10-28: 30 |
| `booking.playerNoShow.graceMinutes` | 15 (code default, range 5–1440) | `ConfigBounds`, V209 | Owner decision 2026-10-29: 15. Minutes after the start before a coach may report a player no-show |
| `booking.unresolvedSession.graceMinutes` | 120 (code default, range 30–10080) | `ConfigBounds`, V209 | Keep. Minutes after a session's end before `UnresolvedSessionSweeperService` acts on it |
| `booking.live.minDurationPercent` | 50 (code default, range 0–100) | `ConfigBounds` | Keep **(confirm)**. A live session ended in under this share of its booked length (Start to End; pauses count as live) waits for the family's confirmation or dispute instead of paying the coach at once. 0 turns the check off |
| `disputes.maxPerBookingPerRaiser` | 2 (code default, range 1–100) | `ConfigBounds` | Keep **(confirm)**. Disputes one side (the coach, or the family's logins together) may raise on one booking in total (open, resolved or dismissed). Past it: 409 `disputes.limitReached`; an appeal is the route past a decision |
| `coach.excusedCancellations.maxPerWindow` | 3 (code default, 0–100) | `ConfigBounds` | Keep unless policy changes |
| `coach.excusedCancellations.windowDays` | 90 (code default, 1–3650) | `ConfigBounds` | Keep |
| `booking.request.maxPendingPerCoach` | 10 (code default, range 1–100) | `ConfigBounds` | Keep. A parent's own requests awaiting one coach's answer at once (single and batch; coach proposals not counted). Raise only if `booking.batch.maxSize` is raised above it |
| `booking.request.maxHorizonDays` | 120 (code default, range 1–730) | `ConfigBounds` | Keep. How many days ahead a session can be requested |
| `payment.payout.hold_hours` | 48 (seed; code default 48, range 0–336) | V139, `CoachPayoutOutboxSupport` | Keep at or above the dispute window you are comfortable with **(confirm)** |
| `payment.payout.newCoach.releasedCount` | 5 (code default, range 0–100) | `ConfigBounds` | Keep **(confirm)**. A coach with fewer payouts ever released than this is "new" and gets the longer hold below. 0 = no new-coach hold. Shown to the coach on the Revenue Dashboard |
| `payment.payout.newCoach.minReleasedNet` | 20 (code default, range 0–10000, whole units of `platform.payment.currency`) | `ConfigBounds` | Keep **(confirm)**. Only RELEASED payouts the coach kept at least this much of count toward `releasedCount`, so tiny sessions cannot end the new-coach hold. 0 = all count |
| `payment.payout.newCoach.holdHours` | 168 (code default, range 0–336) | `ConfigBounds` | Keep **(confirm)**. Payout hold for a new coach (never shorter than `payment.payout.hold_hours`); sized against the card-chargeback window |
| `payment.payout.accountChange.holdHours` | 72 (code default, range 0-336) | `ConfigBounds` | Keep **(confirm)**. Cooling-off during which transfers to a coach's NEW Stripe account are held after they replaced an existing connected account with a different one (V216 `payout_hold_until`); the coach is emailed. A first-time connection is never delayed. 0 = no hold (the email is still sent) |
| `payment.payout.stale_alert_days` | 14 (seed; code default 14, range 1–365) | V199, `StalePayoutAlertScheduler` | Keep. Days a payout may sit FROZEN/HOLD unchanged before a `PAYOUT_HELD` admin alert |
| `platform.commission.rate` | 0.08 | V139 | Set to the agreed rate. The only commission key since V211 (`fix/prod-readiness-gaps` deleted the duplicate `platform.commission_rate`; `ProjectedRevenueService` now reads this one too) |
| `payment.stripe.feeRate` / `feeFixed` | 0.025 / 0.25 | V139 | Match the Stripe contract **(confirm)** |
| `platform.payment.currency` | `eur` | V139 | Keep; it must match the Stripe prices |
| `disputes.submissionWindowDays` | 14 | V139 | Keep |
| `pack.pause.maxDays` | 90 | V139 | Keep |
| `pack.expiry.days.tier1..tier4` | 90 / 180 / 365 / 548 (seed = code default, range 14–3650) | V139, `SessionPackValidity` | Keep. Read since #369 (before it every pack got a flat 60 days): validity from purchase for 1 / 2–5 / 6–10 / 11+ session packs. A bad or out-of-range value falls back to the default; a change applies only to packs bought afterwards |
| `subscription.pastDue.gracePeriodDays` | 7 | V139 | Keep |
| `booking.request_expiry_hours` | 48 | V139 | Keep |
| `booking.quick_complete_timeout_hours` | 24 | V139 | Keep |
| `booking.stuck_completion_timeout_hours` | 72 (code default) | `ConfigBounds` | Keep |
| `booking.payment_pending_sweep_grace_minutes` | 120 | V139 | Keep |
| `booking.payment_pending.capture_pending_max_hours` | 72 (code default, range 6–720) | `PaymentPendingSweeper` | Keep |
| `booking.payment.charge_reconcile_after_minutes` | 10 (code default, range 5–1440) | `ChargeOutcomeReconciler` | Keep. Age a CAPTURE_PENDING reservation must reach before its unknown charge outcome is looked up at Stripe; keep it well above the Stripe SDK worst case (connect + read timeout × (1 + retries)) |
| `booking.batch.maxSize` | 5 | V139 | Keep |
| `reliability.strike.visibilityThreshold` / `suspensionThreshold` | 3 / 5 | V139 | Keep |
| `platform.strike_threshold.admin_alert` / `auto_suspension` | 3 / 5 | V139 | Keep |
| `security.login.max-attempts` / `lock-window-minutes` | 5 / 15 | V139 | Keep |
| `security.login.max-attempts-per-account` | 20 (code default, no seed row) | `LoginRateLimiter` | Keep. Failed logins for one account from any IP in the window; past it the login is refused like the per-IP limit (429), even with the right password, until the oldest failure ages out. Admin "unlock login" clears it |
| `security.registration.phone-otp-required` | `false` | V139 (code fallback `true`) | Keep `false` until SMS exists. `PUT /api/config` rejects `true` while no gateway exists |
| `security.age-policy.*` | u10 9, young-teen 12, teen 17 | V139 | Keep |
| `platform.message_retention_months` | 24 (failFast) | V139 | Legal/GDPR decision **(confirm)** |
| `gdpr.export.urlExpiryHours` | 48 | V139 | Keep |
| `platform.video.playback.ip_binding_enabled` | `false` | V139 | **(confirm)** |
| `video.quota.*`, `coach.tier.*`, `player.tier.*`, `feature.*` | see V139 | V139 | Product decision **(confirm)** |
| `reviews.*` | minSessionAge 7, updateCooldown 30, autoHoldFlagThreshold 3 | V139, V156 | Keep |

Yaml-level settings worth reviewing for PROD (`application.yaml` unless noted):

| Property | Default | Note |
|---|---|---|
| `app.config.cache-ttl-seconds` | 300 | Delay before a `platform_config` edit takes effect |
| `app.email.transport` | `log` (base), `ses` (prod), `smtp` (uat) | Never run PROD on `log` |
| `app.storage.replication.enabled` | `false` | See backups in [section 1](#1-infrastructure) |
| `features.toggles.arachnid-enabled` / `videointel-enabled` | `false` / `false` | |
| `management.tracing.sampling.probability` | 1.0 | Possibly lower on PROD **(confirm)** |
| `app.access-log.enabled` (`APP_ACCESS_LOG_ENABLED`) | `true` | |
| `rate.limiting.enabled` | `true` | Never disable on PROD |
| `custom.cors.allowed-origins` | localhost only | The SPA is same-origin, so PROD needs no entry unless another origin calls the API **(confirm)** |

---

## 8. Accounts and seed data

Detail: [`uat-deployment.md`](uat-deployment.md) Step 8, `secrets-reference.md` → admin bootstrap
callout.

- [ ] **JWT signing key**: a unique `(version='v1', bus_id='jot')` row exists in `main.sec`, and
  it is **not** the test fixture:
  ```sql
  SELECT id, version, bus_id, created_date FROM main.sec WHERE version = 'v1' AND bus_id = 'jot';
  -- exactly one row, and id must NOT be 659287191260154475 (the src/test/resources/sql/secData.sql fixture)
  ```
  Without the row, every request fails with `JWT secret key has not been set in DB`
  (`JwtSecretService`). See [section 0](#0-open-issues-found-while-compiling-this-checklist) for
  how to create it.
- [ ] **Initial admin**: set `APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD` and
  `APP_BOOTSTRAP_ADMIN_PHONE`, deploy, and confirm the log line `admin_bootstrap ... created the
  first administrator`. Then **remove the password** and redeploy (`AdminBootstrapRunner`). Use an
  address no coach, parent or player owns, or the runner silently skips. Store the password in the
  password manager first.
- [ ] Exactly the intended admins exist:
  ```sql
  SELECT u.id, u.email, u.created_date FROM main."user" u
  JOIN main.user_authority ua ON ua.user_id = u.id
  JOIN main.authority a ON a.id = ua.authority_id
  WHERE a.name = 'ROLE_ADMIN';
  ```
- [ ] Admin surfaces are API-only. There is no admin UI (`uat-deployment.md` 8.3).
- [ ] **PROD has no test or seed data.** Never run `src/test/resources/sql/initTestData.sql`,
  `userData.sql`, `account.sql`, `secData.sql` or `docs/deployment/local/seed-accounts.sql` against
  PROD. Check:
  ```sql
  SELECT id, email FROM main."user"
  WHERE email ILIKE '%@example.com'
     OR email IN ('me@yahoo.com','queb@yahoo.com','locked@yahoo.com','not-activated@yahoo.com',
                  'blockme@yahoo.com','guarantee@yahoo.com','gulliver@yahoo.com','vatican@yahoo.com');
  -- must return 0 rows on PROD
  ```
- [ ] Migration-seeded reference data is expected on every environment: roles (`main.authority`),
  `platform_config` and the 20 platform drills (`V139`). No migration seeds a user.
- [ ] UAT test accounts (coach, parent, player) are created through the public registration UI
  (`uat-deployment.md` 8.2). A coach must publish the profile to appear in search.

---

## 9. Release and post-deploy smoke tests

Detail: [`deploy-guide.md`](deploy-guide.md), [`rollback.md`](rollback.md), [`runbook.md`](runbook.md).

### Before each release

- [ ] The release's PRs have updated this checklist (the [Changelog](#changelog) has an entry for
  every new key, event or risky migration).
- [ ] New `.env` variables are added to the node's `.env` **and** appear under `app.environment` in
  `docker-compose.yml` (pull the new compose file onto the node first).
- [ ] Pre-migration checks from [section 3.2](#32-pre-migration-data-checks) run for every pending
  migration, ideally as a rehearsal on a restored dump.
- [ ] **Fresh dump before the deploy:** `deploy.yml` step "Pre-deploy database dump" runs
  `deploy/backup/pg-backup.sh` and fails the deploy if the upload is not verified. Do not tick
  `skip_backup` for a release that carries a migration. The auto-revert does not undo migrations.
- [ ] The candidate image is validated on UAT first (`deploy-guide.md` → "Before Step 2: UAT
  Validation").
- [ ] **Rollback pointer recorded**: the image currently running (`deploy.yml` step "Capture
  pre-deploy image" prints it; also `grep APP_IMAGE /opt/skillars/.env`) and the dump key (the run
  summary shows it as "Pre-deploy dump (rollback pointer)"). Procedure: [`rollback.md`](rollback.md), including "Rollback After Migrations Have Run".

### UAT smoke tests (Stripe test mode)

- [ ] `curl -s https://<UAT_DOMAIN>/actuator/health` returns `{"status":"UP"}`.
- [ ] End-to-end booking with test card `4242 4242 4242 4242`: a parent books a published coach,
  the coach accepts, and the charge appears in the Stripe Dashboard under **Test mode** only.
- [ ] Coach Connect onboarding completes: the coach status reaches `COMPLETE` (`GET
  /api/payment/coaches/me/stripe/status`). The `Account.retrieve` fallback can do this on its own,
  so also check that the **Connect** endpoint (`/api/payment/webhooks/stripe/connect`) shows an
  `account.updated` delivery with **200** in the Dashboard.
- [ ] Stripe Dashboard → Webhooks: each endpoint shows recent deliveries with **200** (green). No
  `400 payment.webhookSignatureInvalid` in `docker compose logs app`.
- [ ] Optional dispute test: test card `4000 0000 0000 0259` creates a dispute, and an admin
  `CHARGEBACK` alert appears.
- [ ] File upload (coach profile photo) renders in the browser, with no CORS 403 on the preflight.
- [ ] Email arrives (verification email through SMTP).
- [ ] Grafana dashboards show the traffic (full UAT only).

### PROD post-deploy

- [ ] `deploy.yml` smoke step passed (`/manage/health/smoke` UP inside the container) and Slack or
  email reports success.
- [ ] External `https://<DOMAIN>/actuator/health` returns UP, and the uptime monitor is green.
- [ ] `docker compose exec app wget -qO- http://localhost:8367/manage/health/notification` returns
  UP (SES production access, `GetAccount` permission).
- [ ] First live deploy only: SES preflight `ready: true` ([section 5](#email)). Webhook endpoints
  show a 200 for a real or resent event.
- [ ] Logs show no `AppSetupException`, `config.value.misconfigured` or `MULTIPLE_INSTANCES`. The
  `skillars_app_instances_live` gauge is 1.
- [ ] Grafana: error rate and latency are normal (`deploy-guide.md` Step 4).
- [ ] If anything is wrong, follow [`rollback.md`](rollback.md) and use the pointer recorded above.

---

## Changelog

Append one line per PR that adds or changes a provisioning item (newest last): date, PR, and what
an operator must now do.

- 2026-10-29: initial consolidated checklist (docs/provisioning-checklist). It records the
  Stripe dual-endpoint/single-secret blocker, the JWT fixture risk, the missing prod object-storage
  service, the env vars missing from `secrets-reference.md`, and pre-migration checks for V185–V198.
- 2026-10-29: PR #352 (V199): new config key `payment.payout.stale_alert_days` (default 14, seeded),
  new daily job `StalePayoutAlertScheduler` (06:30, ShedLock), no new Stripe webhook events. Run the
  V199 pre-migration check, then settle any `HOLD` / `CHARGEBACK_LOST` payouts left by earlier releases
  (section 3.2).
- 2026-10-29: #353 (fix/booking-price-and-excused-race). Section 3.2 has a new informational check
  for non-pack bookings with NULL `agreed_price`, which accept now refuses (`booking.priceNotLocked`).
  No migration and no new config keys.
- 2026-10-29: PR #350 (fix/payment-pack-refund-rounding). There is no new provisioning step. V200 runs
  automatically. Run its post-migration reconciliation query ([3.2](#32-pre-migration-data-checks)) on
  an upgraded UAT/PROD database. V200 joins the "prod is not live" list in section 0.
- 2026-10-29: PR fix/payment-stripe-connect-webhook-endpoint: clears the section 0 Stripe blocker.
  New endpoint `POST /api/payment/webhooks/stripe/connect` and env var
  `APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET` (all compose files). Create a Connect webhook endpoint
  ("events on connected accounts", `account.updated` and `account.application.deauthorized`) at that
  URL in each mode, set its `whsec_`,
  and repoint any existing Connect endpoint that still targets `/api/payment/webhooks/stripe`. No
  migration.
- 2026-10-29: infra/prod-seaweedfs-storage: production now runs single-node SeaweedFS
  (`storage` + `storage-init` in `docker-compose.yml`), which clears the section 0 storage item. Before
  the first `up`: add the `STORAGE_DOMAIN` A record and set `STORAGE_ROOT_USER`,
  `STORAGE_ROOT_PASSWORD`, `STORAGE_DOMAIN` and `APP_STORAGE_BUCKET` in `/opt/skillars/.env`.
  `APP_STORAGE_ENDPOINT_URL` and `APP_STORAGE_S3_*` are now set by compose, not `.env`. New property
  `app.storage.s3.path-style-access` (`APP_STORAGE_S3_PATH_STYLE_ACCESS`, default `false`; compose
  sets `true`). No migration.
- 2026-10-29: fix/payment-payout-recovery (V205). Run the V205 post-migration listing (section 3.2) on
  an upgraded database. New outbox aggregate type `COACH_PAYOUT_TOPUP` (an `UPHOLD_COACH` top-up
  transfer). New admin endpoint `POST /api/admin/payment/coaches/{coachId}/payouts/release-held`, which
  replaces the runbook's manual `UPDATE ... SET status = 'PENDING_RELEASE'` for `HOLD` payouts. `HOLD`
  payouts are also re-driven automatically when `account.updated` (or the onboarding sync) shows that the
  coach's account can receive transfers, so the Connect webhook endpoint (section 4.3) must deliver
  `account.updated`. No new config keys and no new scheduled jobs.
- 2026-10-29: fix/booking-request-consent (V206). Two new config keys, both code defaults with no seed
  row: `booking.request.maxPendingPerCoach` (10) and `booking.request.maxHorizonDays` (120)
  ([section 7](#7-application-config-and-policy-keys)). New parent endpoints
  `PUT /api/bookings/requests/{id}/confirm` and `/withdraw`; coach "repeat next week" bookings now wait
  for the parent's confirmation. Run the V206 pre-migration check and review its informational follow-up
  query ([3.2](#32-pre-migration-data-checks)). V206 joins the "prod is not live" list in section 0. No new
  job, webhook or env var.
- 2026-10-29: fix/payment-charge-idempotency (V207). V207 runs automatically (additive nullable
  columns on `payment.booking_payments`, `coach_subscriptions`, `player_subscriptions` plus a partial
  index on a new all-NULL column; no pre-migration check). New job `ChargeOutcomeReconciler` (10 min,
  ShedLock `ChargeOutcomeReconciler_reconcile`), new code-default config key
  `booking.payment.charge_reconcile_after_minutes` (10), new optional properties
  `app.payment.stripe.connect-timeout-ms` / `read-timeout-ms` / `max-network-retries`
  (`APP_PAYMENT_STRIPE_*`, defaults 10000 / 30000 / 2). No new Stripe webhook events; nothing to set.
- 2026-10-29: fix/payment-wallet-dispute-integrity (V208). No new config key, job or Stripe webhook event.
  V208 runs automatically and joins the "prod is not live" list in section 0. On an upgraded UAT/PROD
  database, run its informational query ([3.2](#32-pre-migration-data-checks)) for expired-pack refunds
  that stay cashable. New admin alert type `BOOKING_ACTION_REQUIRED` (payment-integrity bucket): an admin
  cancel whose refund amount is unknown, or a booking a coach suspension could not cancel. Finish it by
  hand and mark it handled.
- 2026-10-31: fix/security-auth-hardening (V216, additive `phone_otp_tokens.failed_attempts`). The app now
  refuses to start with `activate.security=false` while profile `prod` or `uat` is active: make sure no
  `ACTIVATE_SECURITY=false` override is set on either. New activation/reset keys are 22-character URL-safe
  tokens (old 10-digit keys keep working until they expire). New code-default key
  `security.login.max-attempts-per-account` (20, see the config table).
- 2026-10-29: fix/booking-completion-integrity (V209): new config keys `booking.playerNoShow.graceMinutes`
  (15) and `booking.unresolvedSession.graceMinutes` (120), both code defaults with no seed row. New 5-minute
  job `UnresolvedSessionSweeperService` (ShedLock) and a new admin alert type `SESSION_NEVER_STARTED`
  (payment-integrity bucket). `BookingExpiryScheduler` now also expires REQUESTED bookings past their start
  time. V209 is additive. Run its backlog query (section 3.2) to see what the first sweeps will touch, and
  V209 joins the "prod is not live" list in section 0.
- 2026-10-29: fix/payment-pack-integrity (V210): risky migration (validating CHECKs, "prod is not live"
  list in section 0); see the V210 entry in [3.2](#32-pre-migration-data-checks). New outbox aggregate
  `PACK_SUSPENSION_REFUND` (enqueued by a coach suspension / safety hold, drained by the existing outbox
  drain and sweep). No new config keys, scheduled jobs, env vars or Stripe webhook events (it reacts to
  the already-subscribed `charge.dispute.created` / `charge.dispute.closed` / `charge.refunded`).
- 2026-10-29: fix/prod-readiness-gaps (V211). Operators must now:
  - UAT: set `APP_FRONTEND_URL`, `APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL`, `GMX_PASSWORD` and `GMAIL_PASSWORD`
    in `.env.uat`. The UAT compose files refuse to start without them. `ENVIRONMENT` now defaults to `uat`.
  - Set `ALLOWED_CLIENTS` wherever a tester or integration sends `X-Client-Id`. The placeholder ids are dev-only now.
  - PROD: set `APP_STORAGE_BUCKET`. The compose default changed from `skillars-dev` to `skillars-prod`.
  - Make sure `deploy.yml`'s `SSH_USER` can run `deploy/backup/pg-backup.sh`. Each deploy now dumps the
    database first and stops if the dump fails.

  Other changes:
  - V211 deletes the duplicate `platform.commission_rate` key. It is data-only, so there is no
    pre-migration check.
  - New daily job `CoachStripeAccountResyncScheduler` (07:00, ShedLock).
  - New passthroughs: `APP_BOOTSTRAP_JWT_SECRET_ENABLED`, `APP_PAYMENT_STRIPE_CALLBACK_SUCCESS_URL`,
    `APP_BOOTSTRAP_ADMIN_FIRST_NAME`/`_LAST_NAME`, `APP_STORAGE_BACKUP_*`, `ARACHNID_API_BASE_URL`,
    `VIDEOINTEL_*` and `ALLOWED_CLIENTS`.
  - `PLATFORM_NOTIFICATION_EMAIL` was removed.
  - `phone-otp-required=true` is now refused while no SMS gateway exists.
  - Blank launch keys log an ERROR at boot.
  - No new Stripe webhook events.
- 2026-10-30: #366 (per-session booking from the coach profile), #367 (adult player wallet cash-out),
  #368 (pack status badges, Pause/Extend guards), #369 (pack validity tiers) and #370
  (fix/forfeit-family-emails, V212). #369 starts reading `pack.expiry.days.tier1..tier4`
  ([section 7](#7-application-config-and-policy-keys)): check the seeded rows are still 90 / 180 / 365 / 548
  (or the agreed values). Existing packs keep their expiry; no backfill. V212 rebuilds
  `envelope_entity_email_template_check` with the two family forfeit templates. It is a validating CHECK
  swap over a superset list, so there is no pre-migration check, and it joins the "prod is not live" list
  in section 0. Per-session booking and player cash-out need no config. No new env vars, jobs or Stripe
  webhook events.
- 2026-10-30: fix/audit3-wallet-cashout-integrity. New config keys `payment.payout.newCoach.releasedCount` (5)
  and `payment.payout.newCoach.holdHours` (168), code defaults in `ConfigBounds` with no seed row
  ([section 7](#7-application-config-and-policy-keys)): confirm the values. From deploy, a coach with fewer than
  5 released payouts has new payouts held 7 days instead of 48h; payouts already enqueued keep their release
  time. No migration: cash-outs split across funding charges reuse `cash_out_reconciliations` (one row per
  leg), and refunds' spend-only share is computed from existing ledger rows. No new env vars, jobs or Stripe
  webhook events.
- 2026-10-30: fix/audit3-wallet-cashout-integrity review follow-up: new config key
  `payment.payout.newCoach.minReleasedNet` (20, code default, whole units of the platform currency) — confirm it.
  Only RELEASED payouts of at least that net count toward the new-coach threshold. Still no migration and no new
  admin alert type: a cash-out funding charge Stripe will never return raises a `BOOKING_ACTION_REQUIRED` alert
  (`CASH_OUT_TARGET_INVALID`) on that PaymentIntent — check it and settle the credit by hand.
- 2026-10-30: fix/audit3-completion-dispute-integrity (V213): new config keys `booking.live.minDurationPercent`
  (50) and `disputes.maxPerBookingPerRaiser` (2), both code defaults ([section 7](#7-application-config-and-policy-keys));
  confirm both values with the owner. V213 adds `bookings.confirmation_requested_at` and
  `coach_cancellation_history.after_start` and swaps the disputes partial unique index to one open dispute per
  side. It is a non-concurrent index build, so V213 joins the "prod is not live" list in section 0. Run its backlog
  query ([3.2](#32-pre-migration-data-checks)) for families never asked to confirm. Behaviour changes:
  - the family is emailed (`BOOKING_QUICK_COMPLETE_CONFIRM`) as soon as a session waits for their confirmation,
    and can dispute it or, if it was never started, report a coach no-show;
  - a coach cancel of a never-started session at or after its start time (not a PAUSED one), and a parent's
    refund-eligible late cancel, now issue a
    `COACH_NO_SHOW` reliability strike.

  No new env vars, jobs, email templates or Stripe webhook events.
- 2026-10-30: fix/audit3-booking-scheduling-integrity (V214). New partial index
  `idx_bkg_open_pack_reservation` on `booking.bookings`, built non-concurrently: deploy in a quiet window
  once PROD has traffic (section 3.2). No new config keys, jobs or webhooks.
- 2026-10-31: feat/lgtm-application-dashboards. V215 runs automatically and joins the "prod is not live" list in section 0. Grafana dashboards moved to `deploy/lgtm/dashboards/`, so recreate the container after pulling: `docker compose up -d --force-recreate grafana`. The rewritten alert rules start notifying on the app and business alerts, so confirm a channel is set and check **Alerting → Alert rules** shows every rule healthy.
- 2026-10-31: fix/backlog-metrics-staleness-and-timeout. New optional property `platform.backlog-metrics.query-timeout-seconds` (default 10), no action needed. New Grafana alert `BacklogMetricsStale`: recreate the Grafana container after deploying so it loads.
- 2026-10-31: fix/security-pack-caps-gdpr-stripe-erasure (S-10, S-19). No migration, config key, job or webhook. New outbox aggregate `STRIPE_CUSTOMER_ERASURE` (enqueued by GDPR erasure, drained by the existing outbox drain and sweep): detaches the user's saved cards and deletes their Stripe Customer, or blanks its name/email/phone/description/`userId` metadata if Stripe refuses the delete. If the Stripe secret key is a restricted key, it needs write access to Customers and PaymentMethods. A row stuck in the outbox DLQ means Stripe kept rejecting the call: finish it in the Stripe dashboard (delete or edit the customer named in the row's `stripeCustomerId`). Pack tiers are now capped at 100 sessions and 50000.00 per pack (400 beyond that).
- 2026-10-31: fix/security-payout-oauth-session-revocation (V217, additive). Stripe Connect onboarding now uses a single-use OAuth `state`: a link generated before the deploy fails with `state_mismatch`, the coach reconnects. Replacing a connected Stripe account with a different one holds payouts for `payment.payout.accountChange.holdHours` (default 72, `platform_config`, see section 7) and emails the coach (`profileChange` template, new i18n key in all four bundles). Password reset and password change now revoke all sessions. No new env vars, jobs or webhooks.
