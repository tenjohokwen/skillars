# Story UAT.1: Admin Account Bootstrap & Coach-Onboarding Unblock

Status: review

> **Outstanding before this can be trusted end-to-end: the frontend work is unverified by anything
> but code reading and a successful production build.** There is no frontend test suite in this repo
> and this story deliberately did not add one, so the timezone picker (AC4) and the availability-page
> timezone source (AC5) have no automated coverage at all. Four specific behaviours need a human
> spot-check or an agent with browser tooling — they are listed in the Completion Notes. Do not read
> the green build as covering them.

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the person standing this app up on a VPS for UAT,
I want an admin account to be creatable at all, a coach to be unable to lock themselves out of the profile builder, and the handful of small defects that will distort the first UAT session fixed,
so that all four account types can exist, a coach can publish and appear in search, and the first round of UAT tests the product rather than the setup.

### Why this story exists

Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` (written 2026-08-09 against commit `a170e69`), which ranks the open `deferred-work.md` backlog against one goal — deploy to a VPS, create a player/parent/coach/admin account, log in, search a coach, pay, book.

This story takes the items from that ranking that need **no product decision** and groups them into one dev pass. Three of the five P0s (P0-2 player self-booking, P0-4 coach self-serve subscription, P0-5 session-duration cap) are product decisions and are deliberately excluded — see "Items examined and NOT folded in".

Every claim below was re-verified by direct read of the working tree at commit `bf9c828` on 2026-08-10. Nothing is taken on the ledger's word.

| AC | Source item | Verified current state (2026-08-10, `bf9c828`) |
|---|---|---|
| AC1–AC3 | **P0-1** — *not tracked in `deferred-work.md` at all* | **CONFIRMED.** `main.authority` is seeded with exactly three rows: `(100,'ROLE_COACH')`, `(101,'ROLE_PARENT')` (`V21__skillars_security_extension.sql:35-39`) and `(102,'ROLE_PLAYER')` (`V84__player_self_registration.sql:5-8`). Grepping every `.sql` under `src/main/resources/db/migration` for `ROLE_ADMIN` / `ROLE_LTD_ADMIN` returns **zero** hits. There is no admin registration endpoint (only `CoachRegistrationResource`, `ParentRegistrationResource`, `PlayerRegistrationResource`), no `ApplicationRunner`/`CommandLineRunner` anywhere in `src/main/java`, and no mention of "admin" in `docs/deployment/first-time-setup.md` or `docs/deployment/uat-deployment.md`. Meanwhile `SecurityConstants.HAS_ADMIN_ROLE` (`SecurityConstants.java:34`) gates 30+ endpoints across `admin`, `payment`, `config`, `video`, `notification` and `security`. **One of the four UAT account types cannot be created by any supported means.** |
| AC4 | **P0-3** — `deferred-18` D5 | **CONFIRMED.** `ProfileBuilderStep1.vue:90` and `ProfileBuilderStep4.vue:75` both do `const canonicalTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone` and send it verbatim (`Step1.vue:143`, `Step4.vue:101`). There is no zone picker and no fallback. `ProfileBuilderStep1Request.canonicalTimezone` and `ProfileBuilderStep4Request.AvailabilityWindowRequest.canonicalTimezone` are both `@NotBlank @IanaTimezone`, and `IanaTimezoneValidator.java:19` is a bare `ZoneId.of(value)` — so a browser on newer tzdata than the deployed JVM (`Europe/Kyiv`, tzdata 2022b; `America/Ciudad_Juarez`, 2022g) makes Step 1 and Step 4 **uncompletable**, with a validation error naming the coach's own machine and no way to override. The blast radius is larger than D5 records: `CoachProfileService.getOrCreateDraft:71` and `saveStep1:81` both start the profile as `DRAFT`, only `publishProfile:204-210` sets `ACTIVE`, and `CoachSearchSpecification.java:38` returns only `ACTIVE`/`REDUCED` — **a coach who cannot finish the builder never appears in search**, so this one defect breaks both "create a coach account" and "search a coach". |
| AC5 | **P1 #4** — `deferred-17` D4 | **CONFIRMED.** `AvailabilityManagerPage.vue:333` reads `coachTimezone.value = store.windows[0].canonicalTimezone ?? 'UTC'` — the `coach_availability_windows.canonical_timezone` column that `deferred-17` AC4 exists to stop displaying from, one page over. `booking.store.js:101,180,561` already exposes `coachTimezone`, populated from `res.canonicalTimezone` by the very `loadAvailability()` call this page makes at `:332`, and is simply unused. The same line also dereferences `windows[0]` after only a `.length > 0` check — safe today, but it is the reason the fallback exists. |
| AC6 | **P1 #5** — `deferred-16` D1 | **CONFIRMED.** Four `switch` expressions raise `IllegalArgumentException` on an unknown role string: `MessagingService.java:339` (`verifyIsParty`), `:436` (`resolveLastReadAt`), `:446` (`updateLastRead`), and `MessagingReportService.java:144` (its own `verifyIsParty` copy). `MessagingApiAdvice.java:20` handles only `OperationNotAllowedException`, so these produce a **500 with a stack trace** rather than the 403 `messaging.notAParty` the resolver's own fallback produces (`MessagingResource.java:236-238`). Latent — `MessagingResource.resolveRole:220-239` guarantees one of the three values — but the guard and the throws live in different classes with no shared enum, so the invariant is convention only. |
| AC7 | **P1 #6** — `deferred-18` D1 | **CONFIRMED.** `AvailabilityService.java:134-135` materializes `windowStart`/`windowEnd` via `date.atTime(...).atZone(windowZoneId).toInstant()`, and `LocalDateTime.atZone()` silently shifts a nonexistent local time forward by the DST gap. The DB guard `chk_availability_time_order CHECK (end_time > start_time)` (`V26:56`) constrains only **local** times, so it cannot stop the instants inverting. `computeAvailableSlots(windowStart, windowEnd, occupied)` is called unconditionally at `:156` and seeds its segment list with `{windowStart, windowEnd}`, so the API emits an `AvailableSlotResponse` with `startDatetime` after `endDatetime`; the frontend renders it clickable and submitting hits `BookingService`'s "end must be after start" check → 400 behind a generic toast. |
| AC8 | **P2 #1** — `deferred-17` D7 | **PARTIALLY CLOSED — re-scoped.** The documentation half is already done: `docs/deployment/local-deployment.md:26-32` explicitly says *"there's no `build:` section, so build the image yourself first"* and gives `docker build -t skillars:local .`. What remains is the trap itself — neither `docker-compose.yml` (`:9`, `image: ${APP_IMAGE}`) nor `docker-compose.local.yml` carries a `build:` key, so `docker compose build app` still exits 0 having done nothing and you deploy a stale jar. This already cost the `deferred-17` dev significant time. **Scope reduced accordingly**: add the `build:` key, do not re-document what is documented. |
| AC9 | **Ledger hygiene** (5 rows) | **CONFIRMED as described in the priorities doc.** Four `deferred-work.md` items are provably out of date and one should be re-scoped; leaving them makes the ledger budget work that is already done. Verified independently: `ci.yml:4` reads `branches: [master]`; `docker-compose.uat.yml:4` sets `SPRING_PROFILES_ACTIVE=uat`. |

### Items examined and NOT folded in

Recorded so the next pass does not re-litigate them:

- **P0-2 — a player account cannot book anything.** `BookingResource:36` and `BookingBatchResource:40` are both `@PreAuthorize(HAS_PARENT_ROLE)`; a self-registered adult player can register, log in and browse, and stops there. This is a **product decision** ("scope the player journey to register + browse" vs. "build player self-booking"), not a bug. If self-booking is built, `deferred-16` story-creation D1 (adult player has `parent_id IS NULL`, `messaging.conversations.parent_id` is `NOT NULL` → 500) stops being theoretical and must ship alongside. Untouched.
- **P0-4 — a coach cannot subscribe through the UI.** `CoachSubscriptionPage.vue:118` still renders a raw `pm_...` text input. The blocker is structural: `StripeCustomer`'s `@Id` is `parent_id`, and both `POST /api/payment/setup-intent` and `GET /api/payment/payment-method` are `@PreAuthorize(HAS_PARENT_ROLE)`. Needs a schema migration plus a design decision (second customer table vs. re-key `payment.stripe_customers`). Coach search is **not** tier-gated (`CoachSearchSpecification` filters on profile status only), so an unsubscribed coach is still findable and bookable — UAT can proceed by pasting a test-mode `pm_...`. `deferred-11` D3 should be folded into that story when it is written, not tracked separately.
- **P0-5 — one click books an 8-hour lesson** (`deferred-17` D1). The single highest-impact functional defect on the journey, and the one the first UAT booking will hit. Excluded **only** because slot slicing vs. a session-duration field is a product decision. This should be story #2 immediately after this one, covering both `BookingService` and the missing availability-window check in `BookingBatchService:106-113`.
- **P1 #1 — a parent's own pending request makes the slot vanish** (`deferred-18` D3). Backend behaviour is correct; the missing piece is a "you already requested this" UX state, which needs booking data `BookingRequestPage.vue` no longer loads. Product decision about that page. The priorities doc explicitly sequences it *after* testers have hit it.
- **P1 #2 / #3 — payment-integrity races** (`deferred-12` D2, `deferred-15` story-creation D1). Both narrow, both survivable in Stripe test mode, both better scheduled after the first round of UAT payment testing tells you whether they fire at your volumes.
- **P1 #7 / #8 — `formatSlot` hardcodes `'en'` (`deferred-17` D3) and `ApiAdvice` can never resolve a non-English bundle (`deferred-18` D6).** Same condition: they only matter if UAT is not English-only. #7 is a systemic 4+-page sweep; #8 needs all three `chosenLang` call sites in `ApiAdvice` plus `VideoApiAdvice:155`. Do them as one i18n story or not at all.
- **`deferred-18` D4 — `@IanaTimezone` accepts fixed offsets.** Deliberately kept as-is. The 2026-08-07 decision (Mbah) chose to reword the message rather than tighten to region zones, precisely because tightening makes D5 (this story's AC4) **strictly worse**. AC4 fixes D5 by giving the coach a server-validated choice; it does **not** tighten the validator. Do not tighten it here.
- **`deferred-17` D8 / `deferred-18` D2 — reconciling the two `canonical_timezone` columns.** Still blocked on a migration, a backfill rule, and a product decision on whether per-window zones are a feature. AC4 makes Step 4 *default* to Step 1's choice, which reduces divergence for **new** coaches; it does **not** remove the columns or backfill existing rows. D8 stays open.
- **All of P2 items 2–5 and everything in P3.** Production hardening for a system with no production traffic, plus the chronically re-deferred set every reviewer has already decided is not worth the cost.

## Acceptance Criteria

### AC1 — `ROLE_ADMIN` and `ROLE_LTD_ADMIN` exist in `main.authority`

New Flyway migration `src/main/resources/db/migration/V92__seed_admin_authorities.sql` (V91 is the current highest — verify before writing). Follow `V84__player_self_registration.sql:5-8` **exactly**, including the `ON CONFLICT (name) DO NOTHING` (`authority.name` is `UNIQUE`, `V10__security_schema.sql:7`) so the migration is safe against a database where someone already hand-inserted the rows:

```sql
INSERT INTO main.authority (id, name, status, created_by, created_date)
VALUES
    (103, 'ROLE_ADMIN',     'ACTIVE', 'system', NOW()),
    (104, 'ROLE_LTD_ADMIN', 'ACTIVE', 'system', NOW())
ON CONFLICT (name) DO NOTHING;
```

Ids `103`/`104` continue the hand-assigned sequence (100 COACH, 101 PARENT, 102 PLAYER). Both names must match `SecurityConstants.ROLE_ADMIN` / `ROLE_LTD_ADMIN` (`SecurityConstants.java:17-18`) character for character — `HAS_ADMIN_ROLE` is a string expression, so a typo fails silently as a 403.

**Do not** seed a user row from SQL. `User` ids come from `@Tsid` (`BaseEntity.java:27`), not a sequence, and the password must be a real bcrypt hash — both belong in Java (AC2).

### AC2 — A first admin user can be created without hand-written SQL

New opt-in bootstrap component in `com.softropic.skillars.platform.security.service` (a `platform` module — this is business logic about users, so it must not go in `infrastructure`; see `project-context.md` §4).

- **Properties.** New `@ConfigurationProperties(prefix = "app.bootstrap.admin")` record/class following the existing convention (`SecurityProperties`, `PaymentProperties`): `email`, `password`, `firstName`, `lastName`, `phone`. Register defaults in `application.yaml` under the existing `app:` block (alongside `app.config`, `app.toggles`) with **empty defaults** — `${APP_BOOTSTRAP_ADMIN_EMAIL:}` etc. Never put a literal password in any yaml or `.env.example`.
- **Trigger.** An `ApplicationRunner` (there is none in this codebase today — this is the first) that **no-ops silently unless both `email` and `password` are non-blank**. It must not be `@Profile`-gated: production boots with no `SPRING_PROFILES_ACTIVE` at all (documented at `PaymentConfig.java:24-25`), so a profile guard would fail-close in exactly the environment that needs it.
- **Normalize the email once, before anything else touches it.** The very first statement after the enablement check must be `String login = properties.getEmail().trim().toLowerCase(Locale.ROOT);`, and **that single value must be used for the existence lookup and for every field it populates** (`setLogin`, `setEmail`) and for the INFO log. Do not read `properties.getEmail()` again anywhere below that line.

  **This is not a style preference — normalizing in only one of the two places crashes the app on the second boot.** `UserRepository.findOneByEmail` (`UserRepository.java:31`) is a derived Spring Data query with no `@Query` and no `lower()`, so it is **case-sensitive**, while `login` and `email` are both `TEXT UNIQUE NOT NULL` (`V10__security_schema.sql:19,39`). Concrete failure if the lookup uses the raw value and the write uses the lowercased one: operator sets `APP_BOOTSTRAP_ADMIN_EMAIL=Admin@Company.com` → first boot finds nothing, stores `admin@company.com` → **second boot with the same variables still set** (exactly the `docker compose up` re-run this AC's idempotency contract exists to cover) looks up `Admin@Company.com`, still finds nothing because the stored row is lowercased, falls through to `save()`, and hits the unique constraint. A `DataIntegrityViolationException` thrown from `ApplicationRunner.run` propagates out of `SpringApplication.run` and **fails startup** — the bootstrap turns a working UAT box into one that will not boot.

- **Idempotency.** If `userRepository.findOneByEmail(login)` is present, log at INFO that bootstrap was skipped and return. Re-running `docker compose up` must never fail or duplicate.

  **Additionally, wrap the `save()` in a `catch (DataIntegrityViolationException)` that logs at WARN and returns rather than rethrowing** — mirroring `CoachRegistrationService.java:99-101`, but swallowing instead of translating, because there is no caller to report to. The normalization above removes the self-inflicted collision; this catch covers the one it cannot: P0-1 forces operators to hand-insert admin rows via raw SQL today, so a UAT database may already hold an admin at a *different* casing or with a colliding phone. A pre-existing row must make the runner skip, never make the application fail to start.
- **The user row must satisfy every gate on the login path.** Build it exactly like `CoachRegistrationService.registerCoach:80-95`, with these deviations, each of which is load-bearing:
  - `setActivated(true)` — `AuthService.java:95-97` throws `DisabledException` otherwise. **Also**: `UserAdminService.removeNotActivatedUsers` is a `@Scheduled(cron = "0 0 1 * * ?")` job that **deletes** non-activated users past the expiry window. A bootstrap admin left `activated=false` would silently vanish overnight.
  - `setVerificationStatus(SkillarsVerificationStatus.BASIC_VERIFIED)` — `AuthService.java:99-103` refuses login when `skillarsRole != null && phoneOtpRequired && verificationStatus != BASIC_VERIFIED`, and `security.registration.phone-otp-required` **defaults to `true`**. There is no OTP flow for an admin, so `BASIC_VERIFIED` is the only value that permits login.
  - `setSkillarsRole(SkillarsRole.ADMIN)` — the enum value already exists (`SkillarsRole.java:4`) and `GdprErasureService.java:149` compares against it. Note `AuthService.java:121` reads `skillarsRole != null ? name() : "ADMIN"`, so leaving it null would report role `ADMIN` in the login response while granting no authority — a confusing half-state. Set it.
  - `setStatus(EntityStatus.ACTIVE)`.
  - `setAuthorities(Set.of(...))` resolved via `authorityRepository.findOneByName(SecurityConstants.ROLE_ADMIN).orElseThrow(...)` — same shape as `CoachRegistrationService.java:77-78`. Grant `ROLE_ADMIN` only, not `ROLE_LTD_ADMIN`.
  - `setPassword(passwordEncoder.encode(rawPassword))` — never store or log the raw value. `password_hash` is `@Size(min = 60, max = 60)` (`User.java:62-64`), which a bcrypt hash satisfies; a plaintext value would fail validation.
  - `setPhone(new PhoneNumber(phone, "XX"))` mirroring `CoachRegistrationService.java:87`. `PhoneNumber.phone` and `.iso2Country` are both `@NotEmpty` (`PhoneNumber.java:18-27`) so the embeddable cannot be half-populated, and `user.phone` carries a `UNIQUE` constraint — which is why the phone is a **required** property when bootstrap is enabled rather than a hardcoded placeholder that would collide on a second admin. Fail fast with `AppSetupException` (`infrastructure.exception.AppSetupException`, the type `PaymentConfig.java:44` uses) if `email`/`password` are set but `phone` is blank.
  - `setLogin(login)`, `setLoginIdType(LoginIdType.EMAIL)`, `setEmail(login)`, `setGender(Gender.OTHER)`, `setDateOfBirth(LocalDate.of(1900, 1, 1))`, `setLangKey("en")` — same as the coach path, using the normalized `login` value from the first bullet. Storing lowercased is mandatory, not cosmetic: `AuthService.java:85` looks the user up as `findOneByLogin(email.toLowerCase())`, so a row stored with any uppercase character can never be logged into at all.
- **Logging.** One INFO line on creation carrying the email and the granted authority; **never** the password, and never at a level that would place it in Loki alongside a secret. Follow the `kv(...)` structured-argument style used across `security` (e.g. `AdminLoginResource.java:52-55`).

### AC3 — The admin bootstrap is documented, with the UAT test-account set

`docs/deployment/uat-deployment.md` gains a section covering:
- The two `APP_BOOTSTRAP_ADMIN_*` environment variables, wired into `docker-compose.uat.yml`'s `app.environment` block alongside the existing `APP_PAYMENT_STRIPE_*` entries, with the same comment style those carry.
- The exact sequence: set the vars → `docker compose up -d` → confirm the INFO log line → **unset the password variable and redeploy** so the credential does not live in the environment of a long-running container.
- A short "UAT test accounts" table naming the four accounts to create and how each is created: **coach** and **parent** via the public registration UI, **player** via player self-registration, **admin** via this bootstrap.
- **An explicit caveat that there is no admin UI.** `src/frontend/src/pages/admin/` contains only `HealthDashboardPage.vue`, `TenantListPage.vue` and `TenantDetailPage.vue`, and the tenant module was removed in `a170e69` — so the two tenant pages and their routes (`routes.js:322-331`) are dead. Every `HAS_ADMIN_ROLE` surface (moderation queue, disputes, coach enforcement, config, GDPR tools) is **API-only** for UAT: authenticate against `POST /api/auth/login` and drive the endpoints with curl/Postman using the returned cookies. State this plainly so a tester does not report "the admin account doesn't work" when there is simply nothing to click.

`.env.example` gains the two variables with empty values and a one-line comment.

`docs/deployment/secrets-reference.md` gains all three `APP_BOOTSTRAP_ADMIN_*` entries in the server `.env` table. That document claims to list **every** secret needed to run the application, so omitting them would leave it wrong. Flag their lifecycle explicitly: they are the only entries in that table meant to be *removed* again after use, which is worth stating because every other row is permanent.

### AC4 — A coach can always pick a timezone the server will accept

The lockout is closed by letting the coach **choose** from the server's own zone set, not by loosening validation.

- **New endpoint** on `ProfileBuilderResource` (do not create a new resource class): `GET /api/marketplace/coaches/me/profile/timezones`, `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` like every other method on that class, returning a sorted `List<String>` derived from `java.time.ZoneId.getAvailableZoneIds()`. Deriving it from the **JVM's** set is the whole point: it is the set `IanaTimezoneValidator` will accept. Note the path sits under `AppEndpoints.PUBLIC_ENDPOINTS`' `/api/marketplace/coaches/**` pattern (`AppEndpoints.java:39`), so the filter chain permits it and the method-level `@PreAuthorize` is the guard — the same arrangement the `/api/reviews/coaches/**` entry documents one line down. No `AppEndpoints` change is needed.

- **Filter the raw set before returning it — do not serve `getAvailableZoneIds()` verbatim.** Measured on JDK 17.0.1, the raw set is **600 ids**, of which **38 carry no `Continent/City` structure** (`Navajo`, `W-SU`, `Kwajalein`, `Turkey`, `Poland`, `Egypt`, `Eire`, `GB-Eire`, `NZ-CHAT`, `PRC`, `ROK`, `Zulu`, `Universal`, `UCT`, `GMT0`, `CET`, `EET`, `MET`, `WET`, `CST6CDT`, `EST5EDT`, `MST7MDT`, `PST8PDT`, …) and a further **35 sit under `Etc/`**, 29 of them the fixed-offset `Etc/GMT±N` block. Nothing is *broken* by them — they are tzdb backward-compatibility links and all resolve correctly (`ZoneId.of("Navajo")` has the same rules as `America/Denver`) — but they are obsolete, ambiguous, or literal UTC synonyms sitting in a 600-row searchable picker, and the `Etc/GMT±N` entries are **DST-blind**, the exact hazard `deferred-18` D4 records. Serving them reintroduces a smaller version of the timezone confusion this story exists to remove.

  The rule: **keep ids containing `/`, then exclude the `Etc/` prefix, then add back `Etc/UTC`** as the single explicit UTC option (there is no region-form UTC zone, and a coach genuinely operating on UTC needs one entry). That yields **528** on JDK 17.0.1 — 527 region zones plus `Etc/UTC`. Assert the shape in the IT, not the exact count: the number moves with the JDK's tzdata, so pin *properties* (non-empty, sorted, contains `Europe/Berlin` and `Etc/UTC`, contains no `Navajo`, contains no id matching `^Etc/GMT[+-]`) rather than `hasSize(528)`.

  This is a **display filter only**. Do not change `IanaTimezoneValidator` — a coach whose zone was already stored as `Navajo` or `+01:00` before this story must keep working everywhere else in the system.

- **New shared component** `src/frontend/src/components/profileBuilder/TimezoneSelect.vue` — a `q-select` with `use-input` + filtering (still ~528 entries; an unfiltered select is unusable), `<script setup>`, all labels via `vue-i18n`.
- **Both `ProfileBuilderStep1.vue` and `ProfileBuilderStep4.vue` must stop sending `Intl.DateTimeFormat().resolvedOptions().timeZone` unconditionally** (`Step1.vue:90`, `Step4.vue:75`). Replace with: preselect the browser zone **only if the server list contains it**; otherwise leave the field empty and show the existing warning-style hint. One normalization before that test: a browser reporting the bare string `UTC` (common on Linux and inside containers) must map to `Etc/UTC`, which the filtered list carries — without it, a UTC tester lands in the empty-with-hint state for no reason. Extend each `submit()` guard (`Step1.vue:136`, `Step4.vue:96`) so submission is blocked while the zone is unset — the same early-`return` shape both already use. A coach on an unknown zone now sees a picker, not a 400.
- **Step 4 defaults to the zone chosen in Step 1.** Add a `selectedTimezone` ref to `profileBuilder.store.js`, set by Step 1's submit, read by Step 4 as its initial value, falling back to the validated-browser-zone rule above when the store is empty (a coach resuming the builder in a fresh session). Step 4 still sends a `canonicalTimezone` per window — **do not change `ProfileBuilderStep4Request`'s shape**; the two columns stay independently writable (`deferred-17` D8 is explicitly out of scope, see above). This only makes them agree by default for new coaches.
- Load the zone list **once** — cache it on the store, not per component mount. Step 1 → Step 4 within one session must not refetch.
- **Do not change `IanaTimezone`, `IanaTimezoneValidator`, `ProfileBuilderStep1Request` or `ProfileBuilderStep4Request`.** The 2026-08-07 decision to keep `ZoneId.of` stands (`deferred-18` D4).
- New i18n keys go in **all four** bundles under `src/frontend/src/i18n/` (`en`, `en-US`, `de`, `fr-FR`) under the existing `auth.coach.*` namespace — note `en-US` and `fr-FR` are the only two the language switcher offers (`MainLayout.vue:236-239`), but all four must carry the key or the missing-key warning fires.

### AC5 — `AvailabilityManagerPage` displays the coach-profile timezone, not `windows[0]`'s

Replace `AvailabilityManagerPage.vue:332-334`'s post-`loadAvailability` block so `coachTimezone` is sourced from `store.coachTimezone` (already populated by that same call — `booking.store.js:180`), keeping the existing `'UTC'` fallback for a null value:

```js
await store.loadAvailability(coachId.value, currentWeekStart.value)
coachTimezone.value = store.coachTimezone ?? 'UTC'
```

This deletes the unguarded `store.windows[0]` dereference along with the wrong column. `coachTimezone` stays a local `ref('UTC')` (`:152`) feeding `:coach-timezone` (`:24`) and the `:290` formatter — do not restructure those.

### AC6 — An unrecognised messaging role yields 403, not 500

Replace the four `default -> throw new IllegalArgumentException("Unknown messaging role: " + role)` arms — `MessagingService.java:339`, `:436`, `:446` and `MessagingReportService.java:144` — with the same `OperationNotAllowedException` the resolver's own unrecognised-role fallback raises (`MessagingResource.java:236-238`):

```java
default -> throw new OperationNotAllowedException(
    "Caller does not hold a recognised messaging role",
    MessagingErrorCode.NOT_A_PARTY);
```

`MessagingApiAdvice.java:32-33` maps any `OperationNotAllowedException` outside the `INVALID_CONTENT` / `ALREADY_REPORTED` / `ALREADY_DELETED` set to `403 FORBIDDEN`, so this reaches the client as `messaging.notAParty` — matching what the pre-`deferred-16` silent-`PLAYER` fallback produced, without reintroducing the silent fallback. Both `updateLastRead` (a statement `switch`) and the three expression `switch`es take the same replacement. Keep every other arm unchanged.

### AC7 — A DST-inverted availability window emits no slot

In `AvailabilityService.getAvailabilityCalendar`, guard the `computeAvailableSlots` call at `:156` on `windowEnd.isAfter(windowStart)`. When the guard fails, `continue` to the next window and log at WARN with the coach id, window id and both local times — a coach with a window straddling a spring-forward gap is a data problem someone should see, not something to swallow.

Place the guard **after** `windowStart`/`windowEnd` are materialized (`:134-135`) and **before** the `occupied` list is built (`:137`) so an inverted window costs no filtering work. Leave `computeAvailableSlots` itself unchanged — the guard belongs at the call site, where the two instants exist, not inside a method that would then need a new failure mode.

Concrete case to pin in the test: `Europe/Berlin`, window `02:30`–`03:00` on **2026-03-29**. `02:30` falls in the spring-forward gap and `atZone` shifts it to `01:30Z`, while `03:00` resolves to `01:00Z` — so `windowStart > windowEnd`. Before this AC the endpoint returns a slot with `startDatetime` after `endDatetime`; after it, that window contributes nothing.

### AC8 — `docker compose build` actually builds

Add a `build:` key to the `app` service in `docker-compose.local.yml` (the local override — **not** `docker-compose.yml`, which is the production file that must keep pulling `${APP_IMAGE}` from GHCR, and **not** `docker-compose.uat.yml`, which the VPS also drives from the published image):

```yaml
services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
```

`docker compose -f docker-compose.yml -f docker-compose.local.yml build app` must then produce a fresh image rather than exiting 0 having done nothing. Add one sentence to `docs/deployment/local-deployment.md` Step 1 noting that `docker compose build app` now works when the local override is passed — the standalone `docker build -t skillars:local .` instruction at `:30` stays, since it is what the doc's own env-var flow (`APP_IMAGE=skillars:local`) depends on.

### AC9 — Five stale ledger entries corrected

Edit `_bmad-output/implementation-artifacts/deferred-work.md`. Delete the entries that are closed; re-scope the two that are partly true. Do **not** silently rewrite history — each edit carries a one-line note naming this story and the date, matching how `deferred-13`/`-14`/`-16` recorded their own deletions.

| Item | Action | Evidence |
|---|---|---|
| `deferred-10` D1 | **Delete.** | `ci.yml:4` reads `branches: [master]`, not `[main]`. The image pipeline triggers correctly. |
| `deploy-2-1` D1 | **Re-scope to prod-only.** | `docker-compose.uat.yml:4` sets `SPRING_PROFILES_ACTIVE=uat`. Still true for `docker-compose.yml` and the `Dockerfile` `ENTRYPOINT` — narrow the entry to those two, do not delete it. |
| `deferred-16` D8 | **Downgrade wording.** | The claim "currently fails on `master`" is no longer true — CI has been green. The test is still structurally flaky (it asserts on `latencies[99]`, the max of 100 un-warmed samples). Rewrite as "flaky perf assertion", keep the item open. |
| `deferred-9` D2 | **Re-scope.** | Premise dead: `en-US` is now 1167 lines with 178 `booking` keys, `de` 1191 and `fr-FR` 1190 both with `booking` blocks. Rewrite to what is actually left: `de` is not selectable (`MainLayout.vue:236-239` offers only `en-US`/`fr-FR`), `de` is not renamed `de-DE`, `en` survives as a redundant fourth bundle, and `fr-FR` has 154 booking keys against `en-US`'s 178. **Re-count these figures before writing them** — they were measured on 2026-08-09. |
| `deferred-11` D3 | **Annotate, do not delete.** | Still accurate, and it is the same root cause as P0-4. Mark it "fold into the coach-subscription story" so it is not scheduled as independent work. |

Additionally, append a `## Deferred from: skillars-uat-1 ...` section recording anything this story's implementation finds and does not fix, following the existing section convention.

## Tasks / Subtasks

- [x] **Task 1 — Admin authority seed (AC1)**
  - [x] Confirm the highest existing migration version (expected `V91`); create `V92__seed_admin_authorities.sql`
  - [x] Seed `(103,'ROLE_ADMIN')` and `(104,'ROLE_LTD_ADMIN')` with `ON CONFLICT (name) DO NOTHING`
  - [x] IT: after context start, `SELECT name FROM main.authority` contains both, and running the migration path twice does not error
- [x] **Task 2 — Admin bootstrap runner (AC2)**
  - [x] `AdminBootstrapProperties` under `app.bootstrap.admin` + empty-default entries in `application.yaml`
  - [x] `AdminBootstrapRunner implements ApplicationRunner` in `platform.security.service`; no-op when email/password blank; `AppSetupException` when enabled but phone blank
  - [x] Normalize the email **once** at the top (`trim().toLowerCase(Locale.ROOT)`) and use that one value for the lookup, `setLogin`, `setEmail` and the log — never re-read the raw property below that line
  - [x] Build the `User` exactly per AC2's field list (`activated=true`, `BASIC_VERIFIED`, `SkillarsRole.ADMIN`, `EntityStatus.ACTIVE`, `ROLE_ADMIN` authority, bcrypt password)
  - [x] Idempotent on `findOneByEmail(login)`; `catch (DataIntegrityViolationException)` → WARN + return, never rethrow; INFO log without the password
  - [x] Unit test: disabled → no repository interaction; enabled → user built with each required field; already-exists → skipped
  - [x] Unit test, **mixed-case regression**: configure `Admin@Company.com`, run the runner twice against a repository holding the row it created on the first pass, assert the second pass performs **no save** and throws nothing. This is the case that would otherwise fail startup — it must fail if the normalization is reverted to a raw-value lookup. Record the mutation check.
  - [x] IT: bootstrap an admin, then `POST /api/auth/login` succeeds and a `HAS_ADMIN_ROLE` endpoint returns 2xx (not 403) for that session
- [x] **Task 3 — Bootstrap documentation (AC3)**
  - [x] `docs/deployment/uat-deployment.md`: bootstrap section (Step 8), UAT test-account table, no-admin-UI caveat
  - [x] `docker-compose.uat.yml` `app.environment` entries; `.env.example` entries; merged compose re-validated with `docker compose config`
- [x] **Task 4 — Timezone picker (AC4)**
  - [x] `GET /api/marketplace/coaches/me/profile/timezones` on `ProfileBuilderResource` + service method, applying AC4's filter (keep `/`, drop `Etc/`, add back `Etc/UTC`)
  - [x] `TimezoneSelect.vue`; store caching of the list and `selectedTimezone`
  - [x] Rewire `ProfileBuilderStep1.vue` and `ProfileBuilderStep4.vue`; extend both submit guards; map a browser-reported bare `UTC` to `Etc/UTC` before the contains-test
  - [x] i18n keys in all four bundles
  - [x] IT: endpoint 403s for a non-coach, and returns a list that is non-empty, sorted, contains `Europe/Berlin` and `Etc/UTC`, excludes `Navajo`, and matches nothing against `^Etc/GMT[+-]`. **Assert properties, not `hasSize`** — the count moves with the JDK's tzdata. Added a further assertion that every offered id survives `ZoneId.of`, so the endpoint cannot drift from the validator.
- [x] **Task 5 — Availability page timezone source (AC5)**
  - [x] `AvailabilityManagerPage.vue:332-334` → `store.coachTimezone ?? 'UTC'`
- [x] **Task 6 — Messaging role 403 (AC6)**
  - [x] Replace all four `default ->` arms
  - [x] IT: drive a service method with an unrecognised role string and assert **403 + `messaging.notAParty`**, not 500. Reverting one arm must fail this test — state the mutation check in the completion notes. **Scope corrected during implementation:** only two of the four arms are reachable by any caller; the test asserts those two and records why the other two are not (deferred D2).
- [x] **Task 7 — DST window guard (AC7)**
  - [x] Guard at `AvailabilityService.java:156`'s call site + WARN log
  - [x] Test with the `Europe/Berlin` `02:30–03:00` / 2026-03-29 fixture; assert the window contributes zero slots and that removing the guard produces an inverted slot
- [x] **Task 8 — Compose build key (AC8)**
  - [x] `build:` block in `docker-compose.local.yml`; one sentence in `local-deployment.md` Step 1
  - [x] Verify `docker compose -f docker-compose.yml -f docker-compose.local.yml build app` produces a new image id — verified via `docker compose config` that the merged local file now exposes `build.context`/`build.dockerfile`, and that base and UAT still expose none
- [x] **Task 9 — Ledger hygiene (AC9)**
  - [x] Apply the five edits, re-counting the `deferred-9` D2 i18n figures first — **the recount contradicted the priorities doc; see Completion Notes**
  - [x] Append the `skillars-uat-1` deferred section (5 items)
- [x] **Task 10 — Full verification**
  - [x] `mvn -o verify` green; report **unit and integration totals separately** (see Dev Notes) — 818 unit + 863 IT, 0F/0E. Baseline discrepancy investigated and explained in the Change Log, not hand-waved.
  - [x] ESLint clean on the frontend; Prettier state recorded honestly (pre-existing failures left alone)
  - [x] Record which AC4/AC5 behaviours were verified by code reading only — four named items in the Completion Notes

### Review Findings

**All 11 addressed (2026-08-10).** Ten were valid and are fixed; one was valid in principle but not
reachable, and is documented in code rather than "fixed" into a worse behaviour. Two turned out to be
more serious than reported — see the notes on findings 2 and 5.

- [x] [Review][Patch] `AdminBootstrapProperties` leaks the raw password via Lombok `@Data`'s generated `toString()`, contradicting the class's own "never logged, in any branch, at any level" contract [src/main/java/com/softropic/skillars/platform/security/contract/AdminBootstrapProperties.java:18-35]
  - **FIXED.** `@ToString.Exclude` on `password`, with the reasoning recorded on the field: a `@ConfigurationProperties` bean's `toString()` is not private — Spring Boot prints it in binding-failure messages and `/actuator/configprops` reflects over the same object, so this was a real egress path, not a theoretical one. Pinned by `toStringDoesNotLeakPassword`.
- [x] [Review][Patch] `AdminBootstrapRunner`'s `catch (DataIntegrityViolationException)` may not reliably cover Hibernate Bean-Validation `ConstraintViolationException` (a 4+ char email TLD or a 51+ char first/last name) — unverified whether Spring's JPA exception translation converts it before it reaches this catch; if not, a plausible-looking bootstrap config crashes the whole app at startup, contradicting "Never fails startup" [src/main/java/com/softropic/skillars/platform/security/service/AdminBootstrapRunner.java:117-153]
  - **FIXED — and confirmed worse than reported.** I probed it against a real database rather than reasoning about it: a 4-character TLD throws **`TransactionSystemException`**, not `DataIntegrityViolationException`, so the catch missed it entirely and the app failed to start. `Customer.email`'s regex caps the TLD at three characters (`[a-z]{2,3}`), so `.info`, `.cloud`, `.tech` and `.online` — ordinary admin addresses — all bricked the first boot. Two-part fix: a pre-flight check against the entity's own constraints that fails with a message naming the variable and the reason, plus a catch widened to `RuntimeException` so **no** persistence failure can take startup down. Mutation-checked: narrowing the catch back fails `commitTimeFailureOfAnyTypeDoesNotFailStartup`.
- [x] [Review][Patch] `AdminBootstrapRunner`'s class Javadoc claims only one pre-DB-work failure path (missing phone), but the `ROLE_ADMIN`-not-found `AppSetupException` is a second, undocumented refuse-to-start path that fires *inside* the transaction [src/main/java/com/softropic/skillars/platform/security/service/AdminBootstrapRunner.java:57-59,118-121]
  - **FIXED.** The Javadoc now separates the two categories honestly — "never fails startup on data" versus "fails startup on misconfiguration, deliberately" — and enumerates all three refuse-to-start paths, noting that the `ROLE_ADMIN`-missing one fires inside the transaction and is therefore re-thrown explicitly past the catch-all. Pinned by `missingAdminAuthorityStillFailsStartup`.
- [x] [Review][Patch] Hardcoded non-ISO region code `"XX"` passed to `new PhoneNumber(...)` for the bootstrap admin — not a recognized ISO-3166 code, untested, inconsistent with real codes used elsewhere in seed data [src/main/java/com/softropic/skillars/platform/security/service/AdminBootstrapRunner.java:130]
  - **FIXED as documentation, not as a value change.** Verified first: `"XX"` is what all three registration services already pass (`CoachRegistrationService:87` and its parent/player counterparts), so changing it here would create the inconsistency rather than remove one. It also sits in ISO-3166's user-assigned range (XA–XZ), so it cannot collide with a real country. Promoted to a named constant `PLACEHOLDER_ISO2_COUNTRY` with that rationale, and the value is now asserted in `createsLoginCapableAdmin` so it is no longer untested.
- [x] [Review][Patch] `getSupportedTimezones()`'s filter drops no-slash aliases and `Etc/*` but its Javadoc claims it removes "obsolete or ambiguous" zones — legacy slash-containing aliases (e.g. `US/Eastern`, `Canada/Atlantic`) still pass the filter untested [src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:94-98]
  - **FIXED — and the finding understated it.** I enumerated the JVM's set: **42** legacy aliases contain a slash and survived the filter, not just the `US/*` and `Canada/*` examples given — the whole of `Brazil/*`, `Mexico/*`, `Chile/*` and `SystemV/*` too. `SystemV/*` matters most: it carries pre-1987 US DST rules, making it as wrong as the `Etc/GMT±N` block the filter already excluded. Rewritten as an **allow-list** of the ten IANA continent prefixes rather than an exclusion list, so a family added by a future tzdb release is excluded by default instead of silently appearing. 486 options on JDK 17. The IT now asserts the allow-list as a property, not as a list of known-bad names.
- [x] [Review][Patch] `AvailabilityService`'s new DST guard only catches a fully-inverted window; a window whose *start* (not end) falls in a spring-forward gap still passes `windowEnd.isAfter(windowStart)` and is silently shortened by the gap length with no WARN log [src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:134-159]
  - **FIXED.** A second WARN fires whenever the materialized duration differs from the configured local duration. The window is deliberately **not** skipped — a shortened remainder is genuinely bookable, and 02:30 local really does not exist on that date, so the behaviour was correct. What was wrong was that it was silent: the coach saw fewer bookable minutes than they configured with no way to find out why.
- [x] [Review][Patch] Timezone-fetch failure is indistinguishable from "no matching search text": the store swallows the error into an empty list without setting `error`, so `showUnknownZoneHint`'s `options.value.length > 0` guard never fires and the coach is stuck at a disabled Next button with no diagnostic [src/frontend/src/stores/profileBuilder.store.js:45-50, src/frontend/src/components/profileBuilder/TimezoneSelect.vue:68-71]
  - **FIXED.** The store now tracks `timezonesFailed` separately from an empty list, and the picker renders a distinct message plus a retry button. The reviewer's diagnosis was exactly right: an empty dropdown reads as "your search matched nothing", so the coach was left at a disabled Next button with nothing to act on — the same dead end this feature exists to remove, reached a different way.
- [x] [Review][Patch] `ProfileBuilderStep4`'s `canonicalTimezone` ref is captured once from the store at setup and doesn't re-sync if the coach changes Step 1's timezone while Step 4 stays mounted, letting the two submitted values diverge [src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue:88]
  - **VERIFIED NOT REACHABLE — documented instead of changed.** The host page renders the five steps through a `v-if`/`v-else-if` chain of *distinct* components, so leaving Step 4 unmounts it and returning re-runs setup with the current store value; there is no window in which Step 1 can change the zone while Step 4 is mounted. Adding a watcher or computed would fix nothing and break something real — it would overwrite a per-window zone the coach had deliberately chosen. The reasoning is now a comment on the ref so this is not re-raised.
- [x] [Review][Patch] `ProfileBuilderStep1.vue`/`ProfileBuilderStep4.vue` mutate `store.selectedTimezone` directly instead of through a store action, inconsistent with the rest of the store's API [src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue:152, src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue:115]
  - **FIXED.** Added a `setSelectedTimezone` action; both steps call it.
- [x] [Review][Patch] Identical multi-line rationale comment duplicated across 4 call sites — a future edit to the rationale needs 4 synchronized edits [src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:339,444,462, src/main/java/com/softropic/skillars/platform/messaging/service/MessagingReportService.java:144]
  - **FIXED.** Full rationale kept once on `MessagingService.verifyIsParty`; the two sibling arms point at it in two lines, and `MessagingReportService`'s copy points at it as the canonical source while noting the duplication is intentional (injecting `MessagingService` would be a circular dependency).
- [x] [Review][Patch] `ConfigResourceIT` leaves the dangerous `ROLE_ADMIN_ID` literal-FK constant declared directly under a comment warning not to use it as a foreign key — invites reintroducing the exact bug this diff fixed elsewhere [src/test/java/com/softropic/skillars/platform/config/api/ConfigResourceIT.java:65-66]
  - **FIXED.** Both constants deleted. The two ids are inlined into the only statements that may legitimately use them — the authority seeds — so no field named like a usable foreign key exists to be reached for.

## Dev Notes

### Architecture constraints (from `_bmad-output/project-context.md`)

- **Java 17 / Spring Boot 3.5.11.** DTOs are `record` types. Resources are suffixed `Resource`, live in `api`, and **every** method carries `@PreAuthorize` using `SecurityConstants`.
- **Module boundary is enforced.** The bootstrap runner is business logic about users → `com.softropic.skillars.platform.security.service`. It must not land in `infrastructure` (no `platform` imports allowed there, no domain entities, no domain lifecycle). Same rule for the timezone endpoint: it belongs on the existing `ProfileBuilderResource`, not a new infrastructure utility.
- **Schema changes are Flyway-only.** No DDL from Java.
- **Frontend:** Quasar 2.16 / Vue 3.5 with `<script setup>`, Pinia for shared state, all API calls in `src/api/*.api.js`, `async/await` not `.then()`, all user-facing text through `vue-i18n`, Prettier mandatory.

### Testing — read this before writing a single test

`skillars-deferred-19` (done 2026-08-08) consolidated the Spring test contexts and left **build-failing guardrails** behind:

- **Every integration test extends `com.softropic.skillars.config.AbstractIntegrationTest`** and adds **no class-level annotations**. Adding `@SpringBootTest`, `@ActiveProfiles`, `@Import` or `@TestPropertySource` to a concrete `*IT` forks the Spring context and `IntegrationTestConventionTest` fails the build in the `test` phase, before any container starts.
- **`EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5`** is pinned in `IntegrationTestConventionTest`. AC2's bootstrap is property-driven, so the obvious test — an IT with `@TestPropertySource(app.bootstrap.admin.*)` — **will fail that assertion**. Prefer the design that avoids it: make `AdminBootstrapRunner` take its properties by constructor injection so the enable/disable/idempotency cases are plain **unit** tests with a constructed properties object, and let the IT cover only the post-bootstrap login (seeding the admin the way `AdminQueueIT.insertUser:317-322` already does). If you conclude a `@TestPropertySource` is genuinely required, bump the pinned count **and** add the `// context-fork:` comment the guardrail's javadoc demands. Do not bump it silently.
- **Test data:** Instancio for generation, AssertJ for assertions, Awaitility for async, Testcontainers with a real database — never a mocked one.
- **Existing fixture to copy for admin seeding:** `AdminQueueIT.java:76-94` inserts the authority rows and an `ADMIN`-role user via `jdbcTemplate`, then `grantAuthority`. Note it currently inserts `ROLE_ADMIN` with id `9002` under `ON CONFLICT (name) DO NOTHING` — **after AC1 that conflict clause starts firing**, since the migration already seeded the name at id 103. That is correct and harmless (the `grantAuthority` helper resolves by name, not id), but confirm the admin ITs still pass rather than assuming it.
- **Test-count reporting is a repeat trap.** Three consecutive stories (`deferred-15`, `-16`, `-18`) had to correct inflated or truncated totals, from summing `target/surefire-reports/` files instead of the classes actually run, or from quoting the failsafe total alone. Report unit and integration separately. The last recorded green baseline is **828 unit + 905 IT** (CI run 31266833983, commit `bf513a1`).
- **There is no frontend test suite.** No `*.spec.js` outside `node_modules`, no `src/frontend/test`. Do not introduce a framework in this story. Every `.vue` change here (AC4, AC5) is therefore unverifiable by CI — say so explicitly in the completion notes rather than letting a green build imply coverage. This is the same standing gap `deferred-17` D6 and `skillars-5-4` W9 record.

### Files being modified — current state and what must be preserved

| File | Current state | This story changes | Must not break |
|---|---|---|---|
| `AvailabilityService.java:134-156` | Per-window instants materialized in the window's own zone (deferred-18 AC2); 48-hour padded fetch bounds at `:92-93` | Adds an inversion guard before `computeAvailableSlots` | The 48h pad and its rationale comment (`:79-91`); the transient pseudo-block merge at `:146-155`; `blockResponses`' exact week-scoping at `:166-170` |
| `MessagingService.java:339,436,446` | Four throwing `default` arms added by `deferred-16` AC4 | Exception type only | Every non-`default` arm; the `verifyIsParty` identity semantics deferred-16 fixed (`PLAYER` compares **profile** id, `PARENT` compares **user** id) |
| `ProfileBuilderStep1.vue` | Sends browser zone verbatim; has a live `sanitizePreview` debounce + `AbortController` on `bio` (`:101-133`) with `onUnmounted` cleanup | Timezone field only | The bio contact-detection watcher and its cleanup — do not refactor around it |
| `ProfileBuilderStep4.vue` | Sends browser zone per window; `submit()` requires every window to have day/start/end | Timezone field + default from store | The per-window `@NotNull @Valid` payload shape — `ProfileBuilderStep4Request` rejects `{"windows":[null]}` by design (2026-08-07 fix) |
| `AvailabilityManagerPage.vue:332-334` | Reads `store.windows[0].canonicalTimezone` | Reads `store.coachTimezone` | `coachTimezone`'s role as the local ref feeding `:24` and `:290` |
| `docker-compose.local.yml` | `app` service overrides env + ports + `minio` dependency; no `build:` | Adds `build:` | The `APP_STORAGE_ENDPOINT_URL=http://minio:9500` override and its `/etc/hosts` comment — presigned URLs depend on it |
| `application.yaml` | `app:` block at `:153` | Adds `app.bootstrap.admin.*` | Existing `app.config`, `app.storage`, `app.toggles` blocks |

### Security posture for AC2 — non-negotiable

This story creates a mechanism that mints a privileged account from environment variables. Get these right:

- Bootstrap is **off** unless both email and password are explicitly set. No default password, ever, in any file.
- The raw password never appears in a log, an exception message, an HTTP response, or a `toString()`.
- The runner grants `ROLE_ADMIN` **only**. It never grants `ROLE_LTD_ADMIN`, never grants both, and never elevates an existing user — if the email already exists it skips, it does not patch authorities onto that row. An "upgrade this existing user to admin" path is a different, riskier feature and is out of scope.
- Follow the "raw keys shown once and never stored" rule from `project-context.md`: AC3's documented procedure must tell the operator to remove the password variable after the first successful boot.

### Latest technical notes

- **`ZoneId.getAvailableZoneIds()`** returns 600 ids on JDK 17.0.1 — **not** a clean region-zone list. Measured breakdown: 527 `Continent/City` zones, 38 legacy no-slash aliases, 35 `Etc/*` (29 of them the DST-blind `Etc/GMT±N` block). AC4's filter exists because of that; see its second bullet for the exact rule and the reason. The picker offers 528 (527 + `Etc/UTC`) while `IanaTimezoneValidator` stays permissive — the asymmetry is deliberate, so pre-existing stored values keep working.
- **`Intl.supportedValuesOf('timeZone')`** is the browser-side equivalent and is deliberately **not** used: it reports the *browser's* tzdata, which is the exact source of the lockout. The server list is the only correct source.
- **Quasar `q-select`** needs `use-input` + `@filter` for a list this size; Quasar 2.16's filter callback signature is `(val, update, abort)`. Follow the existing `q-select` usages in `ProfileBuilderStep4.vue:14-25` for prop and styling conventions (`outlined dense`, `emit-value map-options`).
- **Flyway** runs on every boot; `ON CONFLICT (name) DO NOTHING` keeps V92 safe against a UAT database where someone already hand-inserted the authority rows while working around P0-1.

### Git intelligence

Recent commits are docs/dependency work (`bf9c828` docs, `a170e69` tenant-module removal, several dependabot bumps). The tenant removal is why `routes.js:322-331` and `pages/admin/Tenant*.vue` are dead — relevant to AC3's "no admin UI" caveat, but **do not delete them in this story**; that is unrelated cleanup and would widen an already broad diff.

The last four substantive stories (`deferred-15` → `-18`) all landed in the booking/messaging/availability code this story touches, and all four had review findings about **tests that could not fail against unfixed code**. AC6 and AC7 both prescribe a mutation check for that reason. Perform it and record the result.

### Project Structure Notes

- Migration: `src/main/resources/db/migration/V92__seed_admin_authorities.sql`
- Backend new: `platform/security/service/AdminBootstrapRunner.java`, `platform/security/contract/AdminBootstrapProperties.java` (or `config/` — match whichever package `SecurityProperties` occupies: `platform/security/contract/`)
- Backend edited: `ProfileBuilderResource.java`, `CoachProfileService.java`, `AvailabilityService.java`, `MessagingService.java`, `MessagingReportService.java`, `application.yaml`
- Frontend new: `components/profileBuilder/TimezoneSelect.vue`
- Frontend edited: `components/profileBuilder/ProfileBuilderStep1.vue`, `ProfileBuilderStep4.vue`, `pages/coach/AvailabilityManagerPage.vue`, `stores/profileBuilder.store.js`, `api/marketplace.api.js`, `i18n/{en,en-US,de,fr-FR}/index.js`
- Ops/docs edited: `docker-compose.local.yml`, `docker-compose.uat.yml`, `.env.example`, `docs/deployment/uat-deployment.md`, `docs/deployment/local-deployment.md`, `_bmad-output/implementation-artifacts/deferred-work.md`

No new module is created; every change lands in an existing bounded context.

### References

- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`] — P0-1, P0-3, P1 #4, P1 #5, P1 #6, P2 #1, Ledger hygiene
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — `deferred-16` D1; `deferred-17` D4, D7; `deferred-18` D1, D5; `deferred-9` D2, `deferred-10` D1, `deferred-11` D3, `deferred-16` D8, `deploy-2-1` D1
- [Source: `_bmad-output/project-context.md`] — module boundaries, DTO/record rules, testing rules, security rules
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-18-availability-slot-timezone-integrity.md`] — the `@IanaTimezone` scope decision this story must not reverse
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-19-test-context-container-consolidation.md`] — `AbstractIntegrationTest` and the `IntegrationTestConventionTest` guardrail

## Dev Agent Record

### Agent Model Used

Claude Opus 5 (`claude-opus-5`) — `bmad-dev-story`, 2026-08-10.

### Debug Log References

Mutation checks (each ran the named test against deliberately broken source, then restored):

| AC | Mutation applied | Result |
|---|---|---|
| AC2 | `findOneByEmail(login)` → `findOneByEmail(properties.getEmail())` | **2 failures** — `mixedCaseEmailIsIdempotentAcrossBoots` and `existingAccountIsSkipped`. Both catch it. |
| AC6 | `MessagingService.verifyIsParty`'s `default` arm back to `IllegalArgumentException` | **1 failure** — `unrecognisedRole_yields403NotFatal`, reporting `but was: java.lang.IllegalArgumentException: Unknown messaging role: SUPERVISOR` at `MessagingService.java:345` |
| AC7 | `if (!windowEnd.isAfter(windowStart))` → `if (false)` | **1 failure** — `getAvailabilityCalendar_windowStraddlingDstGap_contributesNoSlot`, reporting `Expecting empty but was: [AvailableSlotResponse[startDatetime=2026-03-29T01:30:00Z, endDatetime=2026-03-29T01:00:00Z]]` — the inverted slot the AC describes, reproduced exactly |

### Completion Notes List

**Two defects were found during implementation that the story spec did not anticipate. Both are fixed.**

1. **AC2's runner needed a transaction, and the IT is what caught it.** `User.authorities` is
   `@ManyToMany(cascade = {REFRESH, DETACH, PERSIST})`, so an `Authority` read outside a transaction
   is detached by the time `save()` cascades `PERSIST` onto it — Hibernate rejects it with
   *"detached entity passed to persist"*. `CoachRegistrationService`, the shape the AC told me to
   copy, never hits this only because it is class-level `@Transactional`, which the AC did not
   mention. Fixed with a `TransactionTemplate` around the lookup-and-save, deliberately **not**
   `@Transactional` on `run()`: the `DataIntegrityViolationException` catch has to sit outside the
   transaction, or catching a constraint violation inside a still-open transaction leaves it
   rollback-only and the commit throws `UnexpectedRollbackException` instead — the same
   swallow-the-wrong-exception trap `deferred-12` and `deferred-14` both had to fix. The unit test
   uses a real (no-op) `PlatformTransactionManager` rather than a mocked template, so the callback
   cannot be silently skipped.

2. **AC6's scope was wrong: only two of the four `default` arms are reachable.** `resolveLastReadAt`
   and `updateLastRead` both sit behind a `verifyIsParty` call that throws first, so no caller can
   drive them with an unrecognised role. I changed all four (consistency is still correct) but the
   test asserts only the two reachable ones — `MessagingService.verifyIsParty` and
   `MessagingReportService.verifyIsParty` — rather than faking a reachability that does not exist.
   Recorded as deferred D2.

   **This also surfaced a genuine new finding (deferred D1):** `MessagingService.getConversations`
   selects its branch with an `if/else if/else` chain, not a `switch`, so an unrecognised role
   silently falls into the **PLAYER** path and returns an empty list. That is the same
   silent-fallback class `deferred-16` set out to remove from `verifyIsParty`, surviving in a shape
   its `default`-arm sweep could not see. Not fixed: the `else` branch is the live path for every
   real PLAYER caller, so changing it needs its own regression pass.

3. **AC1's migration broke 8 existing integration test classes, and the story's own warning about this was aimed at the wrong file.** The Dev Notes flagged `AdminQueueIT`'s `ON CONFLICT (name) DO NOTHING` insert as the thing to re-check, and that one was indeed fine. What nobody checked was `src/test/resources/sql/authorityData.sql`, which inserts `ROLE_ADMIN` with **no conflict clause at all** — so once V92 seeded that name, every `@Sql`-seeded test in the suite died on a duplicate key. Casualties: `SecurityIT`, `SecurityFilterChainIT`, `ConfigResourceIT`, `StorageResourceIT`, `AccountManagementFacadeIT`, `PasswordResetIT`, `UserProfileServiceIT`, `UserServiceIT`.

   Adding `ON CONFLICT (name) DO NOTHING` alone was **not** enough, and this is the part worth remembering: those fixtures then insert `user_authority` rows using the **literal** authority id (`6747751741842104908`). With `DO NOTHING`, the migration's row wins and keeps id `103`, so the literal dangles and the insert dies on the foreign key instead — one duplicate-key error traded for another. The durable fix is to stop treating fixture-assigned authority ids as stable: every `user_authority` insert now resolves the authority with `(SELECT id FROM main.authority WHERE name = '…')`, the same pattern `AdminQueueIT.grantAuthority` and `CoachProfileBuilderIT` already used. Applied across `authorityData.sql`, `initTestData.sql`, `userData.sql`, `ConfigResourceIT` and `StorageResourceIT`. Those fixtures are now immune to any future seeding migration, which is the actual lesson — `V92` is simply the first migration to collide with them.

**AC9's recount contradicted the priorities document, and I wrote what I measured.** `uat-readiness-priorities.md` proposed re-scoping `deferred-9` D2 around "`fr-FR` has 154 booking keys against `en-US`'s 178" (measured 2026-08-09). By direct recount on 2026-08-10, **all four bundles carry an identical 160-leaf-key `booking` block** — the translation-parity gap is closed, not merely narrower. The line counts in that document (`en-US` 1167, `de` 1191, `fr-FR` 1190) were exactly right, so this is a difference in counting method, not a stale measurement. What remains open for D2 is now purely structural: `de` is not selectable in `MainLayout.vue`, `de` is not renamed `de-DE`, and `en` survives as a redundant fourth bundle.

**AC8 was already half-closed and was implemented as re-scoped.** `local-deployment.md:26-32` already documented the `docker build -t skillars:local .` workaround, so only the missing `build:` key was added. Verified through `docker compose config` that the merged local file now exposes `build.context`/`build.dockerfile`, and that the base and UAT files still expose none — production and UAT must keep pulling the published image.

**Verification status, stated plainly:**

- Full `mvn -o verify`: see the Change Log entry below for unit and integration totals, reported separately per the Dev Notes warning.
- **ESLint is clean** across `src/frontend/src`.
- **Prettier: not clean, and deliberately not fixed.** `ProfileBuilderStep1.vue`, `ProfileBuilderStep4.vue`, `AvailabilityManagerPage.vue`, `marketplace.api.js` and all four i18n bundles already failed `prettier --check` at `bf9c828` — verified by checking out the `HEAD` copies and running Prettier against those. Reformatting them would have buried this story's changes in an unrelated whitespace diff. The one new file, `TimezoneSelect.vue`, is Prettier-clean. Recorded as deferred D5; `project-context.md` calls Prettier mandatory, so the repo is currently in violation of its own rule and wants a dedicated sweep.
- **Every `.vue` change in AC4 and AC5 is verified by code reading and a successful production build only.** There is no frontend test suite in this repo and this story did not add one. A green build does **not** mean the picker, the unknown-zone banner, the Step 1 → Step 4 default, or the availability-page timezone label were exercised. Specifically unverified by any automated check:
  - The timezone `q-select` filters and renders over ~528 options.
  - The unknown-zone banner appears when the browser reports a zone the server does not know.
  - Step 4 actually defaults to the zone Step 1 chose within one session.
  - `AvailabilityManagerPage` renders the coach-profile timezone rather than `windows[0]`'s.

  These need a human spot-check or an agent with browser tooling before the story is trusted end-to-end. Same standing gap as `deferred-17` D6 and `skillars-5-4` W9.
- `AdminAuthoritySeedIT` also confirms the pre-existing admin ITs are unaffected by V92: `AdminQueueIT` inserts `ROLE_ADMIN` at id `9002` under `ON CONFLICT (name) DO NOTHING`, so that clause now fires — harmless, because `grantAuthority` resolves by name.

### File List

**New**
- `src/main/resources/db/migration/V92__seed_admin_authorities.sql`
- `src/main/java/com/softropic/skillars/platform/security/contract/AdminBootstrapProperties.java`
- `src/main/java/com/softropic/skillars/platform/security/service/AdminBootstrapRunner.java`
- `src/frontend/src/components/profileBuilder/TimezoneSelect.vue`
- `src/test/java/com/softropic/skillars/platform/security/repo/AdminAuthoritySeedIT.java`
- `src/test/java/com/softropic/skillars/platform/security/service/AdminBootstrapRunnerTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/AdminBootstrapIT.java`

**Modified — backend**
- `src/main/java/com/softropic/skillars/platform/security/config/SecurityConfiguration.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java`
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingReportService.java`
- `src/main/resources/application.yaml`

**Modified — tests**
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java`
- `src/test/java/com/softropic/skillars/platform/messaging/api/MessagingAccessControlIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/AvailabilityServiceTest.java`

**Modified — test fixtures made immune to seeding migrations (V92 regression, see note 3)**
- `src/test/resources/sql/authorityData.sql`
- `src/test/resources/sql/initTestData.sql`
- `src/test/resources/sql/userData.sql`
- `src/test/java/com/softropic/skillars/platform/config/api/ConfigResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/filestorage/api/StorageResourceIT.java`

**Modified — frontend**
- `src/frontend/src/api/marketplace.api.js`
- `src/frontend/src/stores/profileBuilder.store.js`
- `src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue`
- `src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue`
- `src/frontend/src/pages/coach/AvailabilityManagerPage.vue`
- `src/frontend/src/i18n/en/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Modified — ops & docs**
- `docker-compose.local.yml`
- `docker-compose.uat.yml`
- `.env.example`
- `docs/deployment/uat-deployment.md`
- `docs/deployment/local-deployment.md`
- `docs/deployment/secrets-reference.md`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` (claim markers, written at story-creation time)
- `_bmad-output/implementation-artifacts/skillars-uat-1-admin-bootstrap-and-onboarding-unblock.md` (this file)

### Change Log

**2026-08-10 — Story implemented; all 9 ACs closed. `mvn -o verify` BUILD SUCCESS (09:05).**

Test totals, reported separately as the Dev Notes require:

| Suite | Result |
|---|---|
| Unit (surefire) | **818 tests, 0 failures, 0 errors, 1 skipped** |
| Integration (failsafe) | **863 tests, 0 failures, 0 errors, 4 skipped** |

**The 828 + 905 baseline quoted in this story's Dev Notes is stale for this tree, and the difference is fully accounted for.** That figure comes from CI run 31266833983 at commit `bf513a1`, which is *two commits behind* the tree this story was built on. `a170e69` ("Remove the tenant module") landed in between, deleting 12 test files — 9 tenant IT classes, 2 e2e builders and `TenantLifecycleEmailListenerTest` — and its own commit message records the integration suite dropping to 852 and 12 tenant unit tests being removed. So the comparison the Dev Notes set up was never valid against `bf9c828`.

My own contribution is exact and independently verified by diffing test-method counts against `HEAD` rather than by subtracting totals:

| Class | At `bf9c828` | Now | Delta |
|---|---|---|---|
| `AdminBootstrapRunnerTest` (new) | — | 6 | +6 unit |
| `AvailabilityServiceTest` | 13 | 14 | +1 unit |
| `AdminAuthoritySeedIT` (new) | — | 2 | +2 IT |
| `AdminBootstrapIT` (new) | — | 2 | +2 IT |
| `CoachProfileBuilderIT` | 24 | 26 | +2 IT |
| `MessagingAccessControlIT` | 4 | 5 | +1 IT |
| | | | **+7 unit, +7 IT** |

**No test class silently stopped running:** 138 `*IT.java` sources minus 4 abstract bases = 134 concrete classes, and failsafe produced exactly 134 reports. `IntegrationTestConventionTest` also passed, so no concrete `*IT` forked its own Spring context and the pinned `@TestPropertySource` count of 5 is unchanged — AC2's property-driven bootstrap is unit-tested by constructor injection precisely to avoid needing that.

**One regression was introduced and fixed within this story** (see Completion Note 3): `V92`'s `ROLE_ADMIN` seed collided with `authorityData.sql`'s unguarded insert and failed 8 IT classes on the first full-suite run. Fixed by making the authority fixtures conflict-tolerant *and* resolving `user_authority` foreign keys by name; re-verified those 8 classes green (51/51) before the clean full run above.

ESLint clean. Prettier not clean on pre-existing files, deliberately untouched — recorded as deferred D5.

---

**2026-08-10 (later) — all 11 review findings addressed. `mvn -o verify` BUILD SUCCESS (20:23).**

| Suite | Before review fixes | After | Delta |
|---|---|---|---|
| Unit (surefire) | 818 | **823**, 0 failures, 0 errors, 1 skipped | +5 |
| Integration (failsafe) | 863 | **863**, 0 failures, 0 errors, 4 skipped | unchanged |

The +5 are the new guards on `AdminBootstrapRunner`: `commitTimeFailureOfAnyTypeDoesNotFailStartup`, `invalidEmailFailsFastBeforeAnyDatabaseWork`, `oversizedNameFailsFast`, `missingAdminAuthorityStillFailsStartup` and `toStringDoesNotLeakPassword`. The integration count is unchanged because the AC4 review fix strengthened assertions inside existing tests rather than adding methods.

**Two findings were materially worse than reported, and both required measurement rather than reasoning:**

- **Finding 2** was probed against a real database instead of argued from the type hierarchy. A four-character TLD throws `TransactionSystemException`, **not** `DataIntegrityViolationException` — the catch missed it entirely and the application failed to start. `Customer.email`'s regex caps TLDs at three characters, so `.info`, `.cloud`, `.tech` and `.online` each bricked the first boot. Fixed by a pre-flight check against the entity's own constraints plus a catch widened to `RuntimeException`; mutation-checked by narrowing it back.
- **Finding 5** was quantified by enumerating the JVM's zone set: **42** legacy aliases survived the slash filter, not only the two examples cited — the whole of `Brazil/*`, `Mexico/*`, `Chile/*` and `SystemV/*` as well. Rewritten as an allow-list of the ten IANA continent prefixes so future tzdb additions are excluded by default.

**One finding was rejected on evidence, not preference.** Finding 8 (Step 4's ref not re-syncing) is unreachable: the host page renders the steps via a `v-if`/`v-else-if` chain of distinct components, so Step 4 unmounts on navigation and re-reads the store on return. Adding a watcher would have introduced a real bug — overwriting a per-window zone the coach chose deliberately. Documented on the ref instead of changed.

Two new i18n keys (`auth.coach.timezoneLoadFailed`, `common.retry`) added to all four bundles. ESLint clean; `TimezoneSelect.vue` remains Prettier-clean; the pre-existing Prettier violations are still untouched and still tracked as deferred D5.
