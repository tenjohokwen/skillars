# Local Manual Testing — Paid Coach + Paid Parent/Player, Without Stripe

How to run the whole product on your own machine and click through every
workflow with a fully paid coach and a fully paid parent/player, while making
zero calls to Stripe.

This is the *manual testing* companion to
[`docs/deployment/local-deployment.md`](../../../docs/deployment/local-deployment.md),
which covers bringing the Docker stack up. Read that one for the infrastructure
detail (volumes, MinIO, Grafana, log tailing, teardown); this one covers
choosing a run mode, getting accounts through registration without a working
mailbox, and seeding the payment state that would normally come from Stripe.

- [Which run mode](#which-run-mode)
- [Startup blockers not covered by the deployment guide](#startup-blockers-not-covered-by-the-deployment-guide)
- [Creating the accounts](#creating-the-accounts)
- [Making them paid, without Stripe](#making-them-paid-without-stripe)
- [What you can and cannot exercise](#what-you-can-and-cannot-exercise)
- [Stripe actually does work locally](#stripe-actually-does-work-locally)
- [Troubleshooting](#troubleshooting)

---

## Which run mode

The Maven build compiles the Quasar frontend and `maven-resources-plugin` copies
`src/frontend/dist/spa` into `target/classes/static` (see the `skipFrontend`
comment in `pom.xml`). **The packaged jar therefore serves the UI itself** — the
Docker image is the whole product, not just an API.

That gives two clean modes. Pick by whether you are testing or changing things.

### Mode A — all-Docker (recommended for pure manual testing)

Everything in containers, UI and API together on one port. Closest to what UAT
and production actually run.

```bash
alias dcl='docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local'

docker build -t skillars:local .
dcl up -d app postgres redis minio minio-init grafana
```

App at **http://localhost:9990**, health at **http://localhost:8367/manage/health**.

One catch: the `dev` profile hardcodes `app.frontend-url: "http://localhost:9000"`
in `application-dev.yaml` — no `${...}` placeholder, so **it cannot be overridden
by an environment variable**. Verification links generated during registration
will point at port 9000, which nothing is serving in this mode. Swap the host to
`localhost:9990` by hand when you paste the link (see
[Creating the accounts](#creating-the-accounts)).

Cost: a full rebuild (Maven + npm + `quasar build`) for every source change.

### Mode B — infra in Docker, app and UI on the host (recommended when changing code)

```bash
dcl up -d postgres redis minio minio-init          # + grafana if you want it
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

## Startup blockers not covered by the deployment guide

`docs/deployment/local-deployment.md` was last updated 2026-08-11. Several
things have changed since. Running it surfaced three separate hard crash loops,
which surfaced one at a time in the order below. Two of them were real bugs
affecting production as much as local, and have been fixed in the code; only the
first still needs an environment variable, and `docker-compose.local.yml` already
carries it. If you are running Mode B, export it yourself.

### 1. The app will not start without a Stripe API key

`PaymentConfig.configureStripe()` throws `AppSetupException` when
`app.payment.stripe.api-key` is blank:

```
app.payment.stripe.api-key is missing or empty. Stripe integration requires a valid API key.
```

It defaults to `""` in `application.yaml`, `application-dev.yaml` does not
override it, and neither `docker-compose.yml` nor `docker-compose.local.yml`
passes it. This fail-fast landed in `82a89a9` (2026-08-27), *after* the
deployment guide was last touched, so the guide's startup steps are incomplete.

Add a placeholder to `docker-compose.local.yml` under `app.environment`:

```yaml
      - APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder
```

or, in Mode B, export `APP_PAYMENT_STRIPE_API_KEY` before `mvn spring-boot:run`.

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
- `127.0.0.1 minio` in `/etc/hosts`, or browser-side presigned uploads (coach
  profile photos, drill videos) cannot resolve the upload host.

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

Skip `initTestData.sql` too — its `main.authority` inserts carry no `ON CONFLICT`
clause, while migrations `V21` and `V92` seed those same role names with
`ON CONFLICT (name) DO NOTHING`, so the fixture hits the unique constraint on
`authority.name`. Every role the registration flows need is already seeded by
Flyway.

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

Register through the UI like a real user. Two things make this different from
production.

**Emails never arrive.** `SesEmailServiceImpl` is annotated `@Profile("!dev")`,
so under the `dev` profile the `NoOpSesEmailService` bean wins and logs only the
subject:

```
NoOp SES: email suppressed — subject=...
```

The verification token is still written to the database, so pull it from there.

**Phone OTP is already disabled.** Migration `V85` seeds
`security.registration.phone-otp-required=false`. `AuthService.login` only
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

Run [`seed-local-test-accounts.sql`](seed-local-test-accounts.sql) once the
accounts above exist:

```bash
dcl exec -T postgres psql -U postgres -d skillars \
  -v coach_email=coach@example.com \
  -v owner_email=parent@example.com \
  < requirements/deployment/local/seed-local-test-accounts.sql
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
`CASH_OUT_REVERSAL`, so the seed uses `BOOKING_REFUND`. And `V79` installed
triggers that reject `UPDATE` and `DELETE` on the table — it is append-only, so
re-running the seed **adds** credit rather than resetting it, and a mistake can
only be offset by another row, never corrected.

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
(MinIO), skills radar and development portal, performance reports, parent and
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

**Registration succeeds but no verification link** — expected. Emails are
suppressed under `dev`; query `main.email_verification_tokens` as shown above.

**Login returns "Account is not activated"** — email verification has not been
completed. `activated` flips at email verification, not at registration.
