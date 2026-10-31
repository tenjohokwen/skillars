# Secrets Reference

This document lists every secret required to run the application and CI/CD pipeline.
**No secret values appear here** — only names, formats, placements, and generation instructions.

---

## Server `.env` — Place at `/opt/skillars/.env` (mode 600)

Copy `.env.example` to `.env`, fill in every value, and SCP to the Node.
`deploy/provision.sh` auto-enforces mode 600 on re-run.

> ### How `.env` reaches the application
>
> There is **no `env_file:` directive in any compose file**. `.env` only feeds `${VAR}`
> placeholders written literally in the compose YAML, so a variable reaches the app's JVM only if
> it is listed under the `app` service's `environment:` block. That block is now complete for a
> production deploy — every app-facing variable in this document is passed through.
>
> **This was not true before 2026-09-02.** `docker-compose.yml` previously listed nine variables
> and stopped, so a production deploy silently ignored every mail, Stripe, video, storage and
> admin-bootstrap value an operator set — and could not start at all, because
> `APP_PAYMENT_STRIPE_API_KEY` resolved empty and `PaymentConfig.configureStripe()` aborts on a
> blank key. If you are looking at a Node that has not been redeployed since, that is why.
>
> Two consequences worth keeping in mind when adding a variable:
>
> - **Adding it to `.env` is half the job.** It must also be listed under `app.environment` in
>   `docker-compose.yml`. UAT and local are layered over that file (`-f docker-compose.yml -f
>   docker-compose.uat.yml`), so they inherit it automatically — only add it to an override
>   file when that environment needs a *different* value.
> - **An empty value is not the same as an unset one.** Spring applies a `${prop:default}` default
>   only when the property is *absent*; an empty environment variable is present-and-blank and
>   overrides the default. The compose defaults therefore mirror the application defaults
>   (`APP_STORAGE_BUCKET` → `skillars-prod`, and so on) rather
>   than being blank. **This includes profile defaults.** Adding a variable to
>   `docker-compose.yml` disables any `${VAR:some-default}` fallback for it in
>   `application-dev.yaml` / `application-uat.yaml`, so a change aimed at production can break
>   local or UAT startup. `docker-compose.local.yml` restates the dev values for exactly this
>   reason.

> **JWT signing key (`JWT_SIGNING_KEY`)** is an operator-supplied secret: Base64, at least 64 bytes decoded
> (`openssl rand -base64 64`). It used to live in the database (`sec` table); it no longer does. See
> "JWT signing key" below and `provisioning-checklist.md` section 2.4.

| Variable | Format | How to obtain or generate |
|---|---|---|
| `APP_IMAGE` | `ghcr.io/tenjohokwen/skillars:sha-<commit>` | Produced by the CI pipeline after push to `master`. For the very first deploy (before CI is set up), build and push manually — see commands below the table |
| `DOMAIN` | FQDN, e.g. `api.example.com` | Your registered domain; must have an A record pointing to the Node IP before first deploy |
| `LETSENCRYPT_EMAIL` | Email address | Your email address; used by Let's Encrypt for certificate expiry notifications |
| `POSTGRES_DB` | Alphanumeric string, e.g. `skillars` | Choose a database name; default `skillars` |
| `POSTGRES_USER` | Alphanumeric string, e.g. `skillars` | Choose a database username; default `skillars` |
| `POSTGRES_PASSWORD` | 32+ character random string | `openssl rand -base64 32` |
| `REDIS_PASSWORD` | 32+ character random string, no spaces or quotes | `openssl rand -base64 32`. Required in prod and UAT: `redis` refuses to start blank and `DeployedSecretsValidator` rejects blank or the local dev value. See "Redis password" below |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Email address | **Temporary — see the callout below.** The login for the platform's first administrator. Choose an address that does not already belong to a coach, parent or player account |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | 24+ character random string | **Temporary — see the callout below.** `openssl rand -base64 24`. Stored bcrypt-hashed; never logged. Record it in your password manager before the first deploy — it cannot be recovered from the running system |
| `APP_BOOTSTRAP_ADMIN_PHONE` | E.164 phone number, e.g. `+491700000000` | **Temporary — see the callout below.** Required whenever the two above are set. `main."user".phone` carries a `UNIQUE` constraint, so this cannot be a shared placeholder — a second admin bootstrapped on the same database needs a different number |
| `GMX_PASSWORD` | String | Password for the `gmx` entry in `app.email.smtp.provider-configs` (story ses-1.2 — moved from the pre-rename `email.providerConfigs`; application.yaml keeps its own provider list, separate from `spring.mail.*`, which no longer exists in this codebase). Referenced with **no default**, so before the compose passthrough was fixed an unset value aborted startup on the `prod` profile |
| `GMAIL_PASSWORD` | String | As above, for the `gmail` provider entry |
| `MANAGEMENT_HEALTH_MAIL_ENABLED` | Boolean | **Dead knob (story ses-1.2 D9), kept for now.** It gates Spring Boot's own mail actuator contributor, which is `@ConditionalOnBean(JavaMailSenderImpl)` — a bean nothing in this codebase registers (the SMTP provider client is constructed manually by `MailSenderProvider`, never exposed as a Spring bean), and deleting `spring.mail.host` means Boot's own auto-registration of that bean no longer fires either. This codebase's real SMTP health check is `SmtpHealthIndicator`, registered separately under the `notification` actuator group — it never authenticates (TCP connect + `220` banner + `EHLO`/TLS only), so this variable does not gate it |
| `BUNNY_API_KEY` | Hex string | Passed to the app as `APP_VIDEO_BUNNY_API_KEY`. Bunny.net Dashboard → Account → API |
| `BUNNY_LIBRARY_ID` | Integer | Passed to the app as `APP_VIDEO_BUNNY_LIBRARY_ID`. Bunny.net Dashboard → Stream → Your Library → Library ID |
| `BUNNY_CDN_HOSTNAME` | Hostname, e.g. `your-library.b-cdn.net` | Passed to the app as `APP_VIDEO_BUNNY_CDN_HOSTNAME`. Bunny.net Dashboard → Stream → Your Library → Pull Zone hostname |
| `MONITORING_DOMAIN` | FQDN, e.g. `monitoring.api.example.com` | Subdomain you configured in DNS (Step 2 of the setup guide); used by Grafana |
| `GF_SECURITY_ADMIN_USER` | Alphanumeric string, e.g. `admin` | Choose a Grafana admin username |
| `GF_SECURITY_ADMIN_PASSWORD` | 24+ character random string | `openssl rand -base64 24` |
| `GF_SMTP_ENABLED` | Boolean | `true` to enable email alerting from Grafana; `false` to disable |
| `GF_SMTP_HOST` | `hostname:port` | SMTP server with port; e.g. `smtp.gmail.com:587`; can use the same provider host as `app.email.smtp.provider-configs` |
| `GF_SMTP_USER` | Email address | SMTP username for Grafana's outgoing email |
| `GF_SMTP_PASSWORD` | String | App password or SMTP credential for Grafana's SMTP user |
| `GF_SMTP_FROM_ADDRESS` | Email address | FROM address on Grafana alert emails |
| `GF_SMTP_FROM_NAME` | String | Display name on Grafana alert emails; default `Skillars Alerts` |
| `GF_SMTP_STARTTLS_POLICY` | String | `MandatoryStartTLS` for port 587 (recommended); `OpportunisticStartTLS` for flexible servers; `NoStartTLS` for unencrypted relay only — port 465 (SMTPS/implicit TLS) is not supported via this setting, use port 587 |
| `GF_ALERT_NOTIFY_EMAIL` | Email address | Recipient for all Grafana-routed alerts |
| `GF_SLACK_WEBHOOK_URL` | HTTPS URL | Slack → Apps → Incoming Webhooks → Add to Slack → select channel → copy URL |
| `STORAGE_ROOT_USER` | String, e.g. `skillars-app` | Access key of the SeaweedFS `storage` service's single S3 identity (production and UAT; renamed from `MINIO_ROOT_USER` 2026-09-28). The app uses the same value as its S3 access key (`docker-compose.yml` passes it as `APP_STORAGE_S3_ACCESS_KEY`). **Required:** the production `storage` container refuses to start if it is blank. Pick any non-guessable name; there is no default |
| `STORAGE_ROOT_PASSWORD` | 24+ character random string | Secret key of that identity, also used by the app (`APP_STORAGE_S3_SECRET_KEY`) and by `storage-init` (production and UAT; renamed from `MINIO_ROOT_PASSWORD` 2026-09-28). `openssl rand -base64 24`. **Required** — blank stops the `storage` container at boot. Rotating it: change `.env`, then `docker compose up -d storage storage-init app` together |
| `STORAGE_DOMAIN` | FQDN, e.g. `storage.skillars.com` | Public hostname Traefik routes to the `storage` service (its own Let's Encrypt certificate), in production and UAT. Needs a DNS A record to the server. Must be reachable **by the browser**, not just from inside the compose network: `docker-compose.yml` sets `APP_STORAGE_ENDPOINT_URL=https://${STORAGE_DOMAIN}`, and every presigned upload/download URL is built from it |
| `BACKUP_STORAGE_TYPE` | String: `hetzner` or `s3-compatible` | **Production only.** Backup destination storage type. Use `hetzner` for Hetzner Object Storage, or `s3-compatible` for another S3-compatible provider |
| `HOS_ACCESS_KEY` | String | Hetzner Object Storage access key (if `BACKUP_STORAGE_TYPE=hetzner`). Hetzner Cloud Console → Object Storage → your bucket → Access Keys → Create access key; copy Access Key ID |
| `HOS_SECRET_KEY` | String | Hetzner Object Storage secret key (if `BACKUP_STORAGE_TYPE=hetzner`). Same creation flow as `HOS_ACCESS_KEY`; copy Secret Access Key (shown once) |
| `HOS_BUCKET` | String, e.g. `skillars-backups` | Hetzner Object Storage bucket name (if `BACKUP_STORAGE_TYPE=hetzner`). Create a private bucket in Hetzner Cloud Console → Object Storage; use the exact bucket name here |
| `HOS_ENDPOINT` | HTTPS URL, e.g. `https://s3.fsn1.hetzner.com` | Hetzner Object Storage endpoint for your datacenter region (if `BACKUP_STORAGE_TYPE=hetzner`). Examples: `https://s3.fsn1.hetzner.com` (Falkenstein), `https://s3.nbg1.hetzner.com` (Nuremberg), `https://s3.hel1.hetzner.com` (Helsinki) |
| `HOS_BACKUP_PREFIX` | String ending in `/`, e.g. `pg-backups/` | Backup key prefix for Hetzner Object Storage (if `BACKUP_STORAGE_TYPE=hetzner`). Choose a prefix to organize backups within the bucket; default `pg-backups/` |
| `HOS_VOLUME_BACKUP_PREFIX` | String ending in `/`, e.g. `volume-backups/` | Volume backup key prefix for Hetzner Object Storage (if `BACKUP_STORAGE_TYPE=hetzner`). Choose a prefix to organize file-level volume backups; default `volume-backups/` |
| `S3_BACKUP_ACCESS_KEY` | String | S3-compatible backup storage access key (if `BACKUP_STORAGE_TYPE=s3-compatible`). E.g., the provider's access key or IAM credentials |
| `S3_BACKUP_SECRET_KEY` | String | S3-compatible backup storage secret key (if `BACKUP_STORAGE_TYPE=s3-compatible`) |
| `S3_BACKUP_BUCKET` | String, e.g. `skillars-backups` | S3-compatible backup storage bucket name (if `BACKUP_STORAGE_TYPE=s3-compatible`) |
| `S3_BACKUP_ENDPOINT` | HTTPS URL, e.g. `https://s3.eu-central-003.backblazeb2.com` | S3-compatible backup storage endpoint (if `BACKUP_STORAGE_TYPE=s3-compatible`): a third-party S3-compatible provider. Never the app's own `storage` service — backups must live off this server |
| `S3_BACKUP_REGION` | String, e.g. `us-east-1` | S3-compatible backup storage region (if `BACKUP_STORAGE_TYPE=s3-compatible`). Default `us-east-1` |
| `S3_BACKUP_PREFIX` | String ending in `/`, e.g. `pg-backups/` | Backup key prefix for S3-compatible storage (if `BACKUP_STORAGE_TYPE=s3-compatible`). Default `pg-backups/` |
| `S3_BACKUP_VOLUME_PREFIX` | String ending in `/`, e.g. `volume-backups/` | Volume backup key prefix for S3-compatible storage (if `BACKUP_STORAGE_TYPE=s3-compatible`). Default `volume-backups/` |
| `BACKUP_RETENTION_DAYS` | Integer, e.g. `14` | **Tuning knob, not a secret.** How many days of PostgreSQL dumps `prune-backups.sh` keeps; default `14` (~56 dumps at the 6-hourly cadence) |
| `BACKUP_RETENTION_MIN_KEEP` | Integer, e.g. `8` | **Tuning knob, not a secret.** Newest dumps retained unconditionally regardless of age — the floor that stops a bad cutoff emptying the bucket; default `8` |
| `VOLUME_BACKUP_RETENTION_DAYS` | Integer, e.g. `14` | **Tuning knob, not a secret.** How many days of file-level volume backups `prune-backups.sh` keeps; default `14` (~14 backups at the daily cadence) |
| `VOLUME_BACKUP_RETENTION_MIN_KEEP` | Integer, e.g. `4` | **Tuning knob, not a secret.** Newest volume backups retained unconditionally regardless of age — the floor that stops a bad cutoff emptying the bucket; default `4` |

### Storage Architecture

**Object storage in production (single-node SeaweedFS):**
- Uploads (coach photos and gallery, documents, generated reports) live in the `storage` service in
  `docker-compose.yml`: SeaweedFS (`chrislusf/seaweedfs`) serving the S3 API on port 9500. MinIO is
  no longer used anywhere: its images stopped pulling anonymously on 2026-09-24 (see
  `docker-compose.uat.yml`). UAT and local run the same image.
- The app talks to it at `https://${STORAGE_DOMAIN}` (Traefik → `storage:9500`), with path-style
  addressing (`APP_STORAGE_S3_PATH_STYLE_ACCESS=true`) and static credentials from `STORAGE_ROOT_*`.
  Browsers use the same host, because presigned upload/download URLs are built from that endpoint.
  The database stores object keys only (e.g. `coach_profile/{userId}/…`), never URLs, so the host
  can change without a data migration.
- `storage-init` (one-shot, runs on every `docker compose up`) creates `APP_STORAGE_BUCKET` and sets
  its CORS policy to allow `https://${DOMAIN}`. Without CORS, browser uploads fail at the preflight.
- Data lives on the Hetzner Volume at `/opt/skillars/data/storage`. `volume-backup.sh` archives all
  of `/opt/skillars/data` except `postgres/`, so the media store is in the daily volume backup.
  Note that backup is a full archive every day: as the media library grows, so do backup time and
  Object Storage cost (×`VOLUME_BACKUP_RETENTION_DAYS`).
- Started with `-volume.max=0` (volume count sized from free disk; the default of 8 would cap the
  store at 8 GB) and `-volume.minFreeSpace=10` (read-only at 10% free disk, so a full media store
  cannot fill the volume Postgres shares). Uploads then fail until disk is added. Watch disk usage
  in Grafana.
- `deploy.yml` only recreates `app` (`up -d --no-deps app`). Start `storage` once at first-time
  setup and after changing its compose definition or `STORAGE_ROOT_*`
  (`docker compose --env-file /opt/skillars/.env up -d storage storage-init`).
- Moving to a managed S3-compatible provider later is a config change plus a bucket copy; see
  [`runbook.md`](runbook.md#moving-object-storage-to-a-managed-provider).

**Backup Storage:**
- PostgreSQL dumps and volume backups are stored off-server (never in the app's own `storage` service)
- Configure via `BACKUP_STORAGE_TYPE`:
  - `hetzner`: Use Hetzner Object Storage (HOS_* variables)
  - `s3-compatible`: Use another S3-compatible provider (S3_BACKUP_* variables)
- Backup schedule: PostgreSQL every 6 hours, volumes daily; retention: 14 days by default

### Application secrets

These were added to `.env.example` and to both compose files on 2026-09-02; before that the
application read them but no deploy supplied them. Defaults below are the application's own, from
`application.yaml`.

| Variable | Format | Default if unset | How to obtain or generate |
|---|---|---|---|
| `APP_PAYMENT_STRIPE_API_KEY` | `sk_test_…` / `sk_live_…` (or `rk_…`) | *empty* — **application refuses to start** | Stripe Dashboard → Developers → API keys. `PaymentConfig` rejects a `sk_live_`/`rk_live_` key unless the `prod` profile is active, so non-production environments cannot charge real money even by mistake |
| `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` | `whsec_…` | *empty* | Stripe Dashboard → Developers → Webhooks → your **account** endpoint ("events on your account") → Signing secret. Endpoint path is `/api/payment/webhooks/stripe`. Without it, subscription, invoice, dispute and refund webhooks fail signature verification |
| `APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET` | `whsec_…` | *empty* — Connect deliveries are rejected with 400 | Stripe Dashboard → Developers → Webhooks → your **Connect** endpoint ("events on connected accounts", events `account.updated` and `account.application.deauthorized`) → Signing secret. Endpoint path is `/api/payment/webhooks/stripe/connect`. A different secret from the account endpoint's. Without it, coach onboarding reaches `COMPLETE` only through the slower `Account.retrieve` fallback (on the OAuth return and when the coach opens payment settings) and later restrictions are missed. Locally, `stripe listen` signs both forwarded streams with one secret, so set both variables to it |
| `APP_PAYMENT_STRIPE_OAUTH_CLIENT_ID` | `ca_…` | *empty* | Stripe Dashboard → Settings → Connect → Integration. Required for coach Connect onboarding |
| `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` | `pk_test_…` / `pk_live_…` | *empty* | Stripe Dashboard → Developers → API keys. Served to the browser by `/api/payment/stripe/config`; card entry, `SetupIntent` and pack purchase are all non-functional without it |
| `APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL` | Absolute URL | relative path (`/api/payment/coaches/me/stripe/callback`) | Must be absolute in any real environment — Stripe rejects a relative `redirect_uri`. `PaymentConfig` logs a warning, it does not fail |
| `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | Base64 string decoding to ≥32 bytes | `""` under `prod` — see the profile note below | `openssl rand -base64 32`. Signs video playback URLs (HS256) |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` | String | `""` under `prod` — see the profile note below | Bunny.net Stream → your library → Webhook signing secret |
| `PLATFORM_PIN_ENCRYPTION_SECRET` | String | dev placeholder (`S3CR3TW0RD`) | `openssl rand -base64 32`. Encrypts parent-approval PINs — **rotating it invalidates every stored PIN** |
| `PLATFORM_REGISTRATION_VERIFICATION_SECRET` | String | dev placeholder; **empty under `prod`/`uat` — `RegistrationVerificationTokenService` aborts startup if unset** | `openssl rand -base64 32`. HMAC key for the phone-verification handle returned by `/verify-email` and required by `/verify-phone` and `/resend-otp`. Also the root of the phone-OTP hash key (`OtpHasher`, skillars-deferred-162), so **rotating it also invalidates in-flight phone OTPs** (10 min TTL; the user resends) as well as registration handles issued in the last 24h |
| `APP_STORAGE_BUCKET` | String, e.g. `skillars-prod` | `skillars-prod` (compose default; `docker-compose.local.yml` pins `skillars-dev`) | Bucket name. `storage-init` creates it in the SeaweedFS `storage` service on every `up`. Set it in `.env` for production; don't rely on the default |
| `APP_STORAGE_ENDPOINT_URL` | URL | **set by `docker-compose.yml`** to `https://${STORAGE_DOMAIN}`, not read from `.env` | The S3 endpoint the app signs requests and presigned URLs for, so it must be reachable **by the browser**. Overlays restate it (`docker-compose.local.yml`: `http://storage:9500`; `docker-compose.uat-hostwinds.yml`: `http://${DOMAIN}:9500`). When moving to a managed provider, restore the `.env` passthrough (runbook) |
| `APP_STORAGE_REGION` | String | `us-east-1` | S3 signing region. SeaweedFS accepts any region; keep `us-east-1` unless a managed provider needs its own |
| `APP_STORAGE_S3_ACCESS_KEY` / `APP_STORAGE_S3_SECRET_KEY` | String | **set by `docker-compose.yml`** from `STORAGE_ROOT_USER` / `STORAGE_ROOT_PASSWORD` | Static S3 credentials. Bound on every profile (`application.yaml`). If both are blank, `BlobstoreConfig` falls back to the AWS default credential chain. Production never does: the chain is shared with SES, so storage must not depend on it |
| `APP_STORAGE_S3_PATH_STYLE_ACCESS` | Boolean | `false` in `application.yaml`; **`true` from `docker-compose.yml`**; dev/uat profiles pin `true` | `true` for a single-hostname endpoint such as SeaweedFS on `STORAGE_DOMAIN`. Most managed providers (AWS S3, Hetzner Object Storage) prefer `false` (virtual-hosted `{bucket}.host`) |
| `GEMINI_API_KEY` | String | *empty* | Google AI Studio → API keys. Used by the AI narrative features |
| `GEMINI_MODE` | `developer` \| `vertex` | `developer` | Which Google endpoint serves moderation. `developer` has no DPA / EU residency and makes prod log a daily compliance warning (admin health banner, gauge `skillars_gemini_developer_api_in_prod`). `vertex` uses Vertex AI in an EU region; see the provisioning checklist for the steps |
| `GEMINI_MODEL` | String | `gemini-2.0-flash` | Model id; Vertex may require the versioned id |
| `GEMINI_VERTEX_PROJECT` / `GEMINI_VERTEX_LOCATION` | String | *empty* / `europe-west4` | Vertex AI project and region. Required when `GEMINI_MODE=vertex` |
| `GEMINI_VERTEX_CREDENTIALS_PATH` | Path | *empty* | Service-account key JSON **inside the container** (role `roles/aiplatform.user`). Required when `GEMINI_MODE=vertex`; mount it read-only, never bake it into the image |
| `ARACHNID_API_KEY` | String | *empty* | Project Arachnid (C3P) credential. **Required on prod/uat** (with `ARACHNID_ENABLED=true`) unless `ALLOW_UNSCANNED_MEDIA=true`: `DeployedMediaScanningValidator` refuses to boot otherwise. Apply to C3P early; vetting lead time is theirs |
| `ARACHNID_ENABLED` | Boolean | `false` | Turns on image abuse-media scanning. Must be `true` on prod/uat (see above) |
| `ALLOW_UNSCANNED_MEDIA` | Boolean | `false` | **Pre-launch override only.** Lets prod/uat boot without image scanning; logs ERROR and raises the `MediaScanningUnsafe` alert while set. Remove before real users upload |
| `APP_ACCESS_LOG_ENABLED` | Boolean | `true` | **Tuning knob, not a secret.** Tomcat access logging, emitted through SLF4J (loggers `skillars.access` for port 9990 and `skillars.access.management` for 8367) so entries reach Loki. Set `false` to disable both; see [`local/deployment.md`](local/deployment.md) for silencing only the management half, which is mostly healthcheck and Prometheus traffic |
| `ENVIRONMENT` | String | `prod` | Stamped onto every log line and Loki stream as the `environment` label. **uat must set `ENVIRONMENT=uat`** in `.env.uat` — the compose default (`prod`) overrides the profile |
| `APP_VERSION` | String | `unknown` | Stamped onto every log line; set from the CI build to make log/trace correlation across deploys possible |
| `APP_FRONTEND_URL` | URL | per-profile | Base URL used to build email verification links. Wrong value ⇒ users receive unusable links. **uat must set it** in `.env.uat`: `docker-compose.yml` defaults it to the production URL, which overrides `application-uat.yaml` |

> **Previously wired only on `dev` and `uat`.** `app.storage.s3.access-key`,
> `app.storage.s3.secret-key`, `app.video.playback.signing-secret`,
> `app.video.bunny.webhook-signing-secret` and `skillars.platform.pin-encryption-secret` carried
> their `${...}` placeholders in `application-dev.yaml` / `application-uat.yaml` only, so under the
> `prod` profile the corresponding variables were bound to nothing and setting them had no effect.
> They are now declared in base `application.yaml` as well, and are configurable on every profile.
> Leaving the two S3 keys blank still selects the AWS default credential chain — that check is on
> blankness, not presence, so blank preserves the previous production behaviour.

> **Email Providers (story ses-1.2):** SMTP is configured entirely via `app.email.smtp.provider-configs`
> in `application.yaml` — one entry per provider (currently `gmx`, `gmail`), round-robined by
> `MailSenderProvider`. There are no `SPRING_MAIL_*` variables any more; `spring.mail.*`
> (Spring Boot's own autoconfigured mailer, which this codebase never injected) was deleted in D9.
> `GMX_PASSWORD` and `GMAIL_PASSWORD` are the only secrets each provider entry needs — host/port/
> username are literals in `application.yaml`, not environment-configurable.
>
> **AWS SES — `APP_SES_FROM_ADDRESS` is REQUIRED on prod (story ses-1.1).** Production runs
> `app.email.transport: ses`, and `SesPropertiesValidator` aborts startup when `app.ses.from-address`
> resolves blank. `docker-compose.yml` passes `APP_SES_FROM_ADDRESS` through with **no `:-` fallback**,
> deliberately, so a missing value reaches the app blank rather than silently becoming a literal.
> Set it in prod's real `.env` **before** deploying. Also read the runbook's
> [Pre-production release gate: SES cutover](runbook.md#pre-production-release-gate-ses-cutover) before the first deploy that
> serves real users — it covers production access, IAM, the preflight, and the shared-AWS-account decision. Note that the manual deploy workflow has a
> smoke-test auto-revert, so a missing value presents as a failed smoke test and an automatic
> revert — not as a readable boot error in the deploy console.
>
> | Variable | Required | Default | Notes |
> |---|---|---|---|
> | `APP_SES_FROM_ADDRESS` | **yes, on prod** | none — blank fails boot | Verified SES sender identity |
> | `APP_SES_REGION` | no | `eu-west-1` | Blank (not unset) also fails boot |
> | `APP_SES_ACCESS_KEY` / `APP_SES_SECRET_KEY` | no | blank | Blank selects the AWS default credential chain. The chain must still resolve — an unresolvable chain fails boot under `transport=ses` |
> | `APP_SES_CONFIGURATION_SET` | no | blank | Must match `^[a-zA-Z0-9_-]{1,64}$` when set |
> | `APP_SES_REPLY_TO_ADDRESS` | no | blank | Validated as a single address when set |
> | `APP_SES_MAX_SEND_RATE_PER_SECOND` | no | `10` | Sandbox accounts cap at 1/s; `>= 10` logs a WARN at boot |
>
> **IAM: `ses:GetAccount` is required in addition to `ses:SendEmail` (story ses-1.3).** `SesHealthIndicator`
> calls `GetAccount` to report sandbox and enforcement state on `/manage/health/notification`. A principal
> that can send but cannot call `GetAccount` makes that group report DOWN with an `AccessDeniedException`
> while mail delivery itself is unaffected. Grant both actions on the SES resource.
>
> **Phase-5 ordering constraint (story ses-1.3; owner exception recorded 2026-10-24).** `SesHealthIndicator` reports UP only when
> `sendingEnabled` **and** `productionAccessEnabled` hold **and** `enforcementStatus` is readable and not
> `SHUTDOWN` — a sandboxed account is deliberately DOWN, because a sandboxed prod account cannot mail real
> users. So SES **production access must be granted before** an environment is flipped to
> `app.email.transport: ses`, or its `notification` health group reports DOWN from the first boot. **Owner
> exception (2026-10-24, source doc §9.2 D-1b):** the uat flip (`ses-1-5b`) may run sandbox-first, sending only
> to verified individual addresses; there `notification` DOWN and preflight `ready:false` are the expected shape,
> and production access remains a hard gate for prod serving real users. This does
> not affect container liveness or the deploy gate: both poll the `smoke` group (db, diskSpace, ping), which
> carries no mail contributor — see `docker-compose.yml`'s app `healthcheck` and the `health-rewrite`
> Traefik middleware.
>
> **Packaging bug, fixed 2026-09-01 (history — not a currently active gate, and predates `ses-1.1`'s
> `app.email.transport` by ten days — the config in play at the time was the older, since-replaced
> `app.ses.enabled: true`).** Until that date, any profile with SES enabled that way crashed the
> application outright: `pom.xml` declared `httpclient5` at test scope, stripping it from the shipped jar
> even though the
> AWS SDK's `apache5-client` needs it at runtime, producing
> `NoClassDefFoundError: org/apache/hc/client5/http/io/HttpClientConnectionManager`. Fixed by
> moving that dependency to compile scope.

> **The three `APP_BOOTSTRAP_ADMIN_*` variables are the only entries in this table that are meant to
> be removed again.** Every other secret here is permanent; these exist to create one account, once.
>
> There is no admin registration endpoint and no migration seeds an admin *user*, so
> `AdminBootstrapRunner` is the only supported way to obtain one. It is **inert unless
> `APP_BOOTSTRAP_ADMIN_EMAIL` and `APP_BOOTSTRAP_ADMIN_PASSWORD` are both non-blank**, so leaving all
> three unset (the `.env.example` default) changes nothing on an environment that already has its
> admin.
>
> Lifecycle: set all three → deploy → confirm the account was created → **remove
> `APP_BOOTSTRAP_ADMIN_PASSWORD` and redeploy**, so a long-running container does not carry a live
> credential in its environment. Confirm with:
> ```bash
> docker compose logs app | grep admin_bootstrap
> # ... "Admin bootstrap created the first administrator" ... authority=ROLE_ADMIN
> ```
> Two behaviours worth knowing before you plan around this:
> - **Re-running is safe.** If the account already exists the runner logs `skip_existing` and returns
>   — it will not duplicate or fail. Removing the password is about exposure, not correctness.
> - **It never elevates an existing user.** If the email is already taken by a coach, parent or
>   player, the runner skips rather than granting `ROLE_ADMIN` to that row. A typo therefore produces
>   a silent skip with only an INFO line to explain it — check the log rather than assuming success.
>
> Setting email and password but leaving `APP_BOOTSTRAP_ADMIN_PHONE` blank makes the application
> **refuse to start**, deliberately, so a half-configured bootstrap fails loudly instead of writing a
> broken row. Full procedure: [`uat-deployment.md`](uat-deployment.md) Step 8.

> **After placing the updated `.env`**, run the backup cron installer:
> ```bash
> bash /opt/skillars/app/deploy/backup/install-crons.sh
> ```
> This is required once per Node. Re-running is safe (idempotent).

> **First deploy — manual image build (before CI pipeline exists):**
> Log in to GHCR, build the image, and push it so `APP_IMAGE` can be pulled:
> ```bash
> echo $GHCR_PAT | docker login ghcr.io -u <github-username> --password-stdin
> docker build -t ghcr.io/tenjohokwen/skillars:sha-$(git rev-parse --short HEAD) .
> docker push ghcr.io/tenjohokwen/skillars:sha-$(git rev-parse --short HEAD)
> # Then set APP_IMAGE=ghcr.io/tenjohokwen/skillars:sha-$(git rev-parse --short HEAD) in .env
> ```
> Run these commands from the root of your local repository clone before Step 6 of [`first-time-setup.md`](first-time-setup.md).

> **Not settable from `.env`, deliberately omitted from the table and from `.env.example`:**
> `LOKI_URL`, `LOKI_ENABLED` and `MANAGEMENT_OTLP_TRACING_ENDPOINT` are written as hardcoded
> literals on the `app` service (they point at Docker service names on the shared
> `skillars-observability` network and the app cannot reach those services by any other address),
> and `SPRING_DATASOURCE_URL` is derived by compose from `POSTGRES_DB`. Setting any of the four in
> `.env` has no effect. Change them by editing the compose file — which is what
> `docker-compose.uat-hostwinds.yml` does to turn Loki off where no `loki` container exists.

> **At least one Grafana alert channel is required.** `deploy/provision.sh` **refuses to proceed**
> (`exit 1`) if `.env` is present and **both** `GF_ALERT_NOTIFY_EMAIL` and `GF_SLACK_WEBHOOK_URL` are
> blank — Grafana still provisions the `notify-ops` contact point, so with neither set every alert
> routes to a dead address. Set at least one. If you set `GF_ALERT_NOTIFY_EMAIL` you must **also** set
> `GF_SMTP_ENABLED=true` and the `GF_SMTP_*` block, or email alerts fail silently (`provision.sh`
> warns about this but does not stop). Setting only one of the two channels is allowed and safe:
> `provision.sh` rewrites the marked `contactPoints:` region of `deploy/lgtm/grafana-alerts.yml` to
> contain **only** the configured channel's receiver, so there is no empty second receiver to
> silently no-op. (The `${GF_*}` placeholders are kept in the file — Grafana expands them at load;
> the secrets are never written there.)

### Optional application variables

Passed through `docker-compose.yml` with defaults that mirror `application.yaml` (added by
`fix/prod-readiness-gaps`). Leave them unset unless you need a different value.

| Variable | Default if unset | What it does |
|---|---|---|
| `ALLOWED_CLIENTS` | blank (no machine client) | Comma-separated `X-Client-Id` values accepted from non-browser API clients (`allowed.clients`). A machine client skips the browser fingerprint-cookie check, so use long random ids and never the dev placeholders (`myClientId`, ...). UAT testers who curl the admin API (`uat-deployment.md` 8.3) need one listed here |
| `APP_BOOTSTRAP_ADMIN_FIRST_NAME` / `APP_BOOTSTRAP_ADMIN_LAST_NAME` | `Platform` / `Administrator` | Display name of the admin created by `AdminBootstrapRunner` |
| `APP_PAYMENT_STRIPE_CALLBACK_SUCCESS_URL` | `/coach/payment-settings` | SPA route the Stripe OAuth callback redirects the coach to. Relative to `APP_FRONTEND_URL` |
| `APP_STORAGE_BACKUP_ENDPOINT_URL`, `_BUCKET`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY` | blank, `us-east-1` for the region | Optional second S3 bucket that uploads are copied to (`app.storage.backup.*`). All blank = off |
| `ARACHNID_API_BASE_URL` | `https://api.c3p.ca` | Project Arachnid endpoint. Used only with `features.toggles.arachnid-enabled` |
| `VIDEOINTEL_PROJECT_ID`, `VIDEOINTEL_CREDENTIALS_PATH` | blank | Google Video Intelligence project and credentials file path (inside the container). Used only with `features.toggles.videointel-enabled` |
| `APP_STORAGE_LOCAL_BASE_DIR` | `/tmp/skillars-storage` | Only for `app.storage.provider=local`; not passed through compose, since PROD and UAT run the S3 backend |

`PLATFORM_NOTIFICATION_EMAIL` was removed: nothing read it.

---

## GitHub Actions Secrets — Configure in Repository Settings → Secrets and Variables → Actions

These secrets are required for the CI/CD pipeline (Epic 2). The foundational set is listed here;
additional secrets (Slack webhook, alert routing) will be defined in Epic 2 stories.

| Secret name | Format | How to obtain or generate |
|---|---|---|
| `GHCR_PAT` | GitHub Personal Access Token | GitHub → Settings → Developer settings → Personal access tokens → New token; grant `read:packages` scope; used **only** by `deploy.yml` for `docker login` on the Node. `ci.yml` no longer needs it — it pushes to GHCR with the built-in `GITHUB_TOKEN`. See [github-build.md](baseline/github-build.md) |
| `SSH_DEPLOY_KEY` | PEM private key (ed25519 recommended) | Generate: `ssh-keygen -t ed25519 -C deploy@skillars-prod`; add the public key to `/root/.ssh/authorized_keys` on the Node; paste the private key here |
| `SSH_HOST` | IP address | The Node's public IP address; used by the deploy workflow to SSH to the Node |
| `SSH_USER` | String, e.g. `root` | SSH username on the Node (default `root`) |
| `SSH_KNOWN_HOST` | Known hosts entries (multi-line) | Run `ssh-keyscan -H <node-ip>` from a trusted machine after provisioning — it prints one line per host-key algorithm, not a single line. Paste **all** lines exactly as printed (do not truncate to one); used to verify the Node host key instead of trusting on first use |
| `SLACK_WEBHOOK_URL` | HTTPS URL | Slack → Your workspace → Apps → Incoming Webhooks → Add to Slack → select channel → copy Webhook URL |
| `SMTP_HOST` | Hostname | Your SMTP provider (e.g. `smtp.gmail.com`, `smtp.sendgrid.net`) |
| `SMTP_PORT` | Integer | From your SMTP provider — `587` for STARTTLS, `465` for SSL/TLS |
| `SMTP_USERNAME` | Email address | Your SMTP username or sending address |
| `SMTP_PASSWORD` | String | App password or SMTP credential from your email provider |
| `NOTIFY_EMAIL` | Email address | Address to receive deploy and revert notifications |

---

## Notes on Secret Generation

Quick reference for generating strong secrets locally:

```bash
# 32 bytes of entropy (~44 base64 characters) — for POSTGRES_PASSWORD:
openssl rand -base64 32

# 24 bytes of entropy (~32 base64 characters) — for GF_SECURITY_ADMIN_PASSWORD
# and APP_BOOTSTRAP_ADMIN_PASSWORD:
openssl rand -base64 24
# Record APP_BOOTSTRAP_ADMIN_PASSWORD in your password manager BEFORE deploying — it is stored
# bcrypt-hashed and never logged, so it cannot be recovered from the running system afterwards.

# ed25519 SSH deploy key pair (GitHub Actions SSH_DEPLOY_KEY):
ssh-keygen -t ed25519 -C deploy@skillars-prod -f ~/.ssh/skillars_deploy
# Private key → GitHub Actions secret SSH_DEPLOY_KEY
# Public key  → append to /root/.ssh/authorized_keys on the Node
```

See [`docs/deployment/first-time-setup.md`](first-time-setup.md) for the full deployment walkthrough.

---

## Secret Rotation

There is **no scheduled rotation cadence** today. The procedures below are for a
compromise-triggered or policy-triggered rotation of a single secret.

### `POSTGRES_PASSWORD`

1. Change the role password inside the running postgres container. Prefer `psql`'s interactive
   `\password` meta-command — it prompts for the value and sends a pre-hashed `ALTER ROLE`, so the
   plaintext never reaches shell history or the Postgres statement log (which `ALTER ROLE … WITH
   PASSWORD '<literal>'` does when `log_statement`/`log_min_duration_statement` are on), and a `'`
   in the password is handled for you:
   ```bash
   docker compose -f /opt/skillars/app/docker-compose.yml exec -it postgres \
     psql -U "<POSTGRES_USER>" -d postgres -c '\password <POSTGRES_USER>'
   ```
   Only if a non-interactive path is unavoidable, `ALTER ROLE "<POSTGRES_USER>" WITH PASSWORD
   '<NEW_PASSWORD>'` works — but clear the shell history line afterwards and be aware of the log
   exposure above.
2. Update `POSTGRES_PASSWORD` in `/opt/skillars/.env`.
3. Recreate every consumer so it picks up the new value:
   ```bash
   cd /opt/skillars/app && docker compose up -d
   ```
   `app` reads it via `SPRING_DATASOURCE_*`; `pg-backup.sh` and `restore-from-dump.sh` read
   `POSTGRES_PASSWORD` from the same `.env`, so no separate update is needed for the backup cron.
4. Confirm: `docker compose logs app --tail=50` shows a clean datasource start, and
   `bash /opt/skillars/app/deploy/backup/pg-backup.sh` completes with `Upload verified`.

### Redis password

`REDIS_PASSWORD` is the `requirepass` of the `redis` container and, through `SPRING_DATA_REDIS_PASSWORD`, the
password the app connects with. `redis` is reachable only from `app` (network `skillars-data`, `internal: true`).
Generate with `openssl rand -base64 32`; keep spaces and quotes out of it (it is written into a redis config line).

**Rotation is "set a new value and restart."** Edit `REDIS_PASSWORD` in `/opt/skillars/.env`, then
`cd /opt/skillars/app && docker compose up -d redis app`. The AOF holds data, not the password, so nothing is lost.
Verify: `docker compose exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" ping'` prints `PONG`.

### JWT signing key

`JWT_SIGNING_KEY` (property `app.security.jwt.signing-key`) is the HMAC key that signs the 15-minute
access tokens. Base64, at least 64 bytes decoded (HS512). Generate with `openssl rand -base64 64`.
`prod` and `uat` refuse to boot if it is blank, not Base64, shorter than 64 bytes, or one of the
public dev/test values (`DeployedSecretsValidator`); `JwtConfiguration` also refuses a blank or
malformed value in any profile. `dev` and the tests carry a clearly-labelled dev-only default.

**Rotation is "set a new value and restart."** Edit `JWT_SIGNING_KEY` in `/opt/skillars/.env`, then
`cd /opt/skillars/app && docker compose up -d app`. Every access token signed with the old key is
rejected on the next request with a 401, which also revokes that user's refresh token, and the SPA
sends them to `/login` (it does not call `/api/auth/refresh` on a 401). Expect every logged-in user to
sign in again once; do it in a quiet window.
Restart every instance together (single node today), or tokens bounce between old and new keys.

The legacy `sec` row `(v1, jot)` is unused. It can be dropped after the first rotation to a freshly
generated key; do not reuse its value (it may exist in backups).

### `GF_SECURITY_ADMIN_PASSWORD`

1. Update `GF_SECURITY_ADMIN_PASSWORD` in `/opt/skillars/.env`.
2. Recreate the Grafana container: `cd /opt/skillars/app && docker compose up -d grafana`.
   Grafana persists the admin user in `/opt/skillars/data/grafana`, so the env var only takes effect
   on a container **recreate**, and a password that was already changed from within the Grafana UI is
   **not** overridden by the env var (documented Grafana behaviour).
3. If the UI password was changed and is now lost, reset it from inside the container:
   ```bash
   docker compose exec grafana grafana-cli admin reset-admin-password '<NEW_PASSWORD>'
   ```

---

## Accepted credential-exposure surface

**Decision (skillars-deferred-107 AC6, [DECIDED 2026-09-10]): won't fix.**

**Mechanism (corrected by the skillars-deferred-107 code review).** `deploy/backup/env-guard.sh`
sources `/opt/skillars/.env` with a bare `.` and **no `set -a`**, so the credentials become
*unexported shell variables* in the sourcing script — they do **not** appear in that script's own
`/proc/<pid>/environ` (only exported variables do). The genuine exposures are narrower:

- **`PGPASSWORD="${POSTGRES_PASSWORD}" docker exec -e PGPASSWORD "$CID" …`** — the short-lived
  `docker exec` **child** process carries `PGPASSWORD` in its environment for the few seconds it
  runs; that child's `/proc/<pid>/environ` is readable by root (the child runs as root).
- **`docker compose --env-file /opt/skillars/.env`** — passes values into the **postgres / app
  container** environments. Those containers legitimately need the DB password; this is by design and
  is the same surface any Compose deployment has.

This is accepted, not fixed, because:

- Everything above is readable only by **root**, and root already owns `/opt/skillars/.env` itself
  (`root:root`, mode `0600`) — it exposes nothing root cannot already read.
- The VPS is **single-tenant**. The only non-root operator account is the unprivileged `deploy`
  user, which cannot read `.env` (`provision.sh` places it outside the checkout, `root:root 0600`)
  and runs no script that sources it — it runs only `git pull` + `docker compose`.
- `PGPASSFILE` / stdin-piping was **evaluated and rejected**: it would rewrite every
  `psql`/`pg_dump`/`pg_restore` invocation across the three backup/restore scripts and change nothing
  about *who* can read the secret (still root, still the same `.env`, still the same container envs).
- The `ps aux` argv-exposure half of the original finding **was** fixed
  (`skillars-deferred-94` AC1 — env-var inheritance: `PGPASSWORD="…" docker exec -e PGPASSWORD "$CID"`).

**Revisit** only if the VPS ever becomes multi-tenant, or if a non-root account is granted the
ability to run (or `ptrace`) a process that has sourced `.env` or an in-flight `docker exec` child.

---

## Stale `HCLOUD_TOKEN` / `HETZNER_VOLUME_ID` on nodes provisioned before `skillars-uat-6`

`skillars-uat-6` AC8 (2026-08-13) removed `HCLOUD_TOKEN` and `HETZNER_VOLUME_ID` from
`.env.example` and this document. Neither var is part of the current template. However, any node
that was `provision.sh`'d **before** that change still has its original `/opt/skillars/.env` on
disk, which may still carry the old values — `provision.sh` never rewrites an existing `.env`.

**Why this is inert, precisely.** `deploy/provision.sh:591` does check `HETZNER_VOLUME_ID`:

```bash
if [ -n "${HETZNER_VOLUME_ID:-}" ]; then
```

and will hard-fail (`:597-600`) if that variable is set to a value that doesn't resolve to an
attached Volume. But this is a check of the **process environment**, not a file read —
`provision.sh` never sources `/opt/skillars/.env` wholesale, and never reads `HETZNER_VOLUME_ID`
out of it directly either. A stale value sitting inertly inside the `.env` file's text is
invisible to a plain `./provision.sh` re-run; it only becomes a problem if an operator
**manually** `export`s the stale value (or does `set -a; . .env`) into their own interactive shell
before re-running `provision.sh` in that same shell.

`deploy/backup/env-guard.sh`'s `require_env_vars` also sources `.env`, but with a bare `.` and no
`set -a` (see "Accepted credential-exposure surface" above) — it holds the stale value as an
unexported shell variable that does not escape that script's own process, so it is **not** a
vector for this risk. `deploy/firewall/apply-firewall.sh` also requires `HCLOUD_TOKEN`, but its own
header scopes that check to the operator's local machine, not the server's `.env` — irrelevant here.

**If you encounter this:** a stale value in a provisioned node's `/opt/skillars/.env` is harmless
in the sense that nothing on the server reads it passively, and there is no automated remediation.
But `HCLOUD_TOKEN` is a Hetzner Cloud API token with infrastructure-level privilege and no expiry —
the analysis above only answers "can `provision.sh` misbehave because of this?", not "is this token
still live and usable against the Hetzner API?". Revoke the token at the Hetzner Cloud console
first, then delete the two lines from the file (or leave the now-dead lines in place — either is
fine once the token itself is revoked).
