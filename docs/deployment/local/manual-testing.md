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
mailbox, and seeding the payment state that would normally come from Stripe.

- [Which run mode](#which-run-mode)
- [Startup blockers not covered by the deployment guide](#startup-blockers-not-covered-by-the-deployment-guide)
- [Creating the accounts](#creating-the-accounts)
- [Making them paid, without Stripe](#making-them-paid-without-stripe)
- [What you can and cannot exercise](#what-you-can-and-cannot-exercise)
- [Stripe actually does work locally](#stripe-actually-does-work-locally)
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
| Closest to production | Yes | No (but faster iteration) |

### Mode A — all-Docker (recommended for pure manual testing)

Everything in containers, UI and API together on one port. Closest to what UAT
and production actually run.

```bash
alias dcl='docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local'

docker build -t skillars:local .
dcl up -d app postgres redis storage storage-init grafana
```

App at **http://localhost:9990**, health at **http://localhost:8367/manage/health**.

`docker-compose.local.yml` sets `APP_FRONTEND_URL=http://localhost:9990` for the
`app` service, so verification links generated during registration already point
here — no manual edit needed. (Without that override, the base `docker-compose.yml`'s
own default of `https://skillars.com` wins: Spring environment variables rank above
`application-dev.yaml` regardless of whether its `app.frontend-url` value is a
placeholder or a literal, so an unset override silently sends every local
verification link to production.)

Cost: a full rebuild (Maven + npm + `quasar build`) for every source change.

### Mode B — infra in Docker, app and UI on the host (recommended when changing code)

```bash
dcl up -d postgres redis storage storage-init          # + grafana if you want it
mvn spring-boot:run -Dspring-boot.run.profiles=dev -DskipFrontend
cd src/frontend && npx quasar dev                  # serves :9000
```

UI at **http://localhost:9000**, proxying `/api` → `localhost:9990`
(`devServer.proxy` in `quasar.config.js`). Backend restarts in seconds, frontend
has hot reload, and a debugger attaches normally. `app.frontend-url` already
matches port 9000, so verification links are correct as-generated.

`-DskipFrontend` skips the five `frontend-maven-plugin` executions. Never set it
for anything you intend to deploy — the resulting jar ships with no UI at all.

---

## Environment variables reference

These are all the variables you may need to set depending on your mode. Defaults are provided in `docker-compose.local.yml` and `application-dev.yaml` where applicable.

| Variable | Used in | Required? | Mode A | Mode B | Purpose |
|----------|---------|-----------|--------|--------|---------|
| `APP_PAYMENT_STRIPE_API_KEY` | Both | Yes | Already in compose | Export before `mvn` | Placeholder to satisfy PaymentConfig validation |
| `APP_EMAIL_TRANSPORT` | Mode B only | No (with defaults) | Not settable — never allowed in a committed compose file (`NoHardcodedSenderTest`) | Not set — defaults to `smtp` (`application-dev.yaml`); export it yourself for `log` | Switch mail to file-dump (`log`, writes to `target/mails/`) instead of SMTP |
| `GMX_PASSWORD` | Both | No (with defaults) | Defaults to a placeholder that fails SMTP auth (mail never sent) | Export if real mail needed | GMX SMTP credentials for registration email |
| `GMAIL_PASSWORD` | Both | No (with defaults) | Defaults to a placeholder that fails SMTP auth (mail never sent) | Export if real mail needed | Gmail SMTP credentials for registration email |
| `APP_VIDEO_BUNNY_LIBRARY_ID` | Both | Yes | Already in compose (123456) | Already in dev profile | Bunny CDN library ID |
| `MANAGEMENT_HEALTH_MAIL_ENABLED` | Both | Yes | Already in compose | Already in dev profile | Disable Mail health check |
| `APP_STORAGE_ENDPOINT_URL` | Mode A | Yes | Already in compose | N/A | SeaweedFS (S3-compatible) endpoint for file uploads |
| `APP_STORAGE_S3_ACCESS_KEY` | Both | Yes | Already in compose | Already in dev profile | SeaweedFS access key |
| `APP_STORAGE_S3_SECRET_KEY` | Both | Yes | Already in compose | Already in dev profile | SeaweedFS secret key |
| `APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET` | Both | Yes | Already in compose | Already in dev profile | Placeholder so `BunnyVideoProviderAdapter` doesn't abort on a blank secret |
| `APP_VIDEO_PLAYBACK_SIGNING_SECRET` | Both | Yes | Already in compose | Already in dev profile | Placeholder for signed video playback URLs |
| `PLATFORM_PIN_ENCRYPTION_SECRET` | Both | Yes | Already in compose | Already in dev profile | Encrypts stored PINs |
| `PLATFORM_REGISTRATION_VERIFICATION_SECRET` | Both | Yes | Already in compose | Already in dev profile | Signs registration verification tokens |
| `APP_SES_FROM_ADDRESS` | Both | No (with default) | Already in compose | Already in dev profile | From-address; unused while `app.email.transport=smtp`/`log` |
| `GEMINI_API_KEY` | Both | No | Already in compose (dev-key) | Already in dev profile | For AI features |
| `127.0.0.1 storage` | Both | Yes | Add to `/etc/hosts` | Add to `/etc/hosts` | Required for browser to reach SeaweedFS presigned URLs (see `deployment.md`) |

**Mode A quick setup:**
```bash
echo "127.0.0.1 storage" | sudo tee -a /etc/hosts
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d app postgres redis storage storage-init
```

**Mode B quick setup:**
```bash
echo "127.0.0.1 storage" | sudo tee -a /etc/hosts
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d postgres redis storage storage-init
export APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder
mvn spring-boot:run -Dspring-boot.run.profiles=dev -DskipFrontend
cd src/frontend && npx quasar dev
```

---

## Startup blockers not covered by the deployment guide

[`deployment.md`](./deployment.md) was last updated 2026-09-28. Running the
stack from scratch previously surfaced three separate hard crash loops, listed
below in the order they appeared historically. Two were real bugs affecting
production as much as local, and have been fixed in the code. The first
(Stripe) is no longer something you need to add yourself — the checked-in
`docker-compose.local.yml` already carries the placeholder, and
`deployment.md`'s Step 3 now shows it — but the underlying validation is worth
understanding if you ever see the error below in Mode B or after editing the
override.

### 1. The app will not start without a Stripe API key

`PaymentConfig.configureStripe()` throws `AppSetupException` when
`app.payment.stripe.api-key` is blank:

```
app.payment.stripe.api-key is missing or empty. Stripe integration requires a valid API key.
```

It defaults to `""` in `application.yaml` and `application-dev.yaml` does not
override it. `docker-compose.yml` has passed `APP_PAYMENT_STRIPE_API_KEY`
through (with an empty default) since 2026-09-02, but that alone doesn't help
locally — nothing sets the variable in your shell — so `docker-compose.local.yml`
supplies a literal placeholder value directly. This fail-fast landed in
`82a89a9` (2026-08-27).

If you're following `deployment.md`'s Step 3 override as shown, this is
already handled:

```yaml
      - APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder
```

In Mode B, export `APP_PAYMENT_STRIPE_API_KEY` yourself before `mvn spring-boot:run`,
since that mode never reads `docker-compose.local.yml`.

The value is never used by anything in this guide. The adjacent guard in the
same method only rejects keys matching `^(sk|rk)_live_`, so any test-shaped
placeholder passes — and a real live key would be refused outside the `prod`
profile regardless, which is the point of that guard.

### 2. Fixed: the Loki appender no longer crashes the app

Recorded here because the symptom is distinctive and the history explains the
shape of the current config. **No environment variable is needed any more** —
`LOKI_ENABLED=true` (the value `docker-compose.yml` sets) now works, and app logs
reach the local Loki container.

`logback-spring.xml` had been written against the loki4j **1.x** schema
(`<http class="com.github.loki4j.logback.JavaHttpSender">`). Dependabot bumped
`loki-logback-appender` 1.5.2 → 2.1.0 in `039b1a8` (PR #48, 2026-08-13) without
updating the XML, so logback failed with `IncompatibleClassException`, and since
Spring Boot treats a logback configuration error as fatal the JVM died inside
`LoggingApplicationListener` before any bean existed — a hard restart loop that
would have hit prod and UAT identically.

Fixing it surfaced two further problems that the crash had been masking:

- **`<root>` cannot be declared inside `<if>`.** Logback processes `<root>` in an
  earlier phase, so branch-local `<root>` elements are silently dropped and the
  context ends up with *no appenders at all*. The config now defines the appender
  conditionally and references it from a single unconditional `<root>`, with
  `NOPAppender` on the disabled branch so the reference never dangles.
- **`<labels>` is newline-separated in 2.x**, not comma-separated; the old
  one-line form throws `Unable to split ... to key-value pairs`.

A correction to an earlier version of this note: the nested `<if>` was *not*
inert. Parsing the pre-fix file directly shows the appender-ref still took effect,
so log shipping did work while logback merely warned about the unsupported shape.
What stopped logs after 2026-08-13 was the appender failing to build at all.

The remaining gap is CI: `src/test/resources/logback-test.xml` shadows
`logback-spring.xml`, so no test ever loads the production logging config. That
is why the Dependabot bump merged green, and it will let the same class of
regression through again.

### 3. Fixed: dev no longer builds an AWS SES client

Also no environment variable any more. `application-dev.yaml` used to set
`app.ses.enabled: true`, which could not work on this profile in two independent
ways at once:

- `SesEmailServiceImpl` is `@Profile("!dev")` and `NoOpSesEmailService` only
  matches `havingValue = "false"`, so `true` left `SesEmailService` with **zero**
  implementations.
- `SesConfig.sesV2Client` has no profile restriction, so dev still constructed a
  real AWS SES client, which died with
  `NoClassDefFoundError: org/apache/hc/client5/http/io/HttpClientConnectionManager`.

That `NoClassDefFoundError` was a genuine packaging bug, not a dev-only quirk:
the AWS SDK BOM resolves `software.amazon.awssdk:apache5-client` (built on Apache
HttpClient 5) onto the compile classpath, while `pom.xml` declared
`httpclient5` at **test** scope for WireMock. Maven's nearest-definition rule let
that direct declaration win, stripping httpclient5 from the shipped jar while
leaving httpcore5 behind — so **any** AWS SDK sync client would have failed at
runtime in every environment, with all tests still passing. Both are fixed:
`app.ses.enabled: false` on dev, and `httpclient5` at default (compile) scope.

### 4. Still applies from the deployment guide

- `APP_VIDEO_BUNNY_LIBRARY_ID=123456` and `MANAGEMENT_HEALTH_MAIL_ENABLED=false`
  in `app.environment` — both fix real startup/health failures.
- `127.0.0.1 storage` in `/etc/hosts`, or browser-side presigned uploads (coach
  profile photos, drill videos) cannot resolve the upload host — see
  `deployment.md`'s [Step 3](./deployment.md#step-3-the-local-compose-override)
  for why.
- The full current list of `app.environment` entries (storage keys, video
  signing secrets, PIN/registration secrets) lives in `deployment.md`'s Step 3
  and in the checked-in `docker-compose.local.yml` itself — this list only
  covers the ones with a distinct startup-failure story worth telling.

### 5. Do NOT seed the JWT secret under the `dev` profile

The deployment guide presents `secData.sql` as mandatory. Under `dev` it is not,
and running it **fails**:

```
ERROR: duplicate key value violates unique constraint "sec_version_bus_id_key"
```

`application-dev.yaml` sets `app.bootstrap.jwt-secret.enabled: true`, so
`JwtSecretBootstrapRunner` writes the row itself on first start
(`JWT secret bootstrap created a new active JWT signing secret`). Only seed it by
hand on a profile that does not have that runner enabled.

Skip `initTestData.sql` too. Its `main.authority` insert was since patched
with `ON CONFLICT (name) DO NOTHING`, so it no longer collides with the role
rows Flyway seeds (previously cited as migrations `V21`/`V92`; the whole
migration history was squashed on 2026-09-24 by `skillars-deferred-112` into
`V138__baseline_schema.sql` + `V139__baseline_seed_data.sql`, which now seed
those roles). It still fails under `dev`, though — its very first statement,
`INSERT INTO main.sec` (`src/test/resources/sql/initTestData.sql:11`), has no
`ON CONFLICT` at all, and `JwtSecretBootstrapRunner` has already written that
row by the time you'd run this, so it hits the same `sec_version_bus_id_key`
unique-constraint error described above for `secData.sql`. Every role the
registration flows need is already seeded by Flyway.

### 6. Fixed: access logs, previously broken everywhere

You may remember this on every boot:

```
ERROR AccessLogValve - Failed to create directory [/usr/local/var/ledger] for access logs
```

Tomcat's stock file-based access log valve was pointed at an absolute path the
image's non-root `appuser` cannot create, so it never produced an access log in
any environment — and had it worked, the file would have sat inside the container
with nothing collecting it. Tomcat logged the error and carried on, which is why
it survived as background noise for so long.

Access logs are now emitted through SLF4J instead, so they travel the same
logback pipeline as everything else and land in Loki:

| Logger | Server |
|---|---|
| `skillars.access` | main app, port 9990 |
| `skillars.access.management` | actuator, port 8367 |

The management logger is a child of the main one: setting `skillars.access` to
`OFF` silences both, while the management half can be silenced alone — worth
considering, since that port carries the healthcheck every 30s plus Prometheus
scrapes and will dominate the volume. `APP_ACCESS_LOG_ENABLED=false` turns both
off. The pattern is unchanged from the old one and lives at
`app.access-log.pattern`.

---

## Creating the accounts

Register through the UI like a real user. Two things worth understanding as
you do — neither is really a difference from production any more, just a
local wrinkle:

**No real mail arrives locally by default, in either mode.**
`application-dev.yaml` sets `app.email.transport: smtp` — the same kind of
real delivery path production uses (production instead runs `ses`) — and this
applies to Mode A and Mode B alike: the env var that switches transport to
`log` (file-dump) is deliberately never allowed to appear in a committed
compose file (`NoHardcodedSenderTest` enforces this — see
`requirements/ses-email-consolidation.md#4.5`), so Mode A cannot override it
that way. `application-dev.yaml` also defaults `GMX_PASSWORD`/`GMAIL_PASSWORD`
to literal placeholder strings (`dev_gmx_password`/`dev_gmail_password`)
whenever the real env vars aren't set, and with those in place the send fails
SMTP authentication server-side (the envelope is recorded `FAILED`, then
retried and eventually exhausted; nothing reaches an inbox). In Mode B you can
still get the file-dump behavior for yourself, uncommitted, by exporting that
transport override before `mvn spring-boot:run` (it lands in your own
`target/mails/`) — or set real `GMX_PASSWORD`/`GMAIL_PASSWORD` in either mode
to actually receive mail.

The verification token is always written to the database regardless of
whether the email send itself succeeds, so pulling it from there (see "Fetch
the token" below) remains a valid — often faster — alternative that works
either way. **The OTP is different: it is not recoverable from the database at
all.** `phone_otp_tokens.otp_hash` stores `SHA-256(otp + userId)`
(`CoachRegistrationService.hashOtp` and its parent/player counterparts), a
one-way hash, so `target/mails/` (or a real inbox) is the only way to read an
OTP short of brute-forcing the hash. In practice you can usually skip this —
see "Phone OTP is already disabled" below.

**Phone OTP is already disabled.** `V139__baseline_seed_data.sql:146` seeds
`security.registration.phone-otp-required=false` (previously cited as
migration `V85`, before the 2026-09-24 migration squash). `AuthService.login` only
demands `BASIC_VERIFIED` when that flag is true, and `activated = true` is set at
*email* verification (`ParentRegistrationService:141` and its coach/player
counterparts). Email verification alone is enough to log in — you can ignore the
`verify-phone` step the UI offers you.

### Steps

1. Register at `/#/coach/register`, `/#/parent/register`, or `/#/player/register`
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

6. **Parent only:** create at least one player profile.

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
Any non-blank value works locally:

```bash
curl -s -X POST http://localhost:9990/api/auth/login \
  -H 'Content-Type: application/json' \
  -b 'fcookie=local-manual-test-fingerprint' \
  -d '{"email":"coach@example.com","password":"Passw0rd!23"}'
# {"userId":"882672139209216818","role":"COACH","displayName":"Co"}
```

---

## Making them paid, without Stripe

Run [`seed-accounts.sql`](./seed-accounts.sql) once the
accounts above exist:

```bash
dcl exec -T postgres psql -U postgres -d skillars \
  -v coach_email=coach@example.com \
  -v owner_email=parent@example.com \
  < docs/deployment/local/seed-accounts.sql
```

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

Two constraints shape how the seed is written. `chk_ledger_amount_sign` permits a
positive amount only for `BOOKING_REFUND`, `BOOKING_DEDUCTION_REVERSAL` and
`CASH_OUT_REVERSAL`, so the seed uses `BOOKING_REFUND`. And triggers
`trg_ledger_no_update`/`trg_ledger_no_delete` (`V138__baseline_schema.sql`,
previously cited as migration `V79` before the 2026-09-24 migration squash)
reject `UPDATE` and `DELETE` on the table — it is append-only, so re-running
the seed **adds** credit rather than resetting it, and a mistake can only be
offset by another row, never corrected.

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
(SeaweedFS, via the `storage` service), skills radar and development portal, performance reports, parent and
player dashboards, admin health dashboard.

**Cannot be exercised without Stripe:** card entry and `SetupIntent`, Connect
onboarding and the OAuth callback, session pack *purchase* (though pack-based
bookings work fine if you seed a `payment.session_pack_purchases` row —
`handlePackBasedBooking` only decrements a counter and never touches the
gateway), real refunds, cash-out, and webhook-driven subscription lifecycle
transitions.

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
  stripe listen --forward-to localhost:9990/api/payment/webhooks/stripe
  ```

  It prints a `whsec_...` signing secret; set it as
  `APP_PAYMENT_STRIPE_WEBHOOK_SECRET`. `StripeWebhookResource` is `permitAll()`,
  so nothing else is in the way.

So if you later want the card flows, set a real `sk_test_...` key plus the
matching `APP_PAYMENT_STRIPE_PUBLISHABLE_KEY` and run the CLI alongside the
stack. The seeding approach in this document exists to keep manual testing fast
and offline, not because Stripe is unreachable.

---

## Setup verification checklist

Run through this to confirm your local environment is ready:

- [ ] **Infrastructure up:**
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.local.yml ps
  # Should show postgres, redis, storage, and (Mode A) app all with status "running"
  ```

- [ ] **App health:**
  ```bash
  curl http://localhost:8367/manage/health
  # Should return 200 with {"status":"UP",...}
  # (Mode A) or after mvn spring-boot:run (Mode B)
  ```

- [ ] **Frontend reachable:**
  - Mode A: http://localhost:9990 (loads UI + API)
  - Mode B: http://localhost:9000 (Quasar dev server)

- [ ] **Can register as coach:**
  - Navigate to registration page
  - Email must use valid TLD (e.g., `coach@example.com`, not `coach@local`)
  - Verify email link (fetch token from DB or use real SMTP credentials)
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
  docker compose -f docker-compose.yml -f docker-compose.local.yml exec -T postgres psql -U postgres -d skillars \
    -v coach_email=coach@example.com \
    -v owner_email=parent@example.com \
    < docs/deployment/local/seed-accounts.sql
  ```
  - Output should show coach payment readiness, credit balance, and player subscriptions

- [ ] **Test a booking:**
  - Parent searches for coach
  - Parent requests a session
  - Coach accepts
  - Booking settles to `CONFIRMED` (no Stripe charge, paid from seeded credit)

If any step fails, jump to [Troubleshooting](#troubleshooting) below.

---

## Troubleshooting

**`AppSetupException: app.payment.stripe.api-key is missing or empty`** — see
[blocker 1](#1-the-app-will-not-start-without-a-stripe-api-key).

**Container restart-loops with `Logback configuration error detected`** — fixed;
you should not see this. If you do, you are on an image built before the loki4j
2.x config fix — rebuild. Note the app dies before any application logging
exists, so `dcl logs -f app` shows only the logback error and a stack trace, with
no Spring banner. See [note 2](#2-fixed-the-loki-appender-no-longer-crashes-the-app).

**`NoClassDefFoundError: org/apache/hc/client5/...` on bean `sesV2Client`** —
fixed; rebuild if you see it. See
[note 3](#3-fixed-dev-no-longer-builds-an-aws-ses-client).

**`AppSetupException: JWT secret key has not been set in DB`** — only possible on
a profile without `app.bootstrap.jwt-secret.enabled`. Under `dev` the row is
created automatically; do not seed `secData.sql` (see
[blocker 5](#5-do-not-seed-the-jwt-secret-under-the-dev-profile)).

**HTTP 500 on register with `ConstraintViolationException ... propertyPath=login`**
— the email's TLD is longer than three characters. Use `example.com`.

**HTTP 401 `security.unauthorized` on login with correct credentials** — missing
`fcookie`; see the API gotchas above.

**`payment.coachStripeNotConfigured` on booking request** — the coach has no
`payment.coach_stripe_accounts` row, or the seed ran against the wrong email.
Re-run the seed and read its verification output.

**Booking stuck in `PAYMENT_PENDING`** — credit balance was below the session
price, so it tried to charge Stripe and failed. Check the balance against
`marketplace.coach_pricing.per_session_price`; add more credit and rebook (the
existing row cannot be repaired, since payment already recorded a failure).

**Seed reports 0 rows for the coach** — the coach profile was never published, so
there is no `marketplace.coach_profiles` row in a usable state. Finish the
profile builder first.

**Registration succeeds but no verification link** — see "Creating the
accounts" above. Both modes run the `smtp` transport by default, so check
`GMX_PASSWORD`/`GMAIL_PASSWORD` are set to real credentials rather than the
placeholder defaults; with placeholders, the send fails SMTP auth and no
email arrives. Query `main.email_verification_tokens` as shown above instead
— the token is written regardless of whether the email send itself succeeds.
This does **not** work for the OTP email, though — its code is only ever
stored as a hash in the database, so a real inbox (or, in Mode B only,
exporting the `log`-transport override before `mvn spring-boot:run` to dump
it to `target/mails/`) is the only place to read it.

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
- `docker compose down -v` (or `docker volume rm skillars-local-postgres`) —
  data is wiped.
- A full rebuild (`docker compose build` / `up --build`) — data survives,
  since the volume isn't part of the image.

The same pattern applies to redis, loki, tempo, prometheus, grafana, and the
SeaweedFS `storage` service in that file — each has its own
`skillars-local-*` named volume.

**`docker compose ps` / `docker ps` shows the app container `(unhealthy)` even
though `curl http://localhost:8367/manage/health` returns `UP`.** These are two
different endpoints. Docker's own `HEALTHCHECK` (`docker-compose.yml`, `app`
service) does not poll the default `/manage/health` group — it polls
`/manage/health/smoke` instead (`db`, `diskSpace`, `ping` only), on purpose:
the default group is *every* registered Actuator health contributor
(including mail-transport/SES indicators), so a sandboxed SES account or a
missing IAM grant would otherwise mark the container unhealthy while the app
itself is working fine. `/manage/health` being green tells you nothing about
what Docker is actually checking — query the smoke group directly:

```bash
# same mapped port, different path:
curl -s http://localhost:8367/manage/health/smoke | jq

# or exactly as Docker's HEALTHCHECK runs it, from inside the container:
docker exec skillars-app-1 wget -qO- http://localhost:8367/manage/health/smoke
```

That narrows it down to which of `db`/`diskSpace`/`ping` is actually failing.
Also check whether your image is stale before chasing this further — an old
image predating a recent fix, or a long-running container that never picked
up a rebuild, is a common enough cause on its own. Compare build ages with
`docker images | grep skillars` against `git log -1 --oneline`; if the image
predates your latest merged commit, rebuild and restart:

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local build app
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
docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local build app

# 2. Recreate just the app container from the new image
docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local up -d app
```

Or combine both in one command: `... up -d --build app`.

No need to run `stop.sh` first — Compose diffs the image and recreates only
`app`; Postgres, Redis, Grafana, etc. keep running unchanged, so whatever test
data you already created survives. Watch it come back healthy with
`docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file
.env.local logs -f app`, or poll `docker inspect --format='{{.State.Health.Status}}'
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
docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local build app
docs/deployment/local/start.sh
```

This is the same two steps as the already-running case above — the only
difference is there's no running container yet to recreate in place, so
`start.sh`'s own `up -d` creates it fresh instead.
