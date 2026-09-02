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
>   (`SPRING_MAIL_HOST` → `mail.gmx.net`, `APP_STORAGE_BUCKET` → `skillars-dev`, and so on) rather
>   than being blank. **This includes profile defaults.** Adding a variable to
>   `docker-compose.yml` disables any `${VAR:some-default}` fallback for it in
>   `application-dev.yaml` / `application-uat.yaml`, so a change aimed at production can break
>   local or UAT startup. `docker-compose.local.yml` restates the dev values for exactly this
>   reason.

> **JWT signing key is not an operator-supplied secret.** There is no `JWT_SECRET` (or equivalent) `.env`
> variable — the app never reads one. The real JWT signing key is a 256-byte value, auto-generated on first
> boot and stored Jasypt-encrypted in the database (`sec.secret` table, via `SecretService.createSecret` /
> `JwtSecretService`). Nothing to configure here.

| Variable | Format | How to obtain or generate |
|---|---|---|
| `APP_IMAGE` | `ghcr.io/tenjohokwen/skillars:sha-<commit>` | Produced by the CI pipeline after push to `master`. For the very first deploy (before CI is set up), build and push manually — see commands below the table |
| `DOMAIN` | FQDN, e.g. `api.example.com` | Your registered domain; must have an A record pointing to the Node IP before first deploy |
| `LETSENCRYPT_EMAIL` | Email address | Your email address; used by Let's Encrypt for certificate expiry notifications |
| `POSTGRES_DB` | Alphanumeric string, e.g. `skillars` | Choose a database name; default `skillars` |
| `POSTGRES_USER` | Alphanumeric string, e.g. `skillars` | Choose a database username; default `skillars` |
| `POSTGRES_PASSWORD` | 32+ character random string | `openssl rand -base64 32` |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Email address | **Temporary — see the callout below.** The login for the platform's first administrator. Choose an address that does not already belong to a coach, parent or player account |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | 24+ character random string | **Temporary — see the callout below.** `openssl rand -base64 24`. Stored bcrypt-hashed; never logged. Record it in your password manager before the first deploy — it cannot be recovered from the running system |
| `APP_BOOTSTRAP_ADMIN_PHONE` | E.164 phone number, e.g. `+491700000000` | **Temporary — see the callout below.** Required whenever the two above are set. `main."user".phone` carries a `UNIQUE` constraint, so this cannot be a shared placeholder — a second admin bootstrapped on the same database needs a different number |
| `SPRING_MAIL_HOST` | SMTP hostname, e.g. `smtp.gmail.com` | From your email provider (e.g. `smtp.gmail.com`, `smtp.sendgrid.net`) |
| `SPRING_MAIL_PORT` | Integer, e.g. `587` | From your email provider — 587 for STARTTLS, 465 for SSL/TLS |
| `SPRING_MAIL_USERNAME` | Email address | Your SMTP username or sending address |
| `SPRING_MAIL_PASSWORD` | String | App password or SMTP credential from your email provider |
| `GMX_PASSWORD` | String | Password for the `gmx` entry in `email.providerConfigs` (application.yaml keeps its own provider list, separate from `spring.mail.*`). Referenced with **no default**, so before the compose passthrough was fixed an unset value aborted startup on the `prod` profile |
| `GMAIL_PASSWORD` | String | As above, for the `gmail` provider entry |
| `MANAGEMENT_HEALTH_MAIL_ENABLED` | Boolean | **Tuning knob, not a secret.** Actuator's mail health indicator opens a real SMTP connection and authenticates. Set `false` wherever the mail credentials are placeholders, or `/manage/health` reports DOWN and the container never becomes healthy. Consumed by the UAT and local compose files |
| `BUNNY_API_KEY` | Hex string | Passed to the app as `APP_VIDEO_BUNNY_API_KEY`. Bunny.net Dashboard → Account → API |
| `BUNNY_LIBRARY_ID` | Integer | Passed to the app as `APP_VIDEO_BUNNY_LIBRARY_ID`. Bunny.net Dashboard → Stream → Your Library → Library ID |
| `BUNNY_CDN_HOSTNAME` | Hostname, e.g. `your-library.b-cdn.net` | Passed to the app as `APP_VIDEO_BUNNY_CDN_HOSTNAME`. Bunny.net Dashboard → Stream → Your Library → Pull Zone hostname |
| `MONITORING_DOMAIN` | FQDN, e.g. `monitoring.api.example.com` | Subdomain you configured in DNS (Step 2 of the setup guide); used by Grafana |
| `GF_SECURITY_ADMIN_USER` | Alphanumeric string, e.g. `admin` | Choose a Grafana admin username |
| `GF_SECURITY_ADMIN_PASSWORD` | 24+ character random string | `openssl rand -base64 24` |
| `GF_SMTP_ENABLED` | Boolean | `true` to enable email alerting from Grafana; `false` to disable |
| `GF_SMTP_HOST` | `hostname:port` | SMTP server with port; e.g. `smtp.gmail.com:587`; can use same provider as `SPRING_MAIL_HOST` |
| `GF_SMTP_USER` | Email address | SMTP username for Grafana's outgoing email |
| `GF_SMTP_PASSWORD` | String | App password or SMTP credential for Grafana's SMTP user |
| `GF_SMTP_FROM_ADDRESS` | Email address | FROM address on Grafana alert emails |
| `GF_SMTP_FROM_NAME` | String | Display name on Grafana alert emails; default `Skillars Alerts` |
| `GF_SMTP_STARTTLS_POLICY` | String | `MandatoryStartTLS` for port 587 (recommended); `OpportunisticStartTLS` for flexible servers; `NoStartTLS` for unencrypted relay only — port 465 (SMTPS/implicit TLS) is not supported via this setting, use port 587 |
| `GF_ALERT_NOTIFY_EMAIL` | Email address | Recipient for all Grafana-routed alerts |
| `GF_SLACK_WEBHOOK_URL` | HTTPS URL | Slack → Apps → Incoming Webhooks → Add to Slack → select channel → copy URL |
| `MINIO_ROOT_USER` | String | MinIO admin username (production and UAT). Default `minioadmin` |
| `MINIO_ROOT_PASSWORD` | 24+ character random string | MinIO admin password (production and UAT). `openssl rand -base64 24` |
| `STORAGE_DOMAIN` | FQDN, e.g. `storage.skillars.com` | Public hostname Traefik routes to MinIO in production (or test domains in UAT). Must be reachable **by the browser**, not just from inside the compose network — presigned upload URLs are built from it |
| `BACKUP_STORAGE_TYPE` | String: `hetzner` or `s3-compatible` | **Production only.** Backup destination storage type. Use `hetzner` for Hetzner Object Storage, or `s3-compatible` for MinIO or other S3-compatible storage |
| `HOS_ACCESS_KEY` | String | Hetzner Object Storage access key (if `BACKUP_STORAGE_TYPE=hetzner`). Hetzner Cloud Console → Object Storage → your bucket → Access Keys → Create access key; copy Access Key ID |
| `HOS_SECRET_KEY` | String | Hetzner Object Storage secret key (if `BACKUP_STORAGE_TYPE=hetzner`). Same creation flow as `HOS_ACCESS_KEY`; copy Secret Access Key (shown once) |
| `HOS_BUCKET` | String, e.g. `skillars-backups` | Hetzner Object Storage bucket name (if `BACKUP_STORAGE_TYPE=hetzner`). Create a private bucket in Hetzner Cloud Console → Object Storage; use the exact bucket name here |
| `HOS_ENDPOINT` | HTTPS URL, e.g. `https://s3.fsn1.hetzner.com` | Hetzner Object Storage endpoint for your datacenter region (if `BACKUP_STORAGE_TYPE=hetzner`). Examples: `https://s3.fsn1.hetzner.com` (Falkenstein), `https://s3.nbg1.hetzner.com` (Nuremberg), `https://s3.hel1.hetzner.com` (Helsinki) |
| `HOS_BACKUP_PREFIX` | String ending in `/`, e.g. `pg-backups/` | Backup key prefix for Hetzner Object Storage (if `BACKUP_STORAGE_TYPE=hetzner`). Choose a prefix to organize backups within the bucket; default `pg-backups/` |
| `HOS_VOLUME_BACKUP_PREFIX` | String ending in `/`, e.g. `volume-backups/` | Volume backup key prefix for Hetzner Object Storage (if `BACKUP_STORAGE_TYPE=hetzner`). Choose a prefix to organize file-level volume backups; default `volume-backups/` |
| `S3_BACKUP_ACCESS_KEY` | String | S3-compatible backup storage access key (if `BACKUP_STORAGE_TYPE=s3-compatible`). E.g., MinIO root user or IAM credentials |
| `S3_BACKUP_SECRET_KEY` | String | S3-compatible backup storage secret key (if `BACKUP_STORAGE_TYPE=s3-compatible`) |
| `S3_BACKUP_BUCKET` | String, e.g. `skillars-backups` | S3-compatible backup storage bucket name (if `BACKUP_STORAGE_TYPE=s3-compatible`) |
| `S3_BACKUP_ENDPOINT` | HTTPS URL, e.g. `https://minio.skillars.com:9000` | S3-compatible backup storage endpoint (if `BACKUP_STORAGE_TYPE=s3-compatible`). E.g., MinIO service URL or third-party S3-compatible provider |
| `S3_BACKUP_REGION` | String, e.g. `us-east-1` | S3-compatible backup storage region (if `BACKUP_STORAGE_TYPE=s3-compatible`). Default `us-east-1` |
| `S3_BACKUP_PREFIX` | String ending in `/`, e.g. `pg-backups/` | Backup key prefix for S3-compatible storage (if `BACKUP_STORAGE_TYPE=s3-compatible`). Default `pg-backups/` |
| `S3_BACKUP_VOLUME_PREFIX` | String ending in `/`, e.g. `volume-backups/` | Volume backup key prefix for S3-compatible storage (if `BACKUP_STORAGE_TYPE=s3-compatible`). Default `volume-backups/` |
| `BACKUP_RETENTION_DAYS` | Integer, e.g. `14` | **Tuning knob, not a secret.** How many days of PostgreSQL dumps `prune-backups.sh` keeps; default `14` (~56 dumps at the 6-hourly cadence) |
| `BACKUP_RETENTION_MIN_KEEP` | Integer, e.g. `8` | **Tuning knob, not a secret.** Newest dumps retained unconditionally regardless of age — the floor that stops a bad cutoff emptying the bucket; default `8` |
| `VOLUME_BACKUP_RETENTION_DAYS` | Integer, e.g. `14` | **Tuning knob, not a secret.** How many days of file-level volume backups `prune-backups.sh` keeps; default `14` (~14 backups at the daily cadence) |
| `VOLUME_BACKUP_RETENTION_MIN_KEEP` | Integer, e.g. `4` | **Tuning knob, not a secret.** Newest volume backups retained unconditionally regardless of age — the floor that stops a bad cutoff emptying the bucket; default `4` |

### Storage Architecture

**MinIO in Production:**
- MinIO runs as a container in production for object storage (videos, images, documents)
- The app communicates with MinIO via `APP_STORAGE_ENDPOINT_URL` (internal: `http://minio:9000`)
- Clients (browsers) receive presigned URLs for direct upload/download, using a public endpoint (e.g., `https://storage.skillars.com`)
- MinIO data persists on the volume (`/opt/skillars/data/minio`) and is backed up by `volume-backup.sh`

**Backup Storage:**
- PostgreSQL dumps and volume backups are stored off-server (not in MinIO or S3)
- Configure via `BACKUP_STORAGE_TYPE`:
  - `hetzner`: Use Hetzner Object Storage (HOS_* variables)
  - `s3-compatible`: Use MinIO or other S3-compatible storage (S3_BACKUP_* variables)
- Backup schedule: PostgreSQL every 6 hours, volumes daily; retention: 14 days by default

### Application secrets

These were added to `.env.example` and to both compose files on 2026-09-02; before that the
application read them but no deploy supplied them. Defaults below are the application's own, from
`application.yaml`.

| Variable | Format | Default if unset | How to obtain or generate |
|---|---|---|---|
| `APP_PAYMENT_STRIPE_API_KEY` | `sk_test_…` / `sk_live_…` (or `rk_…`) | *empty* — **application refuses to start** | Stripe Dashboard → Developers → API keys. `PaymentConfig` rejects a `sk_live_`/`rk_live_` key unless the `prod` profile is active, so non-production environments cannot charge real money even by mistake |
| `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` | `whsec_…` | *empty* | Stripe Dashboard → Developers → Webhooks → your endpoint → Signing secret. Endpoint path is `/api/payment/webhooks/stripe`. Without it, webhook signature verification cannot succeed |
| `APP_PAYMENT_STRIPE_OAUTH_CLIENT_ID` | `ca_…` | *empty* | Stripe Dashboard → Settings → Connect → Integration. Required for coach Connect onboarding |
| `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` | `pk_test_…` / `pk_live_…` | *empty* | Stripe Dashboard → Developers → API keys. Served to the browser by `/api/payment/stripe/config`; card entry, `SetupIntent` and pack purchase are all non-functional without it |
| `APP_PAYMENT_STRIPE_OAUTH_CALLBACK_URL` | Absolute URL | relative path (`/api/payment/coaches/me/stripe/callback`) | Must be absolute in any real environment — Stripe rejects a relative `redirect_uri`. `PaymentConfig` logs a warning, it does not fail |
| `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | Base64 string decoding to ≥32 bytes | `""` under `prod` — see the profile note below | `openssl rand -base64 32`. Signs video playback URLs (HS256) |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` | String | `""` under `prod` — see the profile note below | Bunny.net Stream → your library → Webhook signing secret |
| `PLATFORM_PIN_ENCRYPTION_SECRET` | String | dev placeholder (`S3CR3TW0RD`) | `openssl rand -base64 32`. Encrypts parent-approval PINs — **rotating it invalidates every stored PIN** |
| `APP_STORAGE_BUCKET` | String | `skillars-dev` | S3 bucket name (AWS S3 or MinIO). MinIO runs as a container in production for object storage (videos, images, documents) |
| `APP_STORAGE_ENDPOINT_URL` | URL | `http://localhost:9000` | S3-compatible endpoint URL. Examples: AWS S3 (`https://s3.amazonaws.com`), MinIO (`http://minio:9000` from inside container; `https://storage.skillars.com` from browser). Presigned upload URLs are built from this, so it must be reachable **by the browser** — use an HTTPS URL with a registered domain or public MinIO service hostname |
| `APP_STORAGE_REGION` | String | `us-east-1` | S3 region. For MinIO, default to `us-east-1` |
| `APP_STORAGE_S3_ACCESS_KEY` / `APP_STORAGE_S3_SECRET_KEY` | String | **not bound under `prod`** — see the profile note below | S3 credentials. For MinIO in production, use MinIO's root user (or IAM-equivalent). Leave blank to use AWS default credential chain (instance profile / `~/.aws`), which works only for AWS S3 |
| `GEMINI_API_KEY` | String | *empty* | Google AI Studio → API keys. Used by the AI narrative features |
| `ARACHNID_API_KEY` | String | *empty* | Project Arachnid (C3P) credential. Only needed when `features.toggles.arachnid-enabled` is on |
| `APP_ACCESS_LOG_ENABLED` | Boolean | `true` | **Tuning knob, not a secret.** Tomcat access logging, emitted through SLF4J (loggers `skillars.access` for port 9990 and `skillars.access.management` for 8367) so entries reach Loki. Set `false` to disable both; see [`local-deployment.md`](local-deployment.md) for silencing only the management half, which is mostly healthcheck and Prometheus traffic |
| `ENVIRONMENT` | String | `prod` | Stamped onto every log line and Loki stream as the `environment` label |
| `APP_VERSION` | String | `unknown` | Stamped onto every log line; set from the CI build to make log/trace correlation across deploys possible |
| `APP_FRONTEND_URL` | URL | per-profile | Base URL used to build email verification links. Wrong value ⇒ users receive unusable links |

> **Previously wired only on `dev` and `uat`.** `app.storage.s3.access-key`,
> `app.storage.s3.secret-key`, `app.video.playback.signing-secret`,
> `app.video.bunny.webhook-signing-secret` and `skillars.platform.pin-encryption-secret` carried
> their `${...}` placeholders in `application-dev.yaml` / `application-uat.yaml` only, so under the
> `prod` profile the corresponding variables were bound to nothing and setting them had no effect.
> They are now declared in base `application.yaml` as well, and are configurable on every profile.
> Leaving the two S3 keys blank still selects the AWS default credential chain — that check is on
> blankness, not presence, so blank preserves the previous production behaviour.

> **Email Providers:** The application uses SMTP for email via the `SPRING_MAIL_*` variables (the standard path).
> Configure `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, and `SPRING_MAIL_PASSWORD`
> for your SMTP provider (Gmail, SendGrid, GMX, etc.). The `GMX_PASSWORD` and `GMAIL_PASSWORD` variables
> are provider-specific overrides in `application.yaml` for multi-tenant mail routing.
>
> **AWS SES (legacy):** `application-prod.yaml` sets `app.ses.enabled: true`, so a production boot constructs
> a real `SesV2Client` (region defaults to `eu-west-1` via `app.ses.region`). Credentials come from
> the AWS SDK default provider chain — environment, instance profile, or `~/.aws` — none of which
> `docker-compose.yml` supplies. **Do not use SES unless AWS credentials are configured via IAM roles on the Node.**
> If AWS SES is not needed, set `app.ses.enabled: false` in `application.yaml`. `uat` and `dev` set it to `false`
> and use `NoOpSesEmailService`, which logs the subject and drops the message.
>
> Until 2026-09-01 this bean crashed the application outright on any profile with SES enabled:
> `pom.xml` declared `httpclient5` at test scope, stripping it from the shipped jar even though the
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
> bash /opt/skillars/deploy/backup/install-crons.sh
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
   docker compose -f /opt/skillars/docker-compose.yml exec -it postgres \
     psql -U "<POSTGRES_USER>" -d postgres -c '\password <POSTGRES_USER>'
   ```
   Only if a non-interactive path is unavoidable, `ALTER ROLE "<POSTGRES_USER>" WITH PASSWORD
   '<NEW_PASSWORD>'` works — but clear the shell history line afterwards and be aware of the log
   exposure above.
2. Update `POSTGRES_PASSWORD` in `/opt/skillars/.env`.
3. Recreate every consumer so it picks up the new value:
   ```bash
   cd /opt/skillars && docker compose up -d
   ```
   `app` reads it via `SPRING_DATASOURCE_*`; `pg-backup.sh` and `restore-from-dump.sh` read
   `POSTGRES_PASSWORD` from the same `.env`, so no separate update is needed for the backup cron.
4. Confirm: `docker compose logs app --tail=50` shows a clean datasource start, and
   `bash /opt/skillars/deploy/backup/pg-backup.sh` completes with `Upload verified`.

### JWT signing key

**Rotation is not "restart the app".** The JWT signing key is **not** an environment variable — it
is a Jasypt-encrypted row in the `sec` table keyed by `(version, busId)` =
(`JWT_VERSION` = `v1`, `JWT_BUS_NAME` = `jot`), created once per environment and cached for the life
of each process in `JwtSecretService`'s `volatile Secret` field. `Secret`'s columns are
`updatable = false` and `SecretService` exposes only `createInactiveSecret` / `createActiveSecret` /
`fetchSecret` / `fetchLatestActiveSecretAsBytes` — **there is no first-class rotate/replace method**.
Treat rotation as a one-off DBA + ops task, not a documented button.

> **Before you start.** This is a destructive, downtime-bearing procedure — do it in a planned
> maintenance window. Both paths below run `docker compose down`, which stops **the entire stack**
> (app, Traefik, Grafana, the LGTM containers), not just the app. First:
> 1. Take a fresh database dump: `bash /opt/skillars/deploy/backup/pg-backup.sh` and confirm it
>    ends with `Upload verified`.
> 2. Dry-run the delete as a read to confirm exactly one row matches:
>    ```bash
>    docker compose exec postgres psql -U "<POSTGRES_USER>" -d "<POSTGRES_DB>" \
>      -c "SELECT version, bus_id, created_date FROM sec WHERE version = 'v1' AND bus_id = 'jot';"
>    ```

Two supported ways to force a new key:

- **Replace the `(v1, jot)` row in place.** Stop the stack, delete the row, let
  `JwtSecretBootstrapRunner` recreate it (it only INSERTs when none exists), then disable the runner
  again:
  ```bash
  cd /opt/skillars && docker compose down          # FULL-STACK DOWNTIME STARTS HERE
  docker compose up -d postgres
  docker compose exec postgres psql -U "<POSTGRES_USER>" -d "<POSTGRES_DB>" \
    -c "DELETE FROM sec WHERE version = 'v1' AND bus_id = 'jot';"
  # temporarily enable the bootstrap runner for one boot:
  #   add  APP_BOOTSTRAP_JWT_SECRET_ENABLED=true  to /opt/skillars/.env
  docker compose up -d                             # downtime ends once app is healthy
  docker compose logs app | grep jwt_secret_bootstrap   # expect action=create_secret status=SUCCESS
  # then REMOVE APP_BOOTSTRAP_JWT_SECRET_ENABLED from .env and redeploy:
  docker compose up -d
  ```
  If the bootstrap runner logs anything other than `action=create_secret status=SUCCESS`, restore
  the pre-rotation dump (`restore-from-dump.sh latest`) rather than leaving the `sec` table empty —
  an empty `(v1, jot)` fails **every** request, authenticated or not.
- **Bump `JWT_VERSION`** (e.g. `v1` → `v2`) in `SecurityConstants` — a code change + redeploy. On
  next boot the fetch for `(v2, jot)` misses; provision the new row the same way (bootstrap runner,
  or hand-rolled Jasypt SQL). The stale `(v1, jot)` row is simply left unused.

**Operational consequence, either way:** every issued access token (15 min TTL) **and** refresh
token (7 day TTL) signed with the old key becomes invalid the moment each app instance restarts and
re-reads the `volatile` cache. All users must re-authenticate. Roll during a low-traffic window.

### `GF_SECURITY_ADMIN_PASSWORD`

1. Update `GF_SECURITY_ADMIN_PASSWORD` in `/opt/skillars/.env`.
2. Recreate the Grafana container: `cd /opt/skillars && docker compose up -d grafana`.
   Grafana persists the admin user in `/opt/skillars/data/grafana`, so the env var only takes effect
   on a container **recreate**, and a password that was already changed from within the Grafana UI is
   **not** overridden by the env var (documented Grafana behaviour).
3. If the UI password was changed and is now lost, reset it from inside the container:
   ```bash
   docker compose exec grafana grafana-cli admin reset-admin-password '<NEW_PASSWORD>'
   ```
