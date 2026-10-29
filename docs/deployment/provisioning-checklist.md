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

- [ ] **Stripe needs two webhook endpoints, but the code verifies only one signing secret.**
  `StripeWebhookService.verifySignature` checks every delivery against the single
  `app.payment.stripe.webhook-secret` (`PaymentProperties.webhookSecret`). Coaches connect
  through OAuth as Standard accounts (`StripeOnboardingService`, `scope=read_write`), so their
  `account.updated` events are sent only to a **Connect** endpoint ("events on connected
  accounts"). Every other handled event (subscriptions, invoices, disputes, refunds) belongs to the
  platform account and goes to an **account** endpoint. Stripe gives each endpoint its own `whsec_`
  secret, so whichever endpoint's secret is *not* configured gets HTTP 400
  `payment.webhookSignatureInvalid` on every delivery. Nothing else moves a coach from `PENDING`
  to `COMPLETE`: the OAuth callback sets `PENDING` (`StripeOnboardingService`), and only
  `handleAccountUpdated` sets `COMPLETE`/`RESTRICTED`. There is no `Account.retrieve` fallback
  anywhere. The fix is a code change: accept a second secret (e.g.
  `app.payment.stripe.connect-webhook-secret`), or try a list of secrets. Until that ships, coach
  onboarding on PROD cannot complete. `uat-deployment.md` Step 5 tells you to tick "Listen to
  events on Connected accounts" for `account.updated` only. That is a per-endpoint setting in
  Stripe, not a per-event one **(confirm in the current Dashboard)**.
- [ ] **Never seed PROD's JWT signing key from `src/test/resources/sql/secData.sql`.**
  `uat-hostwinds-deployment.md` Step 8 and `local/deployment.md` Step 5 seed `main.sec` from that
  fixture. Its encrypted key is committed to the repository, so anyone with repo access could forge
  tokens for an environment seeded from it. The supported generator, `JwtSecretBootstrapRunner`
  (`app.bootstrap.jwt-secret.enabled`), is meant for local use only. It does accept the private
  compose-network address, but `APP_BOOTSTRAP_JWT_SECRET_ENABLED` is **not** in the `app`
  service's `environment:` block in any compose file. Setting it in `.env`, as
  `secrets-reference.md` → "JWT signing key" tells you to, therefore has no effect. Decide how PROD
  gets a unique `(v1, jot)` row **(confirm)**: a one-off compose override that passes the flag, or
  hand-written Jasypt SQL. Then verify with the query in [section 8](#8-accounts-and-seed-data).
- [ ] **Production object storage has no service in `docker-compose.yml`.** UAT runs SeaweedFS
  (`storage` in `docker-compose.uat.yml`). The base/prod compose has no storage container and
  defaults `APP_STORAGE_ENDPOINT_URL` to `http://localhost:9000` and `APP_STORAGE_BUCKET` to
  `skillars-dev`. `secrets-reference.md` → "Storage Architecture" still says "MinIO runs as a
  container in production", which is no longer true. PROD needs an external S3-compatible bucket
  and a browser-reachable HTTPS endpoint **(confirm provider)**.
- [ ] **Deploys take no pre-migration backup.** `deploy.yml` takes
  no database dump before `up -d`, and its auto-revert reverts the image only. After a migration
  has run, an image-only revert is unsafe (`rollback.md` → "Rollback After Migrations Have Run"). Take
  a manual dump before every release that carries a migration (see [section 9](#9-release-and-post-deploy-smoke-tests)).
- [ ] **No SMS gateway exists.** `SmsRegistrationStrategy` sends nothing (it is a TODO stub), and
  `security.registration.phone-otp-required` is seeded `false` (`V139`). Keep it `false` on PROD
  until an SMS provider is integrated, or phone verification blocks every registration **(confirm
  product decision)**.
- [ ] **Recent migrations assume "prod is not live".** V186, V192, V193, V195, V198, V199 and V200 use
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
  | `STORAGE_DOMAIN` (S3 endpoint the browser uses) | external bucket host **(confirm)** | yes (`docker-compose.uat.yml` → `storage` router) |

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
- [ ] Object-storage backup: `app.storage.replication.enabled` is `false` by default
  (`application.yaml`). `APP_STORAGE_BACKUP_*` configure a secondary bucket. Decide whether PROD
  replicates uploaded files **(confirm)**.

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
| `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` | live `whsec_` | test `whsec_` | every webhook gets 400 (see [section 0](#0-open-issues-found-while-compiling-this-checklist)) | `StripeWebhookService` |
| `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` | `pk_live_` | `pk_test_` | card entry, SetupIntent and pack purchase broken | `SessionPackPaymentResource` `/api/payment/stripe/config` |
| `APP_PAYMENT_STRIPE_OAUTH_CLIENT_ID` | live `ca_` | test `ca_` | coach Connect onboarding broken | `StripeOnboardingService` |
| `APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL` | `https://<DOMAIN>/api/payment/coaches/me/stripe/callback` | same with UAT host | Stripe rejects the relative compose default. **Compose's relative default overrides `application-uat.yaml`'s absolute one**, so UAT must set it too | `docker-compose.yml`, `application-uat.yaml` |
| `APP_SES_FROM_ADDRESS` | required (`transport: ses`) | n/a (`transport: smtp`) | `SesPropertiesValidator` aborts boot | `application-prod.yaml` |
| `GMX_PASSWORD`, `GMAIL_PASSWORD` | not checked (SES) | **both required** | `ProviderConfigsValidator` aborts boot. Compose passes `${X:-}` (blank), which overrides the `dev_*` defaults in `application-uat.yaml` | `application-uat.yaml`, `docker-compose.uat.yml` |
| `PLATFORM_REGISTRATION_VERIFICATION_SECRET` | required | required | `RegistrationVerificationTokenService` aborts boot | `application.yaml` |
| `PLATFORM_PIN_ENCRYPTION_SECRET` | required; rotating it invalidates stored PINs | required | fail-fast | `application.yaml` |
| `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | required (base64, ≥ 32 bytes) | required | fail-fast | `application.yaml` |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` | required | required | fail-fast | `application.yaml` |
| `BUNNY_API_KEY`, `BUNNY_LIBRARY_ID`, `BUNNY_CDN_HOSTNAME` | required for video | test library | compose defaults the library ID to the placeholder `123456` | `docker-compose.yml` |
| `APP_STORAGE_BUCKET`, `APP_STORAGE_ENDPOINT_URL` | external bucket **(confirm)** | `https://${STORAGE_DOMAIN}` (set by override) | compose defaults are dev values (`skillars-dev`, `http://localhost:9000`) | `docker-compose.yml`, `docker-compose.uat.yml` |
| `APP_STORAGE_S3_ACCESS_KEY`/`SECRET_KEY` | bucket credentials (blank selects the AWS default chain, which works on AWS only) | `STORAGE_ROOT_USER`/`PASSWORD` | uploads fail | `application.yaml` |
| `STORAGE_ROOT_USER`, `STORAGE_ROOT_PASSWORD`, `STORAGE_DOMAIN` | n/a | required | SeaweedFS identity / routing | `docker-compose.uat.yml` |
| `APP_FRONTEND_URL` | defaults to `https://skillars.com` | **must set**: the compose default overrides `application-uat.yaml` | email links point at PROD | `docker-compose.yml` |
| `ENVIRONMENT` | `prod` (default) | **must set `uat`**: same override problem | logs and Loki labelled `prod` | `docker-compose.yml` |
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
and diffing against `secrets-reference.md`. None of these is passed through `docker-compose.yml`,
so on PROD they can only take their yaml defaults until someone adds them to the `app` service.

| Placeholder | Default | Note |
|---|---|---|
| `APP_PAYMENT_STRIPE_CALLBACK_SUCCESS_URL` | `/coach/payment-settings` | Post-OAuth redirect inside the SPA. The relative default is probably fine **(confirm)** |
| `APP_BOOTSTRAP_ADMIN_FIRST_NAME` / `_LAST_NAME` | `Platform` / `Administrator` | Display name of the bootstrapped admin |
| `APP_STORAGE_BACKUP_ENDPOINT_URL`, `_BUCKET`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY` | blank / `us-east-1` | Secondary bucket for `app.storage.replication`. Disabled by default |
| `APP_STORAGE_LOCAL_BASE_DIR` | `/tmp/skillars-storage` | Only used when `app.storage.provider=local` |
| `ARACHNID_API_BASE_URL` | `https://api.c3p.ca` | Only with `features.toggles.arachnid-enabled` (`ARACHNID_API_KEY` is documented) |
| `VIDEOINTEL_PROJECT_ID`, `VIDEOINTEL_CREDENTIALS_PATH` | blank | Only with `features.toggles.videointel-enabled` |
| `PLATFORM_NOTIFICATION_EMAIL` | **a personal address** (`application.yaml` → `skillars.platform.notification-email`) | `SkillarsPlatformProperties.getNotificationEmail()` has no caller today. Remove the personal default or document it **(confirm)** |

Also undocumented as a passthrough: `APP_BOOTSTRAP_JWT_SECRET_ENABLED` is described in
`secrets-reference.md` → "JWT signing key" as an `.env` setting, but no compose file passes it (see
[section 0](#0-open-issues-found-while-compiling-this-checklist)).

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

The only endpoint is `POST /api/payment/webhooks/stripe` (`StripeWebhookResource`, permit-all,
1 MB body cap, signature checked against `app.payment.stripe.webhook-secret`). Event types
handled, taken from the `equals("…")` branches in `StripeWebhookService.handleEventAtomically`
(anything else is logged at DEBUG and acknowledged):

| Endpoint (per mode) | URL | Events (exact) |
|---|---|---|
| **Account** endpoint ("Your account") | `https://<DOMAIN>/api/payment/webhooks/stripe` | `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, `invoice.payment_failed`, `charge.dispute.created`, `charge.dispute.closed`, `charge.refunded` |
| **Connect** endpoint ("Connected accounts") | same URL | `account.updated` |

- [ ] Both endpoints created for PROD (live mode) and UAT (test mode).
- [ ] Signing secret wired: `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` holds **one** `whsec_`. With two
  endpoints, one of them fails verification until the code accepts two secrets. **Blocker,
  see [section 0](#0-open-issues-found-while-compiling-this-checklist).**
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
  - `account.updated` is the **only** path that marks a coach's Connect account `COMPLETE`.
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

- [ ] `app.storage.provider: s3` (`application.yaml`). PROD needs an S3-compatible bucket with a
  public HTTPS endpoint, because presigned upload and download URLs are built from
  `APP_STORAGE_ENDPOINT_URL` and must be reachable by the browser.
- [ ] Bucket CORS allows the app origin for browser uploads. UAT's `storage-init` applies
  `deploy/seaweedfs/s3-cors.json` (`uat-deployment.md` Step 7). PROD bucket CORS **(confirm)**.
- [ ] UAT: SeaweedFS identity comes from `STORAGE_ROOT_USER`/`PASSWORD`
  (`docker-compose.uat.yml` → `storage`, `deploy/seaweedfs/s3-identity.json`).
- [ ] Limits accepted: per-user quota `platform.filestorage.quota.default-bytes` 10 GiB, max file
  100 MiB, MIME/extension allowlist (`application.yaml` → `platform.filestorage`).

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
  `SecurityConfiguration.allowedClients`, header `X-Client-Id`) still holds placeholder values
  (`myClientId,...`). Confirm what PROD clients send and override if needed **(confirm)**.

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
| `PaymentPendingSweeper` | 15 min | hard-coded |
| `CashOutReconciliationService` | 15 min | hard-coded |
| `SessionPackForfeitureScheduler` | 60 min | hard-coded |
| `SessionPackExpiryNotifier` | daily 08:00 | hard-coded |
| `SubscriptionChangeApplicator` | daily 02:00 | hard-coded |
| `SubscriptionGracePeriodChecker` | daily 03:00 | hard-coded |
| `SubscriptionTierReconciliationScheduler` | daily 04:00 | hard-coded |
| `StripeSubscriptionReconciliationScheduler` | daily 05:00 | hard-coded |
| `GdprErasureRetryScheduler` | daily 06:00 | hard-coded |
| `StalePayoutAlertScheduler` | daily 06:30 | hard-coded cron; age threshold `payment.payout.stale_alert_days` (14). Alerts only (`PAYOUT_HELD` for FROZEN/HOLD payouts unchanged that long) |
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
| `platform.admin_alert_email` | **blank** | V139 | **Must set** on PROD and UAT. While blank, admin alerts are dropped (`runbook.md` → "Admin alert recipient") |
| `subscription.*.priceId` (7 keys) | **blank** | V139 | **Must set** ([section 4.4](#44-subscription-products-and-price-ids)) |
| `booking.lateCancel.graceMinutes` | 30 (code default, range 5–1440) | `ConfigBounds`, V191 | Owner decision 2026-10-28: 30 |
| `coach.excusedCancellations.maxPerWindow` | 3 (code default, 0–100) | `ConfigBounds` | Keep unless policy changes |
| `coach.excusedCancellations.windowDays` | 90 (code default, 1–3650) | `ConfigBounds` | Keep |
| `payment.payout.hold_hours` | 48 (seed; code default 48, range 0–336) | V139, `CoachPayoutOutboxSupport` | Keep at or above the dispute window you are comfortable with **(confirm)** |
| `payment.payout.stale_alert_days` | 14 (seed; code default 14, range 1–365) | V199, `StalePayoutAlertScheduler` | Keep. Days a payout may sit FROZEN/HOLD unchanged before a `PAYOUT_HELD` admin alert |
| `platform.commission.rate` **and** `platform.commission_rate` | 0.08 each | V139 | Two keys for one concept. Payments read `platform.commission.rate`; `ProjectedRevenueService` reads `platform.commission_rate`. Change both together |
| `payment.stripe.feeRate` / `feeFixed` | 0.025 / 0.25 | V139 | Match the Stripe contract **(confirm)** |
| `platform.payment.currency` | `eur` | V139 | Keep; it must match the Stripe prices |
| `disputes.submissionWindowDays` | 14 | V139 | Keep |
| `pack.pause.maxDays` | 90 | V139 | Keep |
| `pack.expiry.days.tier1..tier4` | 90 / 180 / 365 / 548 | V139 | Keep |
| `subscription.pastDue.gracePeriodDays` | 7 | V139 | Keep |
| `booking.request_expiry_hours` | 48 | V139 | Keep |
| `booking.quick_complete_timeout_hours` | 24 | V139 | Keep |
| `booking.stuck_completion_timeout_hours` | 72 (code default) | `ConfigBounds` | Keep |
| `booking.payment_pending_sweep_grace_minutes` | 120 | V139 | Keep |
| `booking.payment_pending.capture_pending_max_hours` | 72 (code default, range 6–720) | `PaymentPendingSweeper` | Keep |
| `booking.batch.maxSize` | 5 | V139 | Keep |
| `reliability.strike.visibilityThreshold` / `suspensionThreshold` | 3 / 5 | V139 | Keep |
| `platform.strike_threshold.admin_alert` / `auto_suspension` | 3 / 5 | V139 | Keep |
| `security.login.max-attempts` / `lock-window-minutes` | 5 / 15 | V139 | Keep |
| `security.registration.phone-otp-required` | `false` | V139 (code fallback `true`) | Keep `false` until SMS exists |
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
- [ ] **Take a fresh dump** before a release that carries a migration:
  `bash /opt/skillars/app/deploy/backup/pg-backup.sh`, which must end with `Upload verified`.
  `deploy.yml` does not do this, and its auto-revert does not undo migrations.
- [ ] The candidate image is validated on UAT first (`deploy-guide.md` → "Before Step 2: UAT
  Validation").
- [ ] **Rollback pointer recorded**: the image currently running (`deploy.yml` step "Capture
  pre-deploy image" prints it; also `grep APP_IMAGE /opt/skillars/.env`) and the dump key from the
  step above. Procedure: [`rollback.md`](rollback.md), including "Rollback After Migrations Have Run".

### UAT smoke tests (Stripe test mode)

- [ ] `curl -s https://<UAT_DOMAIN>/actuator/health` returns `{"status":"UP"}`.
- [ ] End-to-end booking with test card `4242 4242 4242 4242`: a parent books a published coach,
  the coach accepts, and the charge appears in the Stripe Dashboard under **Test mode** only.
- [ ] Coach Connect onboarding completes: the coach status reaches `COMPLETE` (`GET
  /api/payment/coaches/me/stripe/status`). This proves `account.updated` is delivered **and**
  verified ([section 0](#0-open-issues-found-while-compiling-this-checklist)).
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
