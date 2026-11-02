# Local Manual Testing — Paid Coach + Paid Parent/Player, Without Stripe

How to run the whole product on your own machine and click through every
workflow with a fully paid coach and a fully paid parent/player, while making
zero calls to Stripe.

This is the *manual testing* companion to
[`deployment.md`](./deployment.md),
which covers bringing the Docker stack up. Read that one for the infrastructure
detail (volumes, the SeaweedFS-backed `storage` service, Grafana, log
tailing, teardown); this one covers
choosing a run mode, getting accounts through registration without a working
mailbox, seeding the payment state that would normally come from Stripe, and
checking the newer safety and abuse-prevention features by hand.

Last verified against master `01770303` (2026-11-02): stack, seed script,
admin tiers, Redis behaviour and Mode B were run for real; the UI-only
scenarios were checked against the code and i18n strings.

- [Which run mode](#which-run-mode)
- [Startup blockers not covered by the deployment guide](#startup-blockers-not-covered-by-the-deployment-guide)
- [Creating the accounts](#creating-the-accounts)
- [Making them paid, without Stripe](#making-them-paid-without-stripe)
- [What you can and cannot exercise](#what-you-can-and-cannot-exercise)
- [Stripe actually does work locally](#stripe-actually-does-work-locally)
- [Scenarios for the recent safety features](#scenarios-for-the-recent-safety-features)
- [Setup verification checklist](#setup-verification-checklist)
- [Troubleshooting](#troubleshooting)
- [Tips](#tips)

---

## Which run mode

The Maven build compiles the Quasar frontend and `maven-resources-plugin` copies
`src/frontend/dist/spa` into `target/classes/static` (see the `skipFrontend`
comment in `pom.xml`). **The packaged jar therefore serves the UI itself** — the
Docker image is the whole product, not just an API.

That gives two clean modes. Pick by whether you are testing or changing things.

| Aspect | Mode A (all-Docker) | Mode B (dev machine + infra) |
|--------|-----|-----|
| When to use | Pure manual testing | Iterating on code |
| Rebuild on change | Full Maven + npm + quasar build (slow) | Partial backend/frontend only (fast) |
| Hot reload | No | Yes (frontend HMR, backend restarts in seconds) |
| Debugger attach | Via `docker compose exec` | Direct IDE attachment |
| UI + API ports | 9990 (both together) | 9000 (UI, with proxy to 9990 API) |
| Extra requirements | Docker only | Java 17, Maven 3.9, Node 22, an extra compose file for host ports |
| Closest to production | Yes | No (but faster iteration) |

### Mode A — all-Docker (recommended for pure manual testing)

Everything in containers, UI and API together on one port. Closest to what UAT
and production actually run. `docs/deployment/local/setup.sh` prepares it and
`start.sh` starts it; by hand:

```bash
alias dcl='docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local'

docker build -t skillars:local .
dcl up -d app postgres redis storage-init grafana
```

App at **http://localhost:9990**, health at **http://localhost:8367/manage/health**.

`docker-compose.local.yml` sets `APP_FRONTEND_URL=http://localhost:9990` for the
`app` service, so verification links generated during registration already point
here — no manual edit needed. (Without that override, the base `docker-compose.yml`'s
own default of `https://skillars.com` wins: Spring environment variables rank above
`application-dev.yaml`, so an unset override silently sends every local
verification link to production.)

Cost: a full rebuild (Maven + npm + `quasar build`) for every source change.

### Mode B — infra in Docker, app and UI on the host (recommended when changing code)

Postgres and Redis are not published by `docker-compose.yml` (and Redis sits on
an `internal: true` network), so the host-run app cannot reach them with the
stack as-is. Layer the extra file
[`docker-compose.host-ports.yml`](./docker-compose.host-ports.yml) on top. It
publishes Postgres on `127.0.0.1:5433` (override with `HOST_POSTGRES_PORT`) and
Redis on `127.0.0.1:6379` (`HOST_REDIS_PORT`), loopback only:

```bash
alias dclb='docker compose -f docker-compose.yml -f docker-compose.local.yml -f docs/deployment/local/docker-compose.host-ports.yml --env-file .env.local'
dclb up -d postgres redis storage storage-init          # + grafana if you want it

# values below come from docker-compose.local.yml / .env.local
export APP_VIDEO_BUNNY_LIBRARY_ID=123456
export APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/skillars?TimeZone=UTC'
export SPRING_DATASOURCE_USERNAME=skillars              # POSTGRES_USER in .env.local
export SPRING_DATASOURCE_PASSWORD=<POSTGRES_PASSWORD from .env.local>
export SPRING_DATA_REDIS_PASSWORD=dev-redis-password-NOT-for-prod
export APP_STORAGE_ENDPOINT_URL=http://storage:9500     # needs the /etc/hosts entry
mvn spring-boot:run -Dspring-boot.run.profiles=dev -DskipFrontend
cd src/frontend && npm install && npx quasar dev    # serves :9000
```

Verified on 2026-11-02: the backend booted against that infrastructure, ran no
migrations (the database was already at `V223`) and answered `UP` on
`:8367/manage/health` (the Quasar dev server itself was not started). Stop the
Docker `app` container first (`dcl stop app`) so ports 9990 and 8367 are free.

UI at **http://localhost:9000**, proxying `/api` → `localhost:9990`
(`devServer.proxy` in `quasar.config.js`). Backend restarts in seconds, frontend
has hot reload, and a debugger attaches normally. `app.frontend-url` already
matches port 9000, so verification links are correct as-generated. Emails are
dumped to `./target/mails` in your checkout.

`-DskipFrontend` skips the five `frontend-maven-plugin` executions. Never set it
for anything you intend to deploy — the resulting jar ships with no UI at all.

---

## Environment variables reference

These are all the variables you may need to set depending on your mode. Defaults are provided in `docker-compose.local.yml` and `application-dev.yaml` where applicable. "In the dev profile" means Mode B needs nothing; "export" means Mode B needs it (see the block above).

| Variable | Required? | Mode A | Mode B | Purpose |
|----------|-----------|--------|--------|---------|
| `APP_PAYMENT_STRIPE_API_KEY` | Yes | Already in compose | Export | Placeholder to satisfy PaymentConfig validation |
| `APP_VIDEO_BUNNY_LIBRARY_ID` | Yes | Already in compose (123456) | **Export** (not in the dev profile; boot fails without it) | Bunny CDN library ID, must be numeric |
| `SPRING_DATASOURCE_*`, `SPRING_DATA_REDIS_PASSWORD` | Mode B only | Wired by compose | Export (see above) | Postgres / Redis connection; Redis requires a password |
| `JWT_SIGNING_KEY` | No | Dev key in compose | Dev key in the dev profile | JWT signing key (env var, not the database); prod/uat reject the dev key |
| `APP_EMAIL_TRANSPORT` | No | Not settable (`NoHardcodedSenderTest` forbids it in committed compose files) | Defaults to `log`; export `smtp` to send real mail via GMX/Gmail | `log` writes each rendered email to `target/mails/`, `smtp` sends it |
| `GMX_PASSWORD`, `GMAIL_PASSWORD` | No | Placeholders, unused under `log` | Export only if you switch to `smtp` | SMTP credentials (uat still uses them) |
| `MANAGEMENT_HEALTH_MAIL_ENABLED` | No | In compose, a harmless no-op | Not needed | Historical: disabled the SMTP mail health check |
| `APP_STORAGE_ENDPOINT_URL` | Yes | Already in compose | Export `http://storage:9500` | SeaweedFS (S3-compatible) endpoint for file uploads |
| `APP_STORAGE_S3_ACCESS_KEY`, `_SECRET_KEY` | Yes | Already in compose | In the dev profile | SeaweedFS credentials |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET`, `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | Yes | Already in compose | In the dev profile | Placeholders so the video adapter doesn't abort on blank secrets |
| `PLATFORM_PIN_ENCRYPTION_SECRET`, `PLATFORM_REGISTRATION_VERIFICATION_SECRET` | Yes | Already in compose | In the dev profile | PIN encryption; registration token and OTP-hash key |
| `APP_SES_FROM_ADDRESS` | No | Already in compose | In the dev profile | Only read by the `ses` transport |
| `GEMINI_API_KEY`, `GEMINI_MODE` | No | `dev-key` / `developer` (default) | Same defaults | AI chat moderation; the developer-API compliance warning is prod-only |
| `ARACHNID_ENABLED` | No | `false` (default) | `false` (dev profile) | Image abuse-media scanning; see [Media scanning](#media-scanning-and-video-privacy-s-18) |
| `ALLOW_UNSCANNED_MEDIA` | No | Irrelevant | Irrelevant | Pre-launch override for the **prod/uat** boot validator only; ignored under `dev` |
| `APP_VIDEO_MODERATION_REQUIRES_REAL_SCANNER` | No | Not in compose | Export `false` to lift the video privacy gate | Default `true`: only the uploader and admins can play a video |
| `APP_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`/`_PHONE` | No | `.env.local` (compose passes them) | Export | Creates the first `ROLE_ADMIN` on startup |
| `127.0.0.1 storage` | Yes | `/etc/hosts` | `/etc/hosts` | Required for the browser to reach SeaweedFS presigned URLs (see `deployment.md`) |

**Mode A quick setup:**
```bash
echo "127.0.0.1 storage" | sudo tee -a /etc/hosts
docs/deployment/local/setup.sh && docs/deployment/local/start.sh
```

**Mode B quick setup:** the block under [Mode B](#mode-b--infra-in-docker-app-and-ui-on-the-host-recommended-when-changing-code), after the `/etc/hosts` line above.

---

## Startup blockers not covered by the deployment guide

[`deployment.md`](./deployment.md) (Step 3) lists everything the checked-in
`docker-compose.local.yml` already supplies. Two of those entries are crash loops
you will meet again the moment you run the app outside that file (Mode B, an
IDE run configuration, a hand-written compose file):

1. **The app will not start without a Stripe API key.**
   `PaymentConfig.configureStripe()` throws `AppSetupException` when
   `app.payment.stripe.api-key` is blank:

   ```
   app.payment.stripe.api-key is missing or empty. Stripe integration requires a valid API key.
   ```

   It defaults to `""` and `application-dev.yaml` does not override it, so export
   `APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder`. The value is never used
   by anything in this guide; the adjacent guard rejects only `^(sk|rk)_live_`
   keys outside the `prod` profile, so any test-shaped placeholder passes and a
   real live key would be refused locally.

2. **The app will not start without a numeric Bunny library id.**
   `app.video.bunny.library-id` defaults to an empty string and the
   `videoProviderAdapter` bean validates it eagerly:

   ```
   BunnyVideoProviderAdapter: app.video.bunny.library-id must be numeric, got:
   ```

   Export `APP_VIDEO_BUNNY_LIBRARY_ID=123456` (the placeholder the integration
   tests use). Compose already sets it in Mode A.

Everything else that used to appear here (the loki4j logback crash, the SES client
in the dev profile, the broken Tomcat access-log path) is fixed in the code. If
you meet `Logback configuration error detected` or
`NoClassDefFoundError: org/apache/hc/client5/...` you are running an image built
before those fixes; rebuild it.

**No JWT secret to seed.** The JWT signing key comes from `JWT_SIGNING_KEY`
(default in `application-dev.yaml` and `docker-compose.local.yml`), so nothing
is seeded and `secData.sql` / `initTestData.sql` only write an unused legacy
`main.sec` row. Do not run them.

**The prod/uat validators do not apply.** `DeployedSecretsValidator` (dev-default
secrets, Redis password, https storage endpoint) and
`DeployedMediaScanningValidator` (image scanning on) are
`@Profile({"prod","uat"})`; under `dev` they never run, which is why the dev
defaults boot at all.

Access logs go through SLF4J into Loki (`skillars.access` and
`skillars.access.management`, `APP_ACCESS_LOG_ENABLED=false` turns them off);
see `deployment.md`'s [Logs](./deployment.md#logs) section.

---

## Creating the accounts

Register through the UI like a real user. Two things worth understanding as
you do — neither is really a difference from production any more, just a
local wrinkle:

**No real mail arrives locally — by design, in either mode.**
`application-dev.yaml` sets `app.email.transport: log`: nothing is
sent; every email is logged (`"Sending email (log transport)"`, with the masked
recipient, subject and a per-send correlation id) and its rendered HTML is
written to `target/mails/<correlationId>.html`. The file name is a random UUID,
so open the newest file or grep the folder for the recipient. In Mode B
(`mvn spring-boot:run`) that folder is `./target/mails` in your checkout. In
Mode A (all-Docker) `docker-compose.local.yml` bind-mounts `./target/mails`
into the container, and `docs/deployment/local/setup.sh` creates it (mode 777,
dev-only) — if the directory did not exist before the first `docker compose up`,
Docker creates it root-owned, the app cannot write to it, and it logs a WARN and
falls back to log-only (the file never appears). On macOS, re-run `setup.sh`
and restart. On Linux, `setup.sh` cannot `chmod` a root-owned directory: run
`sudo rm -rf target/mails`, then re-run `setup.sh` and restart. `mvn clean`
deletes `target/mails` too; `start.sh` re-creates it before every start, so
always start Mode A through `start.sh`. The env var that switches the transport
is deliberately never allowed in a committed compose file
(`NoHardcodedSenderTest`); in Mode B you can export it yourself to use `smtp`
with real `GMX_PASSWORD`/`GMAIL_PASSWORD`, but `log` is the supported dev path.

The verification token is always written to the database regardless of
whether the email send itself succeeds, so pulling it from there (see "Fetch
the token" below) remains a valid — often faster — alternative that works
either way. **The OTP is different: it is not recoverable from the database at
all.** There is no SMS gateway: the phone OTP is sent by **email**, and
`phone_otp_tokens.otp_hash` stores a keyed `HMAC-SHA256` of it (`OtpHasher`, keyed
from `PLATFORM_REGISTRATION_VERIFICATION_SECRET`), so `target/mails/` is the only
way to read an OTP. Five wrong guesses burn the OTP (resend to get a new one).
In practice you can usually skip this — see "Phone OTP is already disabled" below.

**Phone OTP is already disabled.** `V139__baseline_seed_data.sql` seeds
`security.registration.phone-otp-required=false`. `AuthService.login` only
demands the phone step when that flag is true, and `activated = true` is set at
*email* verification (`ParentRegistrationService`, `CoachRegistrationService`
and `PlayerRegistrationService` each do it in `verifyEmail`). Email verification
alone is enough to log in — you can ignore the `verify-phone` step the UI
offers you. (An admin can flip the flag in the config console to test the OTP
flow; it then arrives as a file in `target/mails/`.)

**Registration is rate-limited.** `verify-email` allows 10 calls per 10 minutes
per IP and login has per-IP and per-account (20 per 15 minutes) failed-attempt
buckets. A script that creates many accounts from one machine can hit HTTP 429;
wait it out or use a few accounts.

### Steps

1. Register at `/#/coach-register`, `/#/parent-register`, or `/#/player-register`
   (player self-registration is 18+ only; minors are created by a parent as
   shadow accounts).

2. Fetch the token:

   ```sql
   SELECT t.token, u.email, t.expires_at
   FROM main.email_verification_tokens t
   JOIN main."user" u ON u.id = t.user_id
   WHERE u.email = 'coach@example.com' AND t.used = false
   ORDER BY t.expires_at DESC
   LIMIT 1;
   ```

   run with `dcl exec -T postgres psql -U skillars -d skillars` (the role is
   `POSTGRES_USER` from `.env.local`: `skillars` when copied from `.env.example`).

3. Open the verification URL in the browser — the same one the email would have
   contained:

   ```
   http://localhost:9000/#/coach/verify-email?token=<token>&email=coach%40example.com
   ```

   Use `/#/parent/verify-email` or `/#/player/verify-email` for the other roles.
   In **Mode A**, change the host to `localhost:9990`.

4. Log in at `/#/login`.

5. **Coach only:** complete all five profile-builder steps and publish. Publishing
   is not optional bookkeeping — `CoachProfileService.publishProfile` is what
   flips the profile to `ACTIVE` (making it visible in marketplace search) *and*
   what creates the `marketplace.coach_subscriptions` row. `validateAllStepsComplete`
   requires display name, at least one specialty, at least one age group, pricing,
   and at least one availability window.

6. **Parent only:** create at least one player profile. A self-registered adult
   player creates their own self-owned profile after signing in (the seed script
   matches it through `player_profiles.user_id`).

7. **Admins:** register nothing; follow `deployment.md`'s
   [Step 5](./deployment.md#step-5-create-accounts) (`APP_BOOTSTRAP_ADMIN_*`, and
   `seed-accounts.sql -v ltd_admin_email=...` for the limited tier).

### Two gotchas if you drive the API directly instead of the UI

**Use a real-looking email domain.** The `User` entity's `@Email` carries a
custom regex whose TLD group is `[a-z]{2,3}`, so anything longer is rejected —
`coach@local.test` and `coach@my.local` both fail. Worse, the registration DTO
uses the permissive default `@Email`, so the request passes controller validation
and only blows up at persist time as an opaque HTTP 500 with
`ConstraintViolationException ... propertyPath=login`, not a 400 naming the
field. `example.com` (the RFC-reserved documentation domain) is a safe choice and
is what the seed script defaults to.

**Login needs an `fcookie`.** `AuthResource.login` calls
`ensureClientHasPreLoginId()`, which requires either an `fcookie` browser
fingerprint cookie or an `apikey` header for machine clients — otherwise you get
a flat HTTP 401 `security.unauthorized` even with correct credentials. The
frontend sets this automatically (`@rajesh896/broprint.js`); `curl` does not.
Any non-blank value works for login. The fingerprint is also one of the signals
for related-party detection (values of 8+ characters count; see
[below](#related-party-bookings-s-06)), so give different test accounts
different values:

```bash
curl -s -X POST http://localhost:9990/api/auth/login \
  -H 'Content-Type: application/json' \
  -b 'fcookie=local-manual-test-fingerprint' \
  -d '{"email":"coach@example.com","password":"Passw0rd!23"}'
# {"userId":"882672139209216818","role":"COACH","displayName":"Co","limitedAdmin":false}
```

Registration by API: `POST /api/security/{coach|parent|player}/register` with
`firstName`, `lastName`, `email`, `password` (8+ chars), `phone` (unique, e.g.
`+491701234501`) and, for players, `dateOfBirth`; then
`GET /api/security/{role}/verify-email?token=<uuid>`. Verified against `V223`.

---

## Making them paid, without Stripe

Run [`seed-accounts.sql`](./seed-accounts.sql) once the
accounts above exist:

```bash
dcl exec -T postgres psql -U skillars -d skillars \
  -v coach_email=coach@example.com \
  -v owner_email=parent@example.com \
  < docs/deployment/local/seed-accounts.sql
```

Run it again with `-v owner_email=player@example.com` to seed a self-registered
adult player's own wallet and profile, and add `-v ltd_admin_email=<email>` to
demote a bootstrapped admin to the limited tier (see
[Admin tiers](#limited-admin-vs-admin-s-28)). Everything except the credit row is
idempotent. It was run against a database migrated through `V223` on
2026-11-02 and applied cleanly (twice, for both owner shapes). The seed only ever
writes dev-only placeholder state: `acct_local_...` Stripe account ids and a
`local manual-test seed` ledger description; there are no passwords or real
secrets in it.

It prints back what it seeded. Four pieces of state, and it is worth knowing why
each one is sufficient — these are the seams that let the whole booking and
payment path run without a gateway.

### Coach payment readiness

`BookingService` rejects a booking request when
`paymentGateway.isCoachPaymentReady(coachId)` is false. In `StripePaymentGateway`
that method is a **pure database read**:

```java
return coachStripeAccountRepository.findById(coachId)
    .map(a -> "COMPLETE".equals(a.getOnboardingStatus()) && a.isChargesEnabled())
    .orElse(false);
```

A fabricated `payment.coach_stripe_accounts` row satisfies it. No Connect
onboarding, no OAuth, no network call.

### Parent credit — the important one

`PaymentLifecycleService.handleCreditBasedBooking` computes the Stripe portion as
`sessionPrice - min(creditBalance, sessionPrice)` and **only enters the charge
branch when that remainder is greater than zero**. A booking fully covered by
wallet credit never constructs a PaymentIntent, never reserves a capture, and
settles straight to `CONFIRMED` through `persistPaymentSuccess`.

So seeding `payment.parent_credit_ledger` with a balance comfortably above the
coach's per-session price makes the entire booking → accept → payment → session
lifecycle run end-to-end, locally, with Stripe uninvolved.

Two constraints shape how the seed is written. `chk_ledger_amount_sign` requires a
negative amount for the debit types (`BOOKING_DEDUCTION`, `CASH_OUT_DEBIT`,
`STRIPE_FEE_DEBIT`, `BOOKING_REFUND_REVERSAL`, `CHARGEBACK_CREDIT_REVERSAL`) and a
positive one for every other type, so the seed uses the credit type
`BOOKING_REFUND`. And triggers `trg_ledger_no_update`/`trg_ledger_no_delete`
(`V138__baseline_schema.sql`) reject `UPDATE` and `DELETE` on the table — it is
append-only, so re-running the seed **adds** credit rather than resetting it, and
a mistake can only be offset by another row, never corrected.

### Coach feature tier

Feature gating reads `marketplace.coach_subscriptions.tier` through
`CoachProfileService.getCoachSubscriptionTier` — **not** the `payment` schema.
That single column is what unlocks:

| Tier | Unlocks |
|---|---|
| `SCOUT` | Drill library, session builder. Default at publish. |
| `INSTRUCTOR` | Skills radar assessments, performance reports, drill video upload, 20 GB storage |
| `ACADEMY` | Everything above plus development correlation insights and report branding, 50 GB storage |

The seed sets `ACADEMY` by default; override with `-v coach_tier=INSTRUCTOR`.
It also writes a matching `payment.coach_subscriptions` row so the coach
subscription page agrees, but that row is cosmetic as far as gating goes.

### Player subscription

`payment.player_subscriptions` with `status='ACTIVE'` and a future
`current_period_end`. Note this gates less than you might expect —
`PlayerSubscriptionQueryAdapter` is its only consumer and it drives video
retention policy. It is *not* a prerequisite for booking.

`chk_pps_pro_yearly` and `chk_pps_semi_pro_yearly` force `billing_interval='YEARLY'`
for `PRO` and `SEMI_PRO`; only `ATHLETE` may be `MONTHLY` or `QUARTERLY`.

### Do not call the subscribe endpoints

`POST /api/payment/subscriptions/player/subscribe` and its coach equivalent both
call `stripeClient.createSubscription` and require a saved payment method. Seed
the rows instead. Same for `POST /api/payment/session-packs/purchase`.

---

## What you can and cannot exercise

**Works fully:** registration and email verification, login/refresh/logout,
coach profile builder and publishing, marketplace search and public profiles,
availability management, booking request → accept/decline → payment settlement →
session completion, cancellation and reschedule, credit wallet and statements,
reviews, messaging, homework, drill library and session builder, video upload
(SeaweedFS, via the `storage` service; playback by anyone but the uploader and admins is
blocked, see [Media scanning](#media-scanning-and-video-privacy-s-18)), skills radar and development portal, performance reports, parent and
player dashboards, admin consoles (full and limited tier), the coach safeguarding
badge and the no-show claim flow.

**Cannot be exercised without Stripe:** card entry and `SetupIntent`, Connect
onboarding and the OAuth callback (single-use `state`, payout-destination cooling-off), session pack *purchase* (though pack-based
bookings work fine if you seed a `payment.session_pack_purchases` row —
`handlePackBasedBooking` only decrements a counter and never touches the
gateway), real refunds, cash-out, Stripe Identity age verification, and
webhook-driven subscription lifecycle transitions. Wallet-credit refunds (cancellations,
no-show claims, the 48-hour auto-refund) are internal ledger entries and do work.

**Partly degraded:** any page that mounts Stripe.js will show an empty or
erroring card element, since `app.payment.stripe.publishable-key` is blank.
That affects `CoachPaymentSettingsPage`, `PlayerSubscriptionPage`,
`SessionPackPurchasePage`, and the payment-method card on `BookingRequestPage`.
The rest of those pages still renders.

---

## Stripe actually does work locally

Worth correcting a common assumption: **you do not need a public IP or a
registered webhook endpoint to exercise Stripe from a laptop.**

- Stripe API calls are outbound. Test mode works from behind NAT with no setup.
- The Stripe CLI forwards webhooks over an outbound tunnel it opens itself:

  ```bash
  stripe listen --forward-to localhost:9990/api/payment/webhooks/stripe \
    --forward-connect-to localhost:9990/api/payment/webhooks/stripe/connect
  ```

  It prints a `whsec_...` signing secret; set it as both
  `APP_PAYMENT_STRIPE_WEBHOOK_SECRET` and `APP_PAYMENT_STRIPE_CONNECT_WEBHOOK_SECRET`
  (the CLI signs both streams with the same key). `StripeWebhookResource` is `permitAll()`,
  so nothing else is in the way.

So if you later want the card flows, set a real `sk_test_...` key plus the
matching `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` and run the CLI alongside the
stack. The seeding approach in this document exists to keep manual testing fast
and offline, not because Stripe is unreachable.

---

## Scenarios for the recent safety features

Short hand-checks for the security-hardening round (S-xx are the story ids in the
commit history). Prerequisites for all of them: the stack running, a published
coach, a parent with a player profile, and the seed applied
(`seed-accounts.sql`) so bookings settle without Stripe. SQL runs with
`dcl exec -T postgres psql -U skillars -d skillars -c "..."`. Rows marked
*verified* were executed on 2026-11-02; the rest were checked against the code and
the English i18n strings and need a browser.

### Limited admin vs admin (S-28)

Setup: bootstrap and demote as in `deployment.md`'s
[Step 5](./deployment.md#step-5-create-accounts) (for example
`APP_BOOTSTRAP_ADMIN_EMAIL=ltd@example.com`, then `seed-accounts.sql ... -v ltd_admin_email=ltd@example.com`),
then bootstrap a second address for the full admin. Sign in as each one at
`/#/login`; role changes take effect on the next login (the demotion ends old sessions).

| Call (cookie from a normal login) | `ADMIN` | `LTD_ADMIN` |
|---|---|---|
| `POST /api/auth/login` response | `"limitedAdmin":false` | `"limitedAdmin":true` (`role` stays `ADMIN` for routing) |
| `GET /api/admin/users?q=coach`, `GET /api/admin/queue/summary` | 200 | 200 *(verified)* |
| `GET /api/config/values` | 200 | **403** *(verified)* |
| `GET /api/admin/gdpr/requests` | 200 | **403** *(verified)* |
| `GET /api/admin/safeguarding/requests` | 200 | **403** *(verified)* |

An LTD_ADMIN can read queues, users, bookings, coaches, disputes and appeals and
moderate reviews and messages. It cannot touch money (finance, refunds, payout
release), configuration, GDPR, alert rules, video state, or suspend / reinstate /
strike / unlock anyone, resolve disputes or appeals, force-complete or cancel
bookings, or grant roles (see the matrix in the dev-docs `admin` page). In the UI:
the finance, GDPR and config nav items are hidden; opening `/#/admin/finance`,
`/#/admin/gdpr` or `/#/admin/config` directly lands on
`/#/admin/requires-administrator` ("Administrator access required"); action buttons
on the shared pages are hidden or show "Limited administrator: this action requires
a full administrator."; and any API call is still refused with 403 by the server.

### Safeguarding badge (S-09)

Optional, coach-initiated, ADMIN-only inspection (an LTD_ADMIN gets 403).

1. Coach: `/#/coach/safeguarding` ("Safeguarding check"). Status starts at "Not
   requested". Optionally attach a PDF/JPG/PNG up to 5 MB (this uses the
   `storage` service, so the `/etc/hosts` entry is needed), then "Request
   inspection". Status becomes "Waiting for inspection" with "Your uploaded file
   is deleted as soon as we have inspected it, and in any case on {date}". Without
   a file the note says a staff member will contact you instead.
2. Admin (full): `/#/admin/safeguarding`, tab "Requests". "View document" opens the
   upload (each view is audited); "Mark inspected" confirms, "Reject" refuses.
3. Expect after "Mark inspected": the coach page shows "On record" ("Shown on your
   public profile until {date}", five years by default, `coach.safeguarding.validityYears`);
   the coach's **public profile** shows "Safeguarding check on record (valid until {date})",
   and without a check "No safeguarding check on record" (the check is optional and
   never blocks anything). **The upload is deleted at decision time**: check
   `SELECT coach_id, status, upload_key FROM marketplace.coach_safeguarding_requests;`
   (the request row is gone) and
   `SELECT * FROM marketplace.coach_safeguarding_checks;` (only who/when/until, no
   document data). Uploads nobody processes are swept after 14 days
   (`coach.safeguarding.uploadRetentionDays`).
4. Coach "Withdraw and delete" removes the request, record and file and the badge.

### Age verification of self-registered players (S-27)

Applies only to a self-registered adult player (a parent-managed player is exempt).
The feature is **off by default** (`identity.ageVerification.enabled` has no seed
row and a code default of `false`), and the fallback rule is: the player and a
coach may chat only after at least one **completed** session between them.

1. Register a player at `/#/player-register` (18+), create the player profile, and
   book a coach (the seeded wallet covers it). Booking and paying work with
   verification off.
2. Open a conversation with the coach as that player before any session is
   completed: expect "Chat opens after your first completed session with this
   coach." The coach trying to message
   that player sees the generic "This player has not completed age verification yet,
   so you can't message them for now." The player's age-verification page shows
   "Age verification is not switched on. You can book sessions and pay as usual;
   chat opens after your first completed session with a coach."
3. Complete a session (coach Start, then finish; or Quick Complete after the start
   time, then confirm) and chat opens.
4. To exercise the feature itself: as a full admin set `identity.ageVerification.enabled`
   to `true` in the config console (`PUT /api/config/values/identity.ageVerification.enabled`).
   It also needs a **real** Stripe test key: the local placeholder key
   (`sk_test_local_placeholder`) counts as "configured", so verification is switched on
   but the Stripe call to open the session fails. With a real `sk_test_...` key with
   Stripe Identity enabled, forward webhooks (see [Stripe actually does work
   locally](#stripe-actually-does-work-locally); the four
   `identity.verification_session.*` events go to the account endpoint and use the
   same signing secret) and the player is sent to Stripe, then back to
   `/#/player/age-verification`. Booking and chat are then refused until "Your age is
   verified" (`AGE_VERIFICATION_REQUIRED`); an under-18 result locks the account;
   `identity.ageVerification.maxAttemptsPerDay` (3) caps attempts ("You have used
   today's verification attempts").

### Related-party bookings (S-06)

A booking whose payer is linked to the booked coach is flagged automatically:
the booking still goes through, nobody is alerted, but the payout is held 60 days
(`payment.payout.relatedParty.holdDays`, range 7-120) instead of the usual hold, it
does not count toward ending the new-coach payout hold, and the payer's review of
that coach is stored but hidden and left out of the rating. Two accounts are
"linked" when they share a hashed email, phone, card fingerprint or browser
fingerprint. The comparison is payer vs the booked coach only.

**This bites a solo tester.** Playing both coach and parent from the same browser
profile is exactly the pattern the check looks for: the `fcookie` fingerprint cookie
is per browser profile (and shared across `localhost:9000` and `:9990`), and it is
regenerated to the same value if you delete it. So with both accounts in one
browser every booking is flagged. Also linked: email addresses that differ only by
a `+tag` (`tester+coach@example.com` / `tester+parent@example.com` normalise to the
same address, and for Gmail dots are ignored too) and phone numbers with the same
last 9 digits.

- *To trigger it on purpose* (verified signal storage; the booking flow itself needs
  a browser): sign in as the coach and the parent from the same browser, book,
  coach accepts, booking settles to `CONFIRMED`. Expect
  `SELECT booking_id, matched_kinds, payout_hold_until FROM payment.related_party_bookings;`
  to show a row (`matched_kinds` = `BROWSER`) and an app log line
  `[RELATED_PARTY_FLAGGED] booking=... signals=[BROWSER] holdDays=60`. Check the
  signals: `SELECT u.email, s.kind FROM main.user_identity_signals s JOIN main."user" u ON u.id = s.user_id;`
  (a shared `BROWSER` value shows as the same hash for both accounts; only SHA-256
  hashes are stored). After the family writes a review, the author is told that
  reviews from related accounts are not published or counted
  ("Reviews from accounts that appear to be related to the coach are not published or
  counted.") and the coach's public profile and rating do not include it.
- *To avoid it* (a normal test of the happy path): give each account a different
  `fcookie` before it signs in, either by setting the cookie by hand in devtools
  (Application, Cookies, `localhost`; the app only generates one when none exists), or
  with curl and distinct `-b 'fcookie=...'` values; use different email local parts
  and phones.
- *Self-booking*: a coach booking their own sessions is refused with
  `booking.selfBookingNotAllowed` ("You cannot book your own sessions."). A user holds
  exactly one role, so this cannot be reached with normal accounts; it is covered by
  `BookingServiceTest` and is not reproducible by hand without editing roles in SQL.

### Coach no-show claims (S-07)

Policy: the platform's own record decides. For a captured booking nobody started:

| When | What the family can do |
|---|---|
| before start + 15 min | nothing yet. "You can report a coach no-show from {time}." (`coachNoShowTooEarly`) |
| start + 15 min to start + 5 h | "Coach didn't show" (report) **or** cancel: both are the same claim: full refund, a "Coach no-show" strike for the coach |
| after start + 5 h | cancel is refused with **409 `booking.noShowWindowClosed`**: "The time to report a coach no-show has passed. If nobody records this session, it is refunded automatically." |
| start + 48 h, still nobody recorded it | the platform refunds the payer in full automatically, no strike |

Knobs (platform config): `booking.lateCancel.graceMinutes` (15),
`booking.coachNoShow.reportWindowHours` (5), `booking.unresolvedSession.autoRefundHours`
(48). A coach reporting a **player** no-show has its own gate: from start +
`booking.playerNoShow.graceMinutes` (15).

Also: the coach gets a "Press Start — your session is waiting" email (a file in
`target/mails/`, plus an in-app notification) at the scheduled start and a second at
start + 10 minutes if the session is still unstarted. A payer with 3 coach no-show
claims in 90 days raises one informational admin alert (nothing is blocked).

Recipe: book and settle a session, then back-date it. Waiting for real time is not
needed:

```sql
-- pick the booking
SELECT id, status, requested_start_time FROM booking.bookings ORDER BY requested_start_time DESC LIMIT 3;
-- 20 minutes ago = inside the claim window (use '6 hours' for "window closed", '49 hours' for auto-refund)
UPDATE booking.bookings
   SET requested_start_time = now() - interval '20 minutes',
       requested_end_time   = now() + interval '40 minutes',
       original_start_time  = now() - interval '20 minutes'
 WHERE id = '<booking uuid>';
```

The status moves `CONFIRMED` -> `UPCOMING` within 5 minutes
(`BookingReminderScheduler`); the claim works from either. The auto-refund runs on
the 5-minute `UnresolvedSessionSweeperService` tick; the coach reminders on a
1-minute tick (no reminder for a session more than an hour past its start).

**Quick-complete conflict (50/50).** As the coach press "Quick Complete" on a
session you never started (after its start time), then as the family press "Coach
didn't show". Expect the dialog "The coach marked this session as completed but never
started it in Skillars. Because we cannot tell which account is right, the session fee
is shared equally: you are refunded half and the coach keeps half. No penalty is recorded
for either side." After confirming: half (rounded down to the cent) back to the payer's
wallet, the coach paid the other half after the normal payout hold, no strike. If the coach
**did** press Start, the report is refused ("The coach started this session, so it cannot be
reported as a no-show. Raise a dispute instead.").

### Media scanning and video privacy (S-18)

- **Images, scanning off (the local default, `ARACHNID_ENABLED=false`).** Uploaded
  images (profile photo, gallery, branding) start as `NOT_REQUIRED` and are visible to
  everyone immediately; nothing is held. Check:
  `SELECT scan_status, count(*) FROM main.file_storage_objects GROUP BY 1;`.
- **Images, scanning on.** Export `ARACHNID_ENABLED=true` (Mode B; Mode A would need it
  added to `docker-compose.local.yml`). New images start `PENDING` and only the uploader
  is handed a URL; others see no photo. Without a real `ARACHNID_API_KEY` the scan can
  never succeed, so the object stays `PENDING` (fail closed; the retry sweeper keeps trying
  and eventually alerts the admin). `ALLOW_UNSCANNED_MEDIA` is not relevant here: it only
  relaxes the prod/uat boot check.
- **Videos (always, until a real video scanner exists).** On `/#/coach/videos`
  ("My Videos", also `/#/player/videos`) a banner reads "Videos are reviewed before they
  can be shared. Until then, only you can watch your uploads." Upload a video (needs the
  `storage` host entry), then play it as another account (the family) or anonymously:
  denied; the uploader and admins can play it. This is per video (no `PASSED` record
  from the `VIDEOINTEL` layer, which is a stub). To lift it for local testing export
  `APP_VIDEO_MODERATION_REQUIRES_REAL_SCANNER=false` (Mode B, or add it to the compose
  override).
- Uploads are content-checked: a file whose bytes do not match its declared type
  (e.g. a renamed `.exe` as `image/png`) is rejected at confirm time and the object is
  deleted; `application/octet-stream` is no longer accepted.

### Redis password, Gemini mode, JWT key (S-24, S-20, S-03)

- **Redis password.** Redis refuses to start without one and is reachable only from the
  app (network `skillars-data`). Set `SPRING_DATA_REDIS_PASSWORD` to anything other than
  `dev-redis-password-NOT-for-prod` on the `app` service and restart it *(verified)*: the
  app boots and login still works, but `curl http://localhost:8367/manage/health` returns
  **503 `{"status":"DOWN"}`** with `Redis health check failed ... Unable to connect to
  Redis` in the log, while `/manage/health/smoke` (the Docker healthcheck) stays UP.
- **Gemini mode.** `GEMINI_MODE` defaults to `developer`. The admin health page's
  compliance warning ("Content moderation uses the Gemini developer API, which has no data
  processing agreement or EU data residency...") and the `skillars.gemini.developer_api_in_prod`
  gauge only trigger under the **prod** profile, so a local run shows the component as `UP`
  with `mode: developer` and no warning.
- **JWT key.** The signing key is the `JWT_SIGNING_KEY` env var, not a database row. Changing it
  makes every previously issued token invalid (prod/uat require a random base64 value of 64+ bytes).

### Smaller hardening checks

- **Password change/reset ends every session, including the caller's.** Sign in as one user in two
  browsers (or two cookie jars) and change the password (profile page) or complete a password reset:
  all existing sessions and refresh tokens are revoked, so both browsers must sign in again.
- **Stripe-only items cannot be exercised with the placeholder key:** Connect onboarding (the
  OAuth `state` is single-use) and the payout-destination cooling-off that holds payouts after a
  coach changes their destination, and the Stripe customer / payment-method deletion during GDPR
  erasure (needs a reachable Stripe; not exercised with the placeholder key).
- **Wrong activation keys** are 22-character URL-safe tokens (older 10-digit keys still work);
  email-verification tokens are UUIDs, as in the SQL under [Creating the accounts](#creating-the-accounts).

---

## Setup verification checklist

Run through this to confirm your local environment is ready:

- [ ] **Infrastructure up:**
  ```bash
  dcl ps
  # Should show postgres, redis, storage, and (Mode A) app all running/healthy
  ```

- [ ] **App health:**
  ```bash
  curl http://localhost:8367/manage/health
  # Should return 200 with {"status":"UP","groups":["smoke"]}
  # (Mode A) or after mvn spring-boot:run (Mode B)
  ```

- [ ] **Frontend reachable:**
  - Mode A: http://localhost:9990 (loads UI + API)
  - Mode B: http://localhost:9000 (Quasar dev server)

- [ ] **Can register as coach:**
  - Navigate to registration page
  - Email must use valid TLD (e.g., `coach@example.com`, not `coach@local`)
  - Verify email link (fetch token from DB or open the newest file in `target/mails/`)
  - Login succeeds

- [ ] **Can register as parent + player:**
  - Parent registration works
  - Can create a player profile (parent-created shadow account)
  - Player visible in parent dashboard

- [ ] **Coach profile builder:**
  - Complete all 5 steps (display name, specialty, age group, pricing, availability)
  - Click "Publish" at end (this is load-bearing — creates marketplace rows)

- [ ] **Seed accounts:**
  ```bash
  dcl exec -T postgres psql -U skillars -d skillars \
    -v coach_email=coach@example.com \
    -v owner_email=parent@example.com \
    < docs/deployment/local/seed-accounts.sql
  ```
  - Output should show coach payment readiness, credit balance, and player subscriptions
  (it applied cleanly against `V223` on 2026-11-02)

- [ ] **Test a booking:**
  - Parent searches for coach
  - Parent requests a session
  - Coach accepts
  - Booking settles to `CONFIRMED` (no Stripe charge, paid from seeded credit)
  - If you used one browser for both accounts, expect it to be flagged as a
    related-party booking (see [Related-party bookings](#related-party-bookings-s-06))

If any step fails, jump to [Troubleshooting](#troubleshooting) below.

---

## Troubleshooting

**`AppSetupException: app.payment.stripe.api-key is missing or empty`** — see
[blocker 1](#startup-blockers-not-covered-by-the-deployment-guide) (export
`APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder`).

**`BunnyVideoProviderAdapter: app.video.bunny.library-id must be numeric, got: `**
— export `APP_VIDEO_BUNNY_LIBRARY_ID=123456` (Mode B).

**`AppSetupException: app.security.jwt.signing-key ... must be set`** — `JWT_SIGNING_KEY`
is blank. Under `dev` and in tests a default applies; a blank value from your own
compose file or shell overrides it, so unset it (or set a base64 value,
`openssl rand -base64 64`).

**`psql: FATAL: role "postgres" does not exist`** — the role is `POSTGRES_USER` from
`.env.local`, `skillars` if copied from `.env.example`. Use `-U skillars`.

**Mode B: `Connection refused` to Postgres or Redis** — those ports are not published
by default; layer `docker-compose.host-ports.yml` (see [Mode B](#mode-b--infra-in-docker-app-and-ui-on-the-host-recommended-when-changing-code)).
A host Postgres already on `5432` is why the file defaults to `5433`.

**`/manage/health` is `503 DOWN` but the container is healthy and login works** — a
dependency outside the smoke group is failing; with the full stack it is almost always
Redis (password mismatch between `app` and `redis`). Read the `... health check failed`
WARN in `dcl logs app`.

**HTTP 500 on register with `ConstraintViolationException ... propertyPath=login`**
— the email's TLD is longer than three characters. Use `example.com`.

**HTTP 401 `security.unauthorized` on login with correct credentials** — missing
`fcookie`; see the API gotchas above.

**HTTP 429 while registering/verifying many accounts** — the per-IP limits (10
`verify-email` calls per 10 minutes) and the per-account login bucket; wait or reuse accounts.

**`payment.coachStripeNotConfigured` on booking request** — the coach has no
`payment.coach_stripe_accounts` row, or the seed ran against the wrong email.
Re-run the seed and read its verification output.

**Booking stuck in `PAYMENT_PENDING`** — credit balance was below the session
price, so it tried to charge Stripe and failed. Check the balance against
`marketplace.coach_pricing.per_session_price`; add more credit and rebook (the
existing row cannot be repaired, since payment already recorded a failure).

**Seed reports 0 rows for the coach** — the coach profile was never published, so
there is no `marketplace.coach_profiles` row in a usable state. Finish the
profile builder first. (`INSERT 0 0` lines with empty verification tables mean
the e-mail addresses matched no account.)

**A self-registered player's seed says 0 rows for players** — they have not yet
created their own player profile; do that in the UI first.

**Chat is refused for a self-registered player** — expected until a first completed
session with that coach (or until age verification, if enabled); see
[Age verification](#age-verification-of-self-registered-players-s-27).

**A coach's payout never releases / a review is missing** — check
`payment.related_party_bookings` (the account pair shares a browser fingerprint, email
or phone), see [Related-party bookings](#related-party-bookings-s-06).

**Registration succeeds but no verification link** — see "Creating the
accounts" above. Both modes run the `log` transport, so no email is sent: look
in `target/mails/` (newest file, or grep for the recipient) — in Mode A check the
bind mount works. Query `main.email_verification_tokens` as shown above instead —
the token is written regardless of whether the email send itself succeeds. This
does **not** work for the OTP email, though — its code is only ever stored as a
keyed hash, so `target/mails/` is the only place to read it.

**Login returns "Account is not activated"** — email verification has not been
completed. `activated` flips at email verification, not at registration.

---

## Tips

**Database data survives container restarts.** `docker-compose.local.yml`
overrides the base file's production bind-mount
(`/opt/skillars/data/postgres`) with a named Docker volume —
`skillars-local-postgres:/var/lib/postgresql/data` — declared under that
file's own `volumes:` section. Named volumes are independent of container
lifecycle:

- `docker compose stop`/`restart`, or even `docker compose down` (without
  `-v`) — data survives.
- `docker compose down -v` (or `docker volume rm skillars_skillars-local-postgres`) —
  data is wiped.
- A full rebuild (`docker compose build` / `up --build`) — data survives,
  since the volume isn't part of the image.

The same pattern applies to redis, loki, tempo, prometheus, grafana, and the
SeaweedFS `storage` service in that file — each has its own
`skillars-local-*` named volume.

**`docker compose ps` / `docker ps` shows the app container `(unhealthy)` even
though `curl http://localhost:8367/manage/health` returns `UP`.** (The reverse also
happens: a `503 DOWN` there with a healthy container, see Troubleshooting.) These are two
different endpoints. Docker's own `HEALTHCHECK` (`docker-compose.yml`, `app`
service) does not poll the default `/manage/health` group — it polls
`/manage/health/smoke` instead (`db`, `diskSpace`, `ping` only), on purpose:
the default group is *every* registered Actuator health contributor
(including Redis and the mail-transport/SES indicators), so a wrong Redis
password or a sandboxed SES account would otherwise mark the container
unhealthy while the app itself is working fine. `/manage/health` being green
tells you nothing about what Docker is actually checking — query the smoke group directly:

```bash
# same mapped port, different path:
curl -s http://localhost:8367/manage/health/smoke | jq

# or exactly as Docker's HEALTHCHECK runs it, from inside the container:
dcl exec app wget -qO- http://localhost:8367/manage/health/smoke
```

That narrows it down to which of `db`/`diskSpace`/`ping` is actually failing.
Also check whether your image is stale before chasing this further — an old
image predating a recent fix, or a long-running container that never picked
up a rebuild, is a common enough cause on its own. Compare build ages with
`docker images | grep skillars` against `git log -1 --oneline`; if the image
predates your latest merged commit, rebuild and restart:

```bash
dcl build app
docs/deployment/local/stop.sh
docs/deployment/local/start.sh
```

(See the `docker-compose.local.yml` comment on `app`'s own `build:` key —
Compose silently no-ops a build for a service with no `build:` key, which has
cost real debugging time before: a shipped fix looked broken because the
running jar predated it.)

**Testing code changes made while the stack was already running (Mode A).**
Neither `start.sh` nor plain `up -d` ever rebuilds the image — they only
(re)create containers from whatever image already exists. Editing source
while `app` is running has no effect on it until you rebuild and recreate
that one container:

```bash
# 1. Rebuild the image from your changed source
dcl build app

# 2. Recreate just the app container from the new image
dcl up -d app
```

Or combine both in one command: `... up -d --build app`.

No need to run `stop.sh` first — Compose diffs the image and recreates only
`app`; Postgres, Redis, Grafana, etc. keep running unchanged, so whatever test
data you already created survives. Watch it come back healthy with
`dcl logs -f app`, or poll `docker inspect --format='{{.State.Health.Status}}'
skillars-app-1` until it reports `healthy` (first boot after a rebuild takes
~15-90s for Flyway + health checks).

One caveat: this only picks up **backend** (Java) changes on its own.
`Dockerfile` bundles the built frontend into the jar at image-build time, so a
rebuild does also pick up frontend changes — just via the slower full
`npm`/`quasar build` step baked into the image build. If you're only iterating
on frontend code, Mode B (`quasar dev` on the host) avoids that rebuild cost
entirely.

**Testing code changes made while the stack was stopped.** `start.sh` has the
same limitation as above — it never builds, it only starts containers from
whatever image already exists. Running it straight after `git pull` or local
edits just relaunches the stale image you had before (or, on a from-scratch
checkout with no image built yet, fails outright). Build before you start,
not after:

```bash
dcl build app
docs/deployment/local/start.sh
```

This is the same two steps as the already-running case above — the only
difference is there's no running container yet to recreate in place, so
`start.sh`'s own `up -d` creates it fresh instead.
