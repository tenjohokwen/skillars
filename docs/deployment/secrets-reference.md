# Secrets Reference

This document lists every secret required to run the application and CI/CD pipeline.
**No secret values appear here** — only names, formats, placements, and generation instructions.

---

## Server `.env` — Place at `/opt/skillars/.env` (mode 600)

Copy `.env.example` to `.env`, fill in every value, and SCP to the Node.
`deploy/provision.sh` auto-enforces mode 600 on re-run.

> **JWT signing key is not an operator-supplied secret.** There is no `JWT_SECRET` (or equivalent) `.env`
> variable — the app never reads one. The real JWT signing key is a 256-byte value, auto-generated on first
> boot and stored Jasypt-encrypted in the database (`sec.secret` table, via `SecretService.createSecret` /
> `JwtSecretService`). Nothing to configure here.

| Variable | Format | How to obtain or generate |
|---|---|---|
| `APP_IMAGE` | `ghcr.io/<org>/javatemplate:sha-<commit>` | Produced by the CI pipeline (Epic 2) after merge to `main`. For the very first deploy (before CI is set up), build and push manually — see commands below the table |
| `DOMAIN` | FQDN, e.g. `api.example.com` | Your registered domain; must have an A record pointing to the Node IP before first deploy |
| `LETSENCRYPT_EMAIL` | Email address | Your email address; used by Let's Encrypt for certificate expiry notifications |
| `POSTGRES_DB` | Alphanumeric string, e.g. `skillars` | Choose a database name; default `skillars` |
| `POSTGRES_USER` | Alphanumeric string, e.g. `skillars` | Choose a database username; default `skillars` |
| `POSTGRES_PASSWORD` | 32+ character random string | `openssl rand -base64 32` |
| `SPRING_DATASOURCE_URL` | JDBC URL | Fixed derived value: `jdbc:postgresql://postgres:5432/<POSTGRES_DB>?TimeZone=UTC` — substitute `<POSTGRES_DB>` with the literal database name you chose above; Docker Compose does not expand variable references within `.env` file values |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Email address | **Temporary — see the callout below.** The login for the platform's first administrator. Choose an address that does not already belong to a coach, parent or player account |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | 24+ character random string | **Temporary — see the callout below.** `openssl rand -base64 24`. Stored bcrypt-hashed; never logged. Record it in your password manager before the first deploy — it cannot be recovered from the running system |
| `APP_BOOTSTRAP_ADMIN_PHONE` | E.164 phone number, e.g. `+491700000000` | **Temporary — see the callout below.** Required whenever the two above are set. `main."user".phone` carries a `UNIQUE` constraint, so this cannot be a shared placeholder — a second admin bootstrapped on the same database needs a different number |
| `SPRING_MAIL_HOST` | SMTP hostname, e.g. `smtp.gmail.com` | From your email provider (e.g. `smtp.gmail.com`, `smtp.sendgrid.net`) |
| `SPRING_MAIL_PORT` | Integer, e.g. `587` | From your email provider — 587 for STARTTLS, 465 for SSL/TLS |
| `SPRING_MAIL_USERNAME` | Email address | Your SMTP username or sending address |
| `SPRING_MAIL_PASSWORD` | String | App password or SMTP credential from your email provider |
| `BUNNY_API_KEY` | Hex string | Bunny.net Dashboard → Account → API |
| `BUNNY_LIBRARY_ID` | Integer | Bunny.net Dashboard → Stream → Your Library → Library ID |
| `BUNNY_CDN_HOSTNAME` | Hostname, e.g. `your-library.b-cdn.net` | Bunny.net Dashboard → Stream → Your Library → Pull Zone hostname |
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
| `LOKI_URL` | Internal URL | Fixed value: `http://loki:3100` — do not change (Docker service name on `skillars-internal` network) |
| `LOKI_ENABLED` | Boolean | Fixed value: `true` — do not change |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | Internal URL | Fixed value: `http://tempo:4318/v1/traces` — do not change (Docker service name on `skillars-internal` network) |
| `HOS_ACCESS_KEY` | String | Hetzner Cloud Console → Object Storage → your bucket → Access Keys → Create access key; copy Access Key ID |
| `HOS_SECRET_KEY` | String | Same creation flow as `HOS_ACCESS_KEY`; copy Secret Access Key (shown once) |
| `HOS_BUCKET` | String, e.g. `skillars-backups` | Create a private bucket in Hetzner Cloud Console → Object Storage; use the exact bucket name here |
| `HOS_ENDPOINT` | HTTPS URL, e.g. `https://s3.fsn1.hetzner.com` | Hetzner Object Storage endpoint for your datacenter region (fsn1 = Falkenstein, nbg1 = Nuremberg, hel1 = Helsinki) — visible in the bucket details page |
| `HOS_BACKUP_PREFIX` | String ending in `/`, e.g. `pg-backups/` | Choose a key prefix to organize backups within the bucket; default `pg-backups/` |
| `HOS_VOLUME_BACKUP_PREFIX` | String ending in `/`, e.g. `volume-backups/` | Choose a key prefix to organize file-level volume backups within the bucket; default `volume-backups/` |
| `BACKUP_RETENTION_DAYS` | Integer, e.g. `14` | **Tuning knob, not a secret.** How many days of PostgreSQL dumps `prune-backups.sh` keeps; default `14` (~56 dumps at the 6-hourly cadence) |
| `BACKUP_RETENTION_MIN_KEEP` | Integer, e.g. `8` | **Tuning knob, not a secret.** Newest dumps retained unconditionally regardless of age — the floor that stops a bad cutoff emptying the bucket; default `8` |
| `VOLUME_BACKUP_RETENTION_DAYS` | Integer, e.g. `14` | **Tuning knob, not a secret.** How many days of file-level volume backups `prune-backups.sh` keeps; default `14` (~14 backups at the daily cadence) |
| `VOLUME_BACKUP_RETENTION_MIN_KEEP` | Integer, e.g. `4` | **Tuning knob, not a secret.** Newest volume backups retained unconditionally regardless of age — the floor that stops a bad cutoff emptying the bucket; default `4` |

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
> docker build -t ghcr.io/<org>/javatemplate:sha-$(git rev-parse --short HEAD) .
> docker push ghcr.io/<org>/javatemplate:sha-$(git rev-parse --short HEAD)
> # Then set APP_IMAGE=ghcr.io/<org>/javatemplate:sha-$(git rev-parse --short HEAD) in .env
> ```
> Run these commands from the root of your local repository clone before Step 6.

> **Note on internal service addresses:** `LOKI_URL` and `MANAGEMENT_OTLP_TRACING_ENDPOINT` reference
> Docker service names on the `skillars-internal` bridge network. They are not real secrets and their
> values must not be changed — the application cannot reach these services by any other address.

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
