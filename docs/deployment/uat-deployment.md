# UAT Deployment Guide

Run Skillars on a separate, publicly-reachable UAT server: the same
Docker Compose stack production uses (Traefik + Let's Encrypt TLS, Postgres,
Redis, **the full LGTM observability stack** — Loki, Grafana, Tempo,
Prometheus, unchanged and unTrimmed from production), pointed at its own
domain and its own database, with the `uat` Spring profile active,
**Stripe test-mode** credentials, and **MinIO standing in for the real S3
API** — so payment and upload flows can be exercised end-to-end over a real
public URL without any risk of moving real money or touching production
data/buckets.

This is a different box from production, provisioned the same way. If it
doesn't exist yet, follow [`first-time-setup.md`](first-time-setup.md) Steps
1–5 first — server, DNS, provisioning, firewall, secrets — substituting your
UAT domain (e.g. `uat.skillars.com`, the default `application-uat.yaml`
already assumes) for `DOMAIN`. UAT needs **two additional DNS records**
beyond what `first-time-setup.md` covers — `MONITORING_DOMAIN` (Grafana) and
`STORAGE_DOMAIN` (MinIO, Step 3 below) — both pointed at the same Node IP,
the same way `first-time-setup.md` Step 2 sets up `DOMAIN` and
`MONITORING_DOMAIN`. Everything below picks up from there; only Steps 6+
differ from a straight production deploy, and only in the ways this doc
calls out.

For a trimmed-down, no-domain/no-TLS setup on your own machine instead, see
[`local-deployment.md`](local-deployment.md).

---

## Prerequisites

- A UAT server provisioned per `first-time-setup.md` Steps 1–4 (or an
  existing one you're repurposing), with `DOMAIN`, `MONITORING_DOMAIN`, and
  `STORAGE_DOMAIN` all resolving to it
- A `.env.uat` prepared per Step 3 below (the UAT equivalent of
  `first-time-setup.md` Step 5 / [`secrets-reference.md`](secrets-reference.md))
- A [Stripe](https://dashboard.stripe.com) account (free) with **test mode** enabled (the toggle in the top-right of the Dashboard)

---

## Step 1: Why a separate compose override is needed

`docker-compose.yml` (shared with production, unmodified) doesn't set
`SPRING_PROFILES_ACTIVE` and doesn't pass most `app.*` config through to the
container's environment — only `SPRING_DATASOURCE_*`, Redis, and the LGTM
endpoints are wired today. Without a profile explicitly active, the app boots
on the base profile only, and `application-uat.yaml`'s overrides (including
the Stripe test-key safety check) never apply.

`docker-compose.uat.yml` (checked into the repo, next to
`docker-compose.local.yml`) fixes this for UAT: it sets
`SPRING_PROFILES_ACTIVE=uat`, adds a `minio` service (Step 3), and passes
through every secret-bearing `app.*`/`skillars.*` config value UAT needs,
each with a comment explaining the specific startup failure it works around
(mirroring gotchas already found and documented for `dev` in
`docker-compose.local.yml` / `local-deployment.md`, plus two more this doc
found while writing it — see Step 4). It deliberately does **not** trim
anything from the base `docker-compose.yml` (unlike
`docker-compose.local.yml`, which strips Traefik/host paths for a laptop) —
Postgres, Redis, Traefik, and the full LGTM stack all come up exactly as they
would in production, just with UAT's profile/domain/secrets layered on top.
Nothing to create in this step — [open the file](../../docker-compose.uat.yml)
to see exactly what it does.

The reason this matters beyond "config wiring": `PaymentConfig`
(`src/main/java/.../payment/config/PaymentConfig.java`) refuses to start if
`app.payment.stripe.api-key` looks like a live Stripe key (`sk_live_...` or
`rk_live_...`) *while the `uat` profile is active*. That check only fires
because this override file puts `uat` in `SPRING_PROFILES_ACTIVE` — it's the
thing that turns the safety net on.

---

## Step 2: Get a Stripe test-mode secret key

With **Test mode** enabled in the Stripe Dashboard:

1. Go to **Developers → API keys**
2. Copy the **Secret key** (not the Publishable key, and not a Restricted
   key — see below) — it starts with `sk_test_...` (never `sk_live_...`)
3. If you use Stripe Connect (coach onboarding) in UAT too, also note your
   **Client ID** from **Settings → Connect → Platform settings** — Connect
   has its own test-mode client ID, separate from the API key

**Use the standard secret key, not a Restricted key, for
`APP_PAYMENT_STRIPE_API_KEY`.** Stripe's dashboard offers Restricted keys
(`rk_test_...`/`rk_live_...`) as a scoped, lower-blast-radius alternative to
the standard secret key, and Stripe recommends them for new integrations in
general — but `StripeOnboardingService` performs the Connect OAuth token
exchange (`OAuth.token`), and Stripe requires the account's standard secret
key for that specific call; a Restricted key fails it regardless of which
permissions you grant it. The Publishable key (`pk_...`) doesn't work at all
here either — it has no secret-side permissions and can't authenticate any
server-side call; it's only meant for frontend code, which this backend
doesn't use.

Test-mode keys are Stripe's own sandboxing mechanism: they can only create
test-mode objects (customers, charges, subscriptions), only accept
[Stripe's official test card numbers](https://docs.stripe.com/testing)
(e.g. `4242 4242 4242 4242` for a successful charge), and are billed
nothing regardless of amount — Stripe enforces this separation account-wide,
independent of anything in this codebase. `PaymentConfig`'s startup check
(Step 1) is a second, local backstop in case a live key ends up in the wrong
place.

---

## Step 3: MinIO stands in for the real S3 API

UAT uses [MinIO](https://min.io) instead of real AWS credentials — it speaks
the S3 API, so `BlobstoreConfig`'s `S3Client`/`S3Presigner` beans work
against it unmodified once `app.storage.s3.path-style-access` is `true`
(already set in `application-uat.yaml`). This also means test uploads never
land in a production bucket, and there's nothing to clean up in AWS after
tearing UAT down.

**Why this needs its own public domain, unlike local dev's MinIO:**
`S3Presigner` bakes `app.storage.endpoint-url` directly into every presigned
URL it hands back to the browser for uploads/downloads — there's no separate
"internal" vs. "public" endpoint setting. `docker-compose.local.yml` works
around this for a single laptop with an `/etc/hosts` entry pointing `minio`
at `127.0.0.1`; that trick doesn't scale to arbitrary browsers on the public
internet. Instead, `docker-compose.uat.yml`'s `minio` service is routed
through Traefik on its own domain (`STORAGE_DOMAIN`) with its own Let's
Encrypt certificate — the exact same pattern already used for
`MONITORING_DOMAIN`/Grafana in `docker-compose.yml` — and
`APP_STORAGE_ENDPOINT_URL` is derived from that same domain, so the app
container and every browser resolve the identical HTTPS host.

1. Add one more DNS **A record** pointing at the Node IP, alongside `DOMAIN`
   and `MONITORING_DOMAIN`:

   | Record | Type | Value |
   |---|---|---|
   | `STORAGE_DOMAIN` (e.g. `storage-uat.skillars.com`) | A | `<NODE_IP>` |

   Verify propagation the same way as `first-time-setup.md` Step 2:
   `dig +short <STORAGE_DOMAIN> @8.8.8.8` must return the Node IP before
   first deploy, or Traefik's Let's Encrypt challenge for this domain fails.
2. Pick a UAT-only bucket name (`APP_STORAGE_BUCKET`, e.g. `skillars-uat`) —
   `docker-compose.uat.yml`'s `minio-init` service creates it automatically
   on every startup (no-op if it already exists), same as
   `docker-compose.local.yml` does for dev.
3. Generate real MinIO root credentials for `.env.uat` (Step 4) —
   **do not reuse dev's `minioadmin`/`minioadmin123`**, since this instance
   is reachable from the public internet, not just your laptop:
   ```bash
   openssl rand -base64 24   # MINIO_ROOT_USER
   openssl rand -base64 32   # MINIO_ROOT_PASSWORD
   ```

The MinIO web console (bucket browser) isn't exposed publicly — adding it
would mean another DNS record and TLS cert for something that's only useful
occasionally. Reach it over an SSH tunnel instead when you need to:
```bash
ssh -L 9501:localhost:9501 root@<UAT_NODE_IP>
# then browse http://localhost:9501 locally, log in with MINIO_ROOT_USER/MINIO_ROOT_PASSWORD
```

---

## Step 4: Prepare `.env.uat`

Following the same pattern as `.env` (production) and `.env.local` (dev) —
a separate file per environment, so copying one never silently overwrites
another (see `local-deployment.md` Step 2 for why this matters). **Every
password, secret, and PIN this environment needs lives in this one file** —
`docker-compose.uat.yml` passes each through explicitly, and none of them
have insecure hardcoded fallbacks baked into the compose file itself (the
one exception, `BUNNY_LIBRARY_ID`, is a non-secret numeric placeholder ID,
not a credential).

```bash
cp .env.example .env.uat
```

Fill in the same required values as production (see
[`secrets-reference.md`](secrets-reference.md) — `DOMAIN`, `POSTGRES_*`,
`LETSENCRYPT_EMAIL`, `MONITORING_DOMAIN`, Grafana/SMTP alerting, etc.), using
your UAT domain and a UAT-only Postgres database, **plus** every variable
below, which is specific to UAT / not in `secrets-reference.md` yet:

| Variable | Required? | Value |
|---|---|---|
| `STORAGE_DOMAIN` | Yes | The domain from Step 3 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | Yes | Generated in Step 3 — these double as `APP_STORAGE_S3_ACCESS_KEY`/`APP_STORAGE_S3_SECRET_KEY`, `docker-compose.uat.yml` maps them automatically |
| `APP_STORAGE_BUCKET` | Yes | The bucket name chosen in Step 3 |
| `APP_PAYMENT_STRIPE_API_KEY` | Yes | The `sk_test_...` key from Step 2 |
| `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` | Yes (for webhook-dependent flows) | From Step 5 below |
| `APP_PAYMENT_STRIPE_OAUTH_CLIENT_ID` | Only if testing Connect onboarding | The test-mode Connect client ID from Step 2 |
| `PLATFORM_PIN_ENCRYPTION_SECRET` | **Yes — app refuses to start without it** | `Cryptopher` throws `EncryptionException(MISSING_SECRET)` and the whole context fails if this is blank, profile-independent, not new to UAT. Generate: `openssl rand -base64 32` |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` | **Yes — app refuses to start without it** | `BunnyVideoProviderAdapter`'s constructor throws if blank, and it's built eagerly (non-lazy bean) whenever `app.video.provider=bunny`, the default — so this crashes the *entire* app context on boot even if UAT never touches video. Any non-blank value unblocks startup; only real Bunny webhook verification needs the real Bunny Read-Only API key — generate a placeholder with `openssl rand -base64 24` if video isn't in scope for this UAT round |
| `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | Yes, before anyone tries to watch a video | Not fail-fast at boot — only fails the first time a playback JWT is signed (`Base64.getDecoder().decode(...)` needs ≥32 raw bytes). Generate: `openssl rand -base64 32` |
| `BUNNY_LIBRARY_ID` / `BUNNY_API_KEY` / `BUNNY_CDN_HOSTNAME` | No | Reuses the same variables as `.env` (production); omit `BUNNY_LIBRARY_ID` to fall back to the `123456` placeholder that unblocks app startup without real video features |

**Do not put a live (`sk_live_...` or `rk_live_...`) key in `.env.uat`.**
Even if you did, `PaymentConfig` refuses to start under the `uat` profile —
but the point is to not rely on that backstop.

---

## Step 5: Register the Stripe webhook endpoint

Because this server is publicly reachable, Stripe can deliver webhooks
directly — no local forwarding tool needed here (that's only for iterating
on your own machine before deploying; see the note in
[`docs/dev-docs/payment/index.html`](../dev-docs/payment/index.html#running-stripe-listen-locally)
if you want to test webhook-driven flows without touching UAT at all).

1. In the Stripe Dashboard, with **Test mode** still on, go to
   **Developers → Webhooks → Add endpoint**
2. Endpoint URL: `https://<DOMAIN>/api/payment/webhooks/stripe`
   (e.g. `https://uat.skillars.com/api/payment/webhooks/stripe`)
3. Select the events this app handles — check
   `StripeWebhookService`/`StripeWebhookResource` for the current list, or
   select **all events** for a UAT box since correctness matters more than
   noise here
4. After creating the endpoint, click it and copy the **Signing secret**
   (`whsec_...`) — this is `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` in `.env.uat`

The signature check happens in `StripeWebhookService` — an endpoint
registered against the wrong domain, or a `.env.uat` with the wrong signing
secret, fails closed (`payment.webhookSignatureInvalid` / HTTP 400), not
open, so a misconfiguration here is loud rather than silently accepting
forged events.

---

## Step 6: Deploy

```bash
ssh root@<UAT_NODE_IP> "cd /opt/skillars && git pull"
scp .env.uat root@<UAT_NODE_IP>:/opt/skillars/.env.uat
ssh root@<UAT_NODE_IP> "chmod 600 /opt/skillars/.env.uat"
ssh root@<UAT_NODE_IP> \
  "cd /opt/skillars && docker compose -f docker-compose.yml -f docker-compose.uat.yml --env-file .env.uat up -d"
```

Watch startup status the same way as `first-time-setup.md` Step 6:

```bash
ssh root@<UAT_NODE_IP> "cd /opt/skillars && docker compose -f docker-compose.yml -f docker-compose.uat.yml --env-file .env.uat ps"
```

`docker compose up -d` with no explicit service list brings up everything
defined across both files — Postgres, Redis, Traefik, `app`, `minio` +
`minio-init`, and the full LGTM stack (`loki`, `tempo`, `prometheus`,
`grafana`) — in one command; nothing is trimmed the way
`docker-compose.local.yml` trims it for a laptop.

If `PaymentConfig`'s live-key guard trips, or `Cryptopher`/
`BunnyVideoProviderAdapter` reject a blank secret (Step 4), the `app`
container exits immediately on boot with a clear exception in the logs
(`docker compose ... logs app --tail 50`) rather than looping unhealthy —
check `.env.uat` against the Step 4 table.

---

## Step 7: Verify

```bash
curl -s https://<DOMAIN>/actuator/health
# {"status":"UP"}
```

Then exercise a real payment flow through the UI or API using a
[Stripe test card](https://docs.stripe.com/testing) (`4242 4242 4242 4242`,
any future expiry, any CVC) and confirm:

- The charge appears in the Stripe Dashboard under **Test mode** (never
  under live mode — if it does, something upstream of `PaymentConfig` is
  misconfigured and needs investigating before this box is trusted)
- The webhook fires and is visible in **Developers → Webhooks →** your
  endpoint **→ recent deliveries**, with a `200` response

Then upload something through a feature that stores a file (e.g. a coach
profile photo) and confirm:

- `docker compose ... exec minio mc ls local/<APP_STORAGE_BUCKET>` (after
  `mc alias set local http://localhost:9500 <MINIO_ROOT_USER> <MINIO_ROOT_PASSWORD>`
  inside the container) shows the uploaded object
- The image actually renders in the browser — confirms `STORAGE_DOMAIN`'s
  TLS cert is valid and the presigned GET URL `S3Presigner` generated is
  reachable from outside the compose network, not just from the `app`
  container

And confirm Grafana/observability is live: `https://<MONITORING_DOMAIN>`
loads and the Prometheus/Tempo datasources return data for the traffic you
just generated (same as `first-time-setup.md` Step 7 / `local-deployment.md`
Step 6.1).

---

## Notes

- Seeding bootstrap data (the JWT signing key row, optional sample users)
  works the same as `local-deployment.md` Step 5 — run the same SQL fixture
  against the UAT Postgres container.
- `docker-compose.yml` itself still doesn't set `SPRING_PROFILES_ACTIVE` for
  a plain production deploy (`docker compose up -d` with no `-f` overrides) —
  that's a separate, pre-existing gap tracked outside this doc, not something
  `docker-compose.uat.yml` changes. Production deploys must keep using
  `docker-compose.yml` alone (no `-f docker-compose.uat.yml`), the same as
  today, or they'd pick up UAT's Stripe test key and MinIO storage.
- Two of the secrets in Step 4 (`PLATFORM_PIN_ENCRYPTION_SECRET`,
  `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET`) are fail-fast **everywhere**, not
  UAT-specific — production presumably has real values for these set
  somewhere already (the app couldn't have booted otherwise), but
  `docker-compose.yml`/`secrets-reference.md` don't currently document how
  they reach the container, the same gap already noted for
  `APP_PAYMENT_STRIPE_*`/`APP_STORAGE_*`. Worth reconciling separately from
  this doc.
- This guide was assembled from the verified `first-time-setup.md` /
  `local-deployment.md` content plus the actual current `PaymentConfig`,
  `application-uat.yaml`, and compose files, and `docker compose config` was
  used to confirm the merged `docker-compose.yml` + `docker-compose.uat.yml`
  parses cleanly end-to-end — but unlike those two guides, it has not yet
  been run against a live remote box. Treat Steps 6–7 as the part most
  likely to need a small correction the first time through, and
  update this note once it's been confirmed working.
