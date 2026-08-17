# Story Deferred-28: Booking Error Messaging, Subscription REST Coverage & Coach-Media Timestamp Test

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want five small, independently-verified deferred items closed — a `CoachMediaItem` entity whose
`@PrePersist` timestamp callback has zero test coverage, one full-stack error-messaging gap on the
parent-facing booking flows (six booking error codes that resolve to no message in any locale bundle,
so a single/batch booking submit or a reschedule request shows the same generic "something went wrong"
toast for every distinct rejection reason), a REST-layer test-coverage gap on `SubscriptionResource`
(9 of its 10 endpoints have no HTTP-level integration test), an undocumented refund-eligibility branch
that silently drops to `"NONE"` for a negative elapsed-time value, and a credit-routing/cash-out boundary
condition (balance exactly equal to the amount owed) that is correct today but pinned by no test — so
that each of five unrelated, previously-deferred defects, spanning the marketplace, booking and payment
modules, gets fixed without bundling any of them into a larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit as
`skillars-deferred-11/20/21/22/23/24/25/26/27`. All items below were independently re-verified against
**current** code during this story's creation (2026-08-17), not trusted from the ledger's text.

**This story was reworked after a senior-dev review of its draft** (`story-review.md`, 2026-08-17) found
two of the draft's five items already closed by a different, concurrently-shipped
`skillars-deferred-27-repository-ordering-updatable-guard-and-test-coverage-fixes` story (merged
`efafd54`, PR #57) — a same-ledger item collision between two story-creation passes run in parallel. That
merged story's AC3 closed the `SessionBuilderResourceIT` 30-drill boundary test and its AC4 closed the
`SluDashboardServiceTest` `anyShort()`→`eq()` tightening — both verbatim what this story's draft AC1/AC2
asked for (`deferred-work.md:1448-1449` already carry `[CLOSED by skillars-deferred-27 AC3/AC4]`). It
also closed `deferred-26`'s `Booking.parentId`/`playerId`/`coachId` `updatable = false` regression-test
gap as its own AC2 (`deferred-work.md:1440`). None of those three items are in this story. The review also
found the draft's booking-error-messaging AC branched on the wrong API response field (`helpCode`, a
random support ID, instead of `errorMsg.errorKey`) and that its subscription-test AC had a false premise,
a contradictory reference pattern, and a broken fixture plan — all corrected below. This story was
renumbered from `skillars-deferred-27` to `skillars-deferred-28` to stop colliding with the merged story
of that number.

**This story was reworked a second time after a further senior-dev review** (`story-review.md`,
2026-08-17, overwriting the earlier review referenced above — see the References note on this file's
rotating-scratch-file convention). That review found no ID collisions and no pre-closed items this time,
but caught two real defects before any code was written: AC2's batch-flow wiring listed two error codes
(`coachUnavailable`, `slotUnavailable`) that `BookingBatchService.createBatch` structurally cannot throw
(it has no shared coach-suspension check and no cross-booking-vs-DB overlap check — only its own inline
non-ACTIVE check, which yields `MISSING_RIGHTS`, not `COACH_UNAVAILABLE`), and AC2's prescribed English
copy for `batchSizeExceeded` stated the wrong limit (10, when the configured value is 5). It also found
AC3's mock list would fail to boot the Spring test slice (two required beans missing) and that AC4's
prescribed comment asserted a false reason for the branch it documents. All corrected below; see each
AC's own inline notes for what changed and why.

Ledger candidates inspected while assembling this story and **rejected** (do not implement):

- **`skillars-deferred-26` D1 — `DrillMetadata.repDensity` as a nullable `Integer`.** Re-verified during
  this story's creation that **no live code path can ever produce an unset `repDensity` today**: the only
  two ways a `Drill` row gets metadata are Flyway's `V39` seed (20 platform drills, all with valid
  `repDensity`) and `DrillLibraryService.cloneDrill` (`DrillLibraryService.java:104-126`), which does
  `clone.setMetadata(source.getMetadata())` — copying a `PLATFORM` drill's already-valid metadata verbatim.
  There is no `CreateDrillRequest`-style endpoint anywhere that lets a coach author new `DrillMetadata`
  JSON from scratch. Converting `int` → `Integer` would be speculative hardening for a custom-drill-authoring
  feature that does not exist yet. Left open in `deferred-work.md` for whoever builds real custom-drill
  authoring.
- **`skillars-deferred-25` code review — `CoachMediaItem`'s `@PrePersist` has no reachable IT-level
  test.** Correct that no *integration* path constructs this entity yet (still zero hits for
  `new CoachMediaItem()` outside the entity's own file — confirmed 2026-08-17). But the callback method
  itself (`onCreate()`) is package-private and trivially unit-testable in isolation without any feature
  needing to exist first — see AC1 below, which closes this without waiting on the coach-media gallery
  upload feature.
- **`skillars-uat-4` D3 — `de-DE` bundle has 55 `// TODO: native review` markers.** A translation-content
  review task, not an engineering defect; Mbah already made the ship-as-is call in that story's Dev Notes.
  Not a mechanical fix a dev agent can safely perform.
- **`skillars-uat-2` D1 — `RescheduleService` has no availability-window check at all.** The ledger item
  itself says "decide the semantics first" (does a reschedule need to fit *current* availability, or only
  availability as it stood at booking time?) — a product decision, not a bundled mechanical fix.
- **`skillars-11-2`/`skillars-deferred-11` D2 — pack-selection FIFO vs. soonest-expiring display
  mismatch.** Explicitly flagged in the ledger as "needs product input."
- **The broad body of `deploy-*` items and every item flagged as needing a migration, a product decision,
  or a platform-wide resilience pass** (e.g. `skillars-10-2` D1's `AFTER_COMMIT` listener reliability) —
  none of those are small, independently-safe, mechanical fixes.

## Deferred Items Closed

| Source | Item | Current location (re-verified 2026-08-17) | AC |
|---|---|---|---|
| Code review of `skillars-deferred-25-...` (2026-08-15) | No test proves `CoachMediaItem`'s `@PrePersist onCreate()` sets `uploadedAt` | `CoachMediaItem.java:34-37` | 1 |
| `skillars-uat-2-session-duration-and-booking-slot-integrity` story creation (2026-08-10) D2 | Booking error codes (`booking.coachUnavailable`, `booking.slotUnavailable`, `booking.invalidSessionDuration`, `booking.batchSizeExceeded`, `booking.duplicateSlotStartTime`, `booking.overlappingSlots`) resolve to no message in any bundle on the three parent-facing booking flows | `BookingError.java`, `BookingBatchService.java`, `RescheduleService.java`, `messages*.properties`, `en-US/de-DE/fr-FR/index.js`, `BookingRequestPage.vue`, `ParentBookingsPage.vue` | 2 |
| Code review of `skillars-uat-6-coach-subscription-and-volume-backup` (2026-08-13) D3 | 9 of `SubscriptionResource`'s 10 endpoints have no HTTP-level integration test (only service-layer `SubscriptionLifecycleIT`, plus one endpoint now covered by `PlayerSubscriptionOwnershipIT`) | `SubscriptionResource.java` | 3 |
| Code review of `skillars-3-4-booking-state-machine-sse` (2026-06-15) | Negative `hoursUntilSession` for a past-session `CANCEL_PARENT` silently maps to `"NONE"` refund eligibility — probably correct but undocumented | `BookingService.java:762-763` | 4 |
| Adversarial code review of `skillars-7-2-session-payment-lifecycle-credit-wallet` Group 6 (2026-06-24) D22 | Credit routing boundary (`balance == sessionPrice`, Case A/B boundary, `stripeAmount=0`) and cash-out boundary (`amount == balance`) are correct today but pinned by no test | `CreditRoutingTest.java`, `CashOutServiceTest.java` | 5 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement): all
items listed under "Why this story exists" above, plus the two items closed by the concurrently-merged
`skillars-deferred-27` story named there.

## Acceptance Criteria

1. **New `CoachMediaItemTest` unit-tests the `@PrePersist onCreate()` callback.**
   `CoachMediaItem.onCreate()` (`CoachMediaItem.java:34-37`) sets `uploadedAt = OffsetDateTime.now()` only
   when `uploadedAt == null`, and has zero test coverage — confirmed no test file references
   `CoachMediaItem` anywhere (`find . -iname "CoachMediaItemTest.java"` returns nothing) and no production
   code constructs one yet (`grep -rn "new CoachMediaItem()" src/main/java` returns zero hits outside the
   entity itself). The callback is package-private, so a plain JUnit test class in the **same package**
   (`com.softropic.skillars.platform.marketplace.repo`) can call it directly with no reflection, no
   persistence context, and no Spring context — a pure POJO unit test, e.g.:
   ```java
   package com.softropic.skillars.platform.marketplace.repo;

   import org.junit.jupiter.api.Test;
   import java.time.OffsetDateTime;
   import static org.assertj.core.api.Assertions.assertThat;

   class CoachMediaItemTest {
       @Test
       void onCreate_setsUploadedAt_whenNull() {
           CoachMediaItem item = new CoachMediaItem();
           item.onCreate();
           assertThat(item.getUploadedAt()).isNotNull();
       }

       @Test
       void onCreate_preservesUploadedAt_whenAlreadySet() {
           OffsetDateTime fixed = OffsetDateTime.parse("2026-01-01T00:00:00Z");
           CoachMediaItem item = new CoachMediaItem();
           item.setUploadedAt(fixed);
           item.onCreate();
           assertThat(item.getUploadedAt()).isEqualTo(fixed);
       }
   }
   ```
   Do not add a `@DataJpaTest`/persistence-level test and do not build any fixture for a real upload
   flow — that flow does not exist yet; this AC closes the callback-logic gap only, not the "nothing
   constructs this entity yet" gap (out of scope, unrelated to a `@PrePersist` unit test). Place the new file
   at `src/test/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItemTest.java`. Run
   `mvn -o test -Dtest=CoachMediaItemTest` green (2/2).

2. **Six booking error codes get real, translated messages, and the parent-facing single/batch-booking and
   reschedule flows show a specific message for the errors each flow can actually produce, instead of one
   generic toast.** Confirmed 2026-08-17: none of the six codes below exist in any of the four backend
   bundles (`messages.properties`, `messages_en.properties`, `messages_de.properties`,
   `messages_fr.properties` — a broad `grep -rn "booking\." src/main/resources/i18n/*.properties` returns
   ~10 hits, but every one is an unrelated `email.booking.*` key; none of the six codes below is present)
   or in any of the three frontend locale bundles. The six codes, their throw sites, and which of this
   AC's three in-scope frontend actions can genuinely receive each — **get this precise, not
   approximate: `createBatch` and `requestReschedule` each throw a strict subset, not "the same codes as
   single-create"**:
   - `booking.coachUnavailable` — `BookingError.COACH_UNAVAILABLE` (`BookingError.java:13`) — **single
     booking create only** (`BookingService.createBookingRequest:179,239`). `BookingBatchService.createBatch`
     has its own inline coach-status check (`:115-117`) that collapses *every* non-`ACTIVE` status,
     `SUSPENDED` included, into a plain `OperationNotAllowedException(..., SecurityError.MISSING_RIGHTS)`
     — there is no shared coach-suspension check between the two paths, and `createBatch` never throws
     `COACH_UNAVAILABLE`. Do not wire this code into `submitBatchRequest()`'s catch.
   - `booking.slotUnavailable` — `BookingError.SLOT_UNAVAILABLE` (`BookingError.java:14`) —
     **single booking create only** (`BookingService.createBookingRequest:254`). `ApiAdvice`'s
     `excl_bkg_coach_slot_overlap` constraint mapping (`ApiAdvice.java:142`, 409) also maps to this code,
     but its partial predicate (`V87__booking_overlap_exclusion_constraint.sql:20-22`) explicitly excludes
     `REQUESTED`-status rows, and both parent-side create paths insert `REQUESTED` — so that constraint
     cannot fire from either flow in this AC's scope; it is reachable only from the coach-side accept
     path, which is out of scope (see below). `createBatch` has no cross-booking overlap check against the
     database at all — its only overlap logic (`:173-181`) compares the batch's own slots against each
     other and raises `booking.overlappingSlots`, a different code. Do not wire this code into
     `submitBatchRequest()`'s catch either.
   - `booking.invalidSessionDuration` — `BookingError.INVALID_SESSION_DURATION`
     (`BookingError.java:15`) — single booking create (`:215`), batch create
     (`BookingBatchService.createBatch:139`), **and** reschedule request
     (`RescheduleService.requestReschedule:87-93` — this is the **only** `BookingError` this method can
     throw; it has no coach-suspension check and no slot-overlap check, so do not wire
     `coachUnavailable`/`slotUnavailable` into the reschedule catch either — see Task 2)
   - `booking.batchSizeExceeded` — `BookingBatchService.java:98` — batch create only
   - `booking.duplicateSlotStartTime` — `BookingBatchService.java:159` — batch create only
   - `booking.overlappingSlots` — `BookingBatchService.java:179` — batch create only

   **So `submitBatchRequest()`'s catch branches on exactly four codes** (`batchSizeExceeded`,
   `invalidSessionDuration`, `duplicateSlotStartTime`, `overlappingSlots`), not all six — see Task 2. Of
   those four, `duplicateSlotStartTime` and `overlappingSlots` are the only two a normal user interaction
   can realistically trigger today: the basket UI keys slots by `startDatetime`
   (`isSlotInBasket`/`removeSlotFromBasket` in `BookingRequestPage.vue`) so a true duplicate start time
   can't be added through the UI, and `toggleSlotInBasket` (`:297-303`) already caps the basket at
   `maxBatchSize` client-side, so `batchSizeExceeded` fires only under config drift or a failed
   `getBatchConfig()` call. Keep all four branches anyway (they are cheap, defensive, and correct for the
   API contract regardless of what the current UI happens to allow) — just don't describe all four as
   equally likely user-visible wins.

   **This AC is deliberately scoped to the three parent-initiating flows only**
   (`BookingRequestPage.vue`'s `submit()` and `submitBatchRequest()`, `ParentBookingsPage.vue`'s
   `submitReschedule()`). Note `requestReschedule` has five distinct rejection paths in total and this AC
   improves exactly one of them (`invalidSessionDuration`) — the other four (not-owner, wrong status,
   start-not-in-future, end-not-after-start, and a pending-reschedule-already-exists conflict, the last of
   which is probably the most common in practice) all emit `SecurityError.MISSING_RIGHTS` and stay
   generic; that is correctly out of this AC's scope, just don't oversell the reschedule half of this AC
   as "a specific message for every rejection." The same six codes are also throwable from three
   **coach**-side accept flows (`BookingService.acceptBooking`, `BookingBatchService.acceptOneBooking`,
   `RescheduleService.acceptReschedule` — note `acceptReschedule` is also where `excl_bkg_coach_slot_overlap`
   actually becomes reachable) whose frontend catch sites (`CoachBookingRequestsPage.vue`'s
   `handleAccept`/`handleAcceptAll`, and the coach accept-reschedule flow) are equally generic today.
   Fixing those is out of scope here — different page, different store, would roughly double this AC's
   file count — and is logged as a new `deferred-work.md` item by Task 6 rather than left silently
   unlisted.

   **Backend (4 files, 6 lines each):** add all six keys to `src/main/resources/i18n/messages.properties`,
   `messages_en.properties`, `messages_de.properties`, `messages_fr.properties`, matching each file's
   existing `key=value` style (see the neighboring `video.*`/`validation.phone.*` blocks). Note
   `messages_fr.properties` mixes both a literal-UTF-8 style (e.g. `video.notFound=La vidéo demandée...`)
   and a `\uXXXX`-escaped style (e.g. `email.greeting=Cher/Chère {0},`) in different blocks — match
   whichever convention the *immediately neighboring* keys in each file use, don't assume one file-wide
   rule. English wording should be short and specific, e.g.
   `booking.coachUnavailable=This coach is currently unavailable.`,
   `booking.slotUnavailable=This time slot is no longer available.`,
   `booking.invalidSessionDuration=The requested session length does not match this coach's session
   duration.`, `booking.duplicateSlotStartTime=Two or more requests in this batch have the same start
   time.`, `booking.overlappingSlots=Two or more requests in this batch overlap.` — translate the German
   and French bundles to the same meaning, matching the register/tone of neighboring keys in each file.
   **`booking.batchSizeExceeded` needs its own handling — do not hardcode a number.** The batch size limit
   is `configService.getLong("booking.batch.maxSize")` (`BookingBatchService.java:96`), seeded to `'5'` by
   `V36__booking_batches.sql:19` (confirmed the only migration touching this key) — **not** 10, which is a
   different, unrelated constraint (`CreateBatchRequest`'s `@Size(min = 1, max = 10)`, which produces a
   `MethodArgumentNotValidException` and a completely different error key, never `batchSizeExceeded`). The
   backend `BatchRuleViolationException("booking.batchSizeExceeded")` throw site passes no arguments, so
   the backend `messages*.properties` entry cannot be parameterized — word it without a number:
   `booking.batchSizeExceeded=You have reached the maximum number of sessions allowed in one batch.` The
   frontend copy CAN be parameterized, and should be, using the value the page already holds:
   `BookingRequestPage.vue`'s `maxBatchSize` ref (`:258`, populated from `getBatchConfig()` at `:530-531`).
   Add the frontend key as `booking.errors.batchSizeExceeded: 'You can request up to {max} sessions in one
   batch.'` and call it as `t('booking.errors.batchSizeExceeded', { max: maxBatchSize.value })` — mirroring
   the existing parameterized-key convention already used one block over,
   `booking.batch.selectedCount: '{n} of {max} slots selected'` (`en-US/index.js:901`). Translate both the
   backend (unparameterized) and frontend (parameterized) German/French versions to the same meaning.

   **Frontend (3 locale bundles):** in `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js`, add a new
   `errors` block as a sibling of the existing `booking.requests`/`booking.completion`/`booking.reschedule`/
   `booking.batch` blocks (`en-US/index.js` — `requests` at `:744`, `completion` at `:861`, `reschedule` at
   `:877`, `batch` at `:897` — insert `errors` as a new sibling key under `booking`, mirroring the same
   structural pattern in `de-DE` and `fr-FR`), with the same six keys (`coachUnavailable`,
   `slotUnavailable`, `invalidSessionDuration`, `batchSizeExceeded`, `duplicateSlotStartTime`,
   `overlappingSlots`) and translated display strings.

   **Wire the three catch blocks** (not four — get this count right; there are exactly three call sites)
   to branch on `err?.response?.data?.errorMsg?.errorKey` before falling back to the existing generic
   message. **This is not the same field the codebase's one existing precedent for this pattern uses —
   that precedent is itself broken and must not be copied.** `DrillDetailPanel.vue:382-390` branches on
   `err?.response?.data?.helpCode`, but `helpCode` is never an error code: `ApiAdvice.logError` (`:636-651`)
   sets it to `ApplicationException.getSupportId()`, which is always a fresh
   `SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())))` — a random per-request
   support ID, never derived from the thrown `ErrorCode`. The actual error code lands in
   `errorMsg.errorKey` (`ApiAdvice.toErrorDTO:619-630` builds `new ErrorDto(helpCode, new
   ErrorMsg(msgKey, message))`, and `ErrorMsg` is `record ErrorMsg(String errorKey, String message)`).
   The repo's own axios interceptor confirms this is the real contract:
   `src/frontend/src/boot/axios.js:137` reads `const errorKey = data?.errorMsg?.errorKey || ''`. So
   `DrillDetailPanel.vue`'s `helpCode === 'video.quotaExceeded'` branch has never fired in production —
   do not use it as a template for the condition, only for the general if/else-if shape. Each of the
   three sites also currently uses a **bare** `catch {` with no error binding — change it to
   `catch (err) {` so `err` is available for the new branch:
   - `BookingRequestPage.vue`'s `submit()` catch (`:480`, currently `} catch {`) — branch on
     `err?.response?.data?.errorMsg?.errorKey`: `coachUnavailable`, `slotUnavailable`,
     `invalidSessionDuration`
   - `BookingRequestPage.vue`'s `submitBatchRequest()` catch (`:499`, currently `} catch {`) — branch on
     the same field, **four** codes only: `batchSizeExceeded`, `invalidSessionDuration`,
     `duplicateSlotStartTime`, `overlappingSlots`. Do **not** add branches for `coachUnavailable` or
     `slotUnavailable` here — `createBatch` cannot throw either (see this AC's throw-site list above).
   - `ParentBookingsPage.vue`'s `submitReschedule()` catch (`:209`, currently `} catch {`) — branch on the
     same field, `invalidSessionDuration` **only** (see the AC's `RescheduleService` note above — the
     other two codes cannot reach this flow; do not add branches for them)
   Do not change any other catch block in either file (e.g. `handleConfirmCompletion`'s catch at
   `ParentBookingsPage.vue:167` uses an unrelated `error.verificationFailed` key — leave it, and leave it
   as a bare `catch {}` too). Do not build a shared composable/helper for this mapping — three call sites
   with an if/else-if chain each, matching this codebase's existing per-component inline convention, not a
   premature abstraction. (Note for context, not action: `submit()`'s catch also wraps
   `submitBookingRequest`'s trailing `loadParentBookings()` call, and `submitReschedule()`'s wraps
   `handleRequestReschedule`'s trailing one — a reload failure *after* a successful write would show the
   submit-failed toast. Pre-existing, and the new branches degrade correctly since a reload failure won't
   carry a matching `errorKey`, so this is not a regression to fix here.)

   No frontend test suite exists in this repo (standing gap, recorded by every UAT/deferred story since
   `skillars-5-4` W9) — verify manually via the dev server is the established path here, but this session
   has no browser-driving tooling; verify instead by (1) `npx eslint` clean on all touched `.vue`/`.js`
   files, (2) `npx prettier --check` on all six touched frontend files (three i18n bundles, both `.vue`
   pages) — the three i18n bundles and `BookingRequestPage.vue` are Prettier-clean today (confirmed
   2026-08-17; `deferred-27` AC6 already cleaned the three i18n bundles specifically), so run
   `npx prettier --write` on those four if hand-editing breaks formatting, but leave
   `ParentBookingsPage.vue`'s existing (pre-this-story) Prettier violation alone — reformatting it is the
   same out-of-scope call `deferred-27` made about the wider repo-wide Prettier debt, (3) tracing each new
   branch by hand against its throw site, confirming the `errorKey` string sent by the backend
   (`BookingError.getErrorCode()` / the literal in `BatchRuleViolationException` constructions, both of
   which pass straight through as `errorMsg.errorKey` unmodified) matches the frontend string exactly.
   Flag explicitly in Dev Agent Record that a live browser check was not performed, per this project's
   established convention (see `deferred-26`'s AC4 Dev Agent Record entry for the exact phrasing pattern).

3. **New `SubscriptionResourceIT` (or a comparably-scoped new test class) covers the 9 of
   `SubscriptionResource`'s 10 endpoints that currently have zero HTTP-level coverage.**
   `SubscriptionResource.java` (`/api/payment/subscriptions/**`) has 10 endpoints. One —
   `GET /player/me` — already has real HTTP-level coverage via
   `src/test/java/com/softropic/skillars/platform/payment/api/PlayerSubscriptionOwnershipIT.java`
   (four cases: 200 own-player, 403 other-player, 403 wrong-player-id, 401 unauthenticated). The other
   nine have none — only the service-layer `SubscriptionLifecycleIT.java` exists, which calls
   `SubscriptionService` directly and never goes through the controller (auth annotations, request
   validation, response serialization are all untested for these nine):
   - Coach: `GET /coach/tiers` (`permitAll()`), `GET /coach/me` (`HAS_COACH_ROLE`),
     `POST /coach/subscribe` (`HAS_COACH_ROLE`), `POST /coach/change-tier` (`HAS_COACH_ROLE`),
     `DELETE /coach` (`HAS_COACH_ROLE`)
   - Player: `GET /player/tiers` (`permitAll()`), `POST /player/subscribe` (`HAS_PARENT_ROLE`),
     `POST /player/change-tier` (`HAS_PARENT_ROLE`), `DELETE /player` (`HAS_PARENT_ROLE`)

   **Follow `PlayerSubscriptionOwnershipIT`'s `@WebMvcTest(SubscriptionResource.class)` pattern exactly —
   do not use `BasePaymentIT`/`httpTestClient`/cookie-login.** (An earlier draft of this AC prescribed a
   `BasePaymentIT`+`httpTestClient` full-stack approach mirroring `SessionPackPaymentResourceIT`; that was
   wrong on inspection — `SessionPackPaymentResourceIT` is *itself* `@WebMvcTest` with `MockMvc` and a
   local `TestSecurityConfig`, not a `BasePaymentIT` subclass, and no `BasePaymentIT` subclass anywhere in
   this codebase performs an HTTP login. `BasePaymentIT.insertTestCoach` also seeds
   `password_hash = '{noop}test'`, which a bare `BCryptPasswordEncoder` — this app's actual bean,
   `SecurityConfiguration.java:123`, not a `DelegatingPasswordEncoder` — cannot interpret, and seeds no
   `main.authority`/`main.user_authority` rows either, so a real HTTP login would fail regardless.) Create
   `src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceIT.java` with
   `@WebMvcTest(SubscriptionResource.class)`, importing `PlayerSubscriptionOwnershipIT.TestSecurityConfig`
   (package-private, same package, reusable via `@Import(PlayerSubscriptionOwnershipIT.TestSecurityConfig.class)`
   — do not duplicate it) plus `@EnableMethodSecurity` is already carried by that imported config so
   `@PreAuthorize` is actually evaluated in the slice. Reusing another test class's `@TestConfiguration` via
   `@Import` (rather than copying it, which is what `SessionPackPaymentResourceIT` does with its own local
   copy) couples the two test classes — a future edit to `PlayerSubscriptionOwnershipIT`'s filter chain
   would silently change `SubscriptionResourceIT`'s expectations too. Reuse anyway (it's the same package,
   legal, and avoids drift between two copies of the same chain) but be aware of the coupling.

   **`@MockitoBean` five beans, not three — the class will fail to load the Spring context with only
   `SubscriptionService`, `CoachProfileRepository` and `SecurityUtil` mocked.** `@WebMvcTest` also
   instantiates every `@Component`/`@RestControllerAdvice` on the classpath, not just the named
   controller: `SecurityAdviceFilter` (`@Component extends OncePerRequestFilter`) requires a
   `JwtSecretService` in its constructor, and `VideoApiAdvice` (`@RestControllerAdvice`) requires a
   `VideoMetrics` — both are picked up by the slice regardless of this test having nothing to do with
   video or JWT parsing directly, and both are absent from the Spring context unless mocked.
   `PlayerSubscriptionOwnershipIT` already mocks all five it needs
   (`SubscriptionService`, `CoachProfileRepository`, `SecurityUtil`, `JwtSecretService`, `VideoMetrics`,
   plus a sixth, `PlayerProfileRepository`, that exists only to feed the real `PlayerOwnershipGuard` bean
   that class also `@Import`s) — mirror the first five, skip `PlayerProfileRepository` (this class does
   not `@Import` `PlayerOwnershipGuard`, so nothing needs it). For coach-role requests, stub
   `securityUtil.getCurrentCoachUserId()` and `coachProfileRepository.findByUserId(coachUserId)` (both
   consumed by `SubscriptionResource.resolveCoachId():127-131`); for parent-role requests, stub
   `securityUtil.requireCurrentUserId()` (consumed by `currentParentId():134-136`). Use
   `@WithMockUser(roles = "COACH")` / `@WithMockUser(roles = "PARENT")` for the role-gated endpoints (no
   custom `Principal` mock is needed here — that machinery in `PlayerSubscriptionOwnershipIT` exists only
   because `@playerOwnershipGuard.check(authentication, #playerId)` inspects the real `Principal` type,
   which none of these nine endpoints' `@PreAuthorize` expressions do).

   **`permitAll()` does not mean "no auth needed" for this test's own security chain.** The
   `TestSecurityConfig` being reused declares `.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())`
   with no path-specific carve-out, so an unauthenticated request to `GET /coach/tiers`/`GET /player/tiers`
   is rejected by that filter chain (401) before `@PreAuthorize("permitAll()")` is ever consulted — this
   matches the real production filter chain's behavior too (`/api/payment/subscriptions/**` is not in
   `AppEndpoints.PUBLIC_ENDPOINTS`, so `activate.security`'s default-true `SecurityFilterChain` also 401s
   an unauthenticated call before method security runs). For these two endpoints, assert 401 unauthenticated
   **and** 200 with any authenticated role (`@WithMockUser` with either role suffices — `permitAll()` is
   role-agnostic once authenticated).

   Minimum coverage (do not attempt exhaustive coverage of every field/validation branch — this AC closes
   the "no REST-layer coverage" gap on these nine endpoints, not a full test-matrix):
   - `GET /coach/tiers`: 200 (any authenticated role), 401 (unauthenticated)
   - `GET /coach/me`: 200 happy path (mocked `subscriptionService.getCoachSubscription`), 403 for a
     `PARENT`-role caller
   - `subscribeCoach`: 200 happy path
   - One `change-tier` and one cancel (`DELETE`) happy path for the coach role, asserting `204 No Content`
   - `GET /player/tiers`: 200 (any authenticated role), 401 (unauthenticated)
   - `subscribePlayer`: 200 happy path
   - One `change-tier` and one cancel (`DELETE`) happy path for the player role, asserting `204 No Content`
   - **One 400 case**: `POST /player/subscribe` with an empty JSON body (`{}`) — every request record this
     resource accepts declares `@NotNull` on all its fields (`CoachSubscribeRequest`,
     `CoachChangeTierRequest`, `PlayerSubscribeRequest`, `PlayerChangeTierRequest`), so this is the cheapest
     possible proof that request validation is actually exercised in this slice (it is not exercised by
     the service-layer `SubscriptionLifecycleIT`, which calls the service directly and never constructs
     these DTOs from JSON). Without this case, this AC's own stated rationale ("...request validation...
     are all untested for these nine") would remain true even after the AC ships.
   Do **not** duplicate `PlayerSubscriptionOwnershipIT`'s existing ownership-guard case — an earlier draft
   of this AC listed "one ownership-guard case on `GET /player/me`" as required minimum coverage, which is
   a verbatim duplicate of that file's existing `getPlayerSubscription_otherPlayerId_returns403NotFound`.
   `GET /player/me` needs no new coverage from this AC at all. **Also note in the completion notes:**
   because `SubscriptionService` is `@MockitoBean`-mocked in this slice, the real ownership enforcement on
   `subscribePlayer`/`changePlayerTier`/`cancelPlayerSubscription` (`SubscriptionService.assertPlayerOwnership`
   at `:276`/`:339`/`:414`) is invisible to these tests — it is real and correctly wired (there is no IDOR
   on these three endpoints, unlike what their bare `@PreAuthorize(HAS_PARENT_ROLE)` might suggest at a
   glance), but this AC's mocked-service tests cannot prove it; that guard is service-layer, not
   controller-layer, coverage.
   Run `mvn -o test -Dtest=SubscriptionResourceIT` green.

4. **`BookingService.applyRefundLogic`'s negative-`hoursUntilSession` branch gets a documenting comment
   that states what the code actually does, not an assumed intent it doesn't have.** `BookingService.java:762-763`
   computes `hoursUntilSession` and maps it to a refund eligibility string (`> 24` → `"FULL"`, `>= 6` →
   `"PARTIAL"`, else → `"NONE"`) with no comment explaining the negative case (a `CANCEL_PARENT` event
   arriving after the session's start time has already passed). **Important correction from a senior-dev
   review of this AC's earlier draft:** that draft's proposed comment claimed a negative value only
   happens because "a caller used the wrong event" (i.e. should have fired `NO_SHOW_COACH`/`NO_SHOW_PLAYER`
   instead) — that is false. `cancelBookingAsParent` (`BookingService.java:611-661`), the ordinary
   parent-facing cancel path, is the **only** caller that reaches this branch with `CANCEL_PARENT`, and it
   has **no start-time guard at all**: it checks ownership, reads the locked row, refuses only when the
   booking is `PAYMENT_PENDING` with a `CAPTURE_PENDING` payment (`:639-643`), and otherwise transitions
   unconditionally. A parent cancelling a booking that is still `UPCOMING` after its start time has passed
   — the coach never started the session — is a normal, reachable path through the UI today, and it is
   the negative-`hoursUntilSession` case this branch handles. Add a comment above line 762 stating the
   **true** fact instead: no guard currently prevents a post-start-time parent cancellation; when one
   happens, `hoursUntilSession` goes negative and this falls through to `"NONE"`; whether that case should
   instead be settled as a coach no-show (a different `BookingEvent` with different refund semantics) is
   an open product question, not resolved by this comment — see Task 6, which files that question as a new
   `deferred-work.md` item. Do not assert that the branch is unreachable-by-design or that it represents
   caller error — both are false and would mislead the next reader into treating a live, reachable case as
   dead code. Doc-only — no behavior change, no test required.

5. **Two boundary-condition tests close D22** — the credit-routing Case A/B boundary and the cash-out
   amount-equals-balance boundary, both correct today per direct code read but pinned by no test.
   - **`CreditRoutingTest.java`**: add `caseAB_boundary_balanceExactlyEqualsSessionPrice_zeroStripeCharge()`,
     mirroring `caseA_fullCreditCoversBooking_noStripeCall()`'s structure (`:83-95`) exactly, but stub
     `creditWalletService.getBalance(PARENT_ID)` to return `SESSION_PRICE` (`"50.00"`) instead of
     `"100.00"`. Assert `verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any())`
     and `verify(persistenceService).persistPaymentSuccess(eq(BOOKING_ID), eq(SESSION_PRICE),
     eq(BigDecimal.ZERO), isNull(), isNull(), eq(PARENT_ID), anyString(), anyString(),
     any(Instant.class), anyString())` — pinning that `PaymentLifecycleService.handleCreditBasedBooking`'s
     `creditToUse.compareTo(event.getSessionPrice()) >= 0` boundary check (`PaymentLifecycleService.java:180`)
     correctly treats an exact balance match as "fully covered" rather than triggering a spurious
     zero-amount Stripe call.
   - **`CashOutServiceTest.java`**: add `processCashOut_amountEqualsBalance_succeeds()`, mirroring
     `processCashOut_feeCalculatedCorrectly_refundIssuedWithNetAmount()`'s structure (`:47-67`) — **but not
     literally, in one respect**: that mirrored test declares `ArgumentCaptor<BigDecimal> amountCaptor` and
     `ArgumentCaptor<String> typeCaptor` and never uses either (pre-existing dead code in the test being
     mirrored). Do not copy those two unused declarations into the new test — omit them. Otherwise mirror
     the structure exactly, but stub `creditWalletService.getBalance(PARENT_ID)` to return `AMOUNT`
     (`"100.00"`) instead of `"200.00"`. Assert the call completes without throwing and `paymentGateway.refund` is still invoked
     with the correctly-calculated net amount (same fee math as the mirrored test:
     `100 * 0.025 + 0.25 = 2.75`; net `97.25`) — pinning that `CashOutService`'s
     `balance.compareTo(requestedAmount) < 0` guard (`CashOutService.java:26`) correctly treats an exact
     match as sufficient balance, not insufficient.
   - Do **not** touch `CashOutService.java` or `PaymentLifecycleService.java` production code — both
     boundary conditions are already correct; this AC only adds the missing regression coverage.
     `mvn -o test -Dtest=CreditRoutingTest,CashOutServiceTest` must be green.

6. **Ledger hygiene in `deferred-work.md`.** All five items this story closes already carry a live
   `[OWNED BY skillars-deferred-28 ACn — see …]` marker, placed during this story's creation
   (`deferred-work.md:809` AC4, `:1120` AC5, `:1253` AC2, `:1387` AC3, `:1439` AC1). **This step REPLACES
   each `[OWNED BY …]` marker with `[CLOSED by skillars-deferred-28 ACn]` followed by a substantive prose
   description of the actual change made** — do not merely append a `[CLOSED by …]` tag next to the
   now-stale `[OWNED BY …]` one; the marker gets replaced, matching the convention every prior
   `skillars-deferred-N` closure in this file follows (e.g. `deferred-work.md:1440`, `:1448`, `:1449` —
   each is a full sentence or more describing what actually shipped, any correction found along the way,
   and where the code now lives, not a bare tag). Do not delete the original item's descriptive text before
   the tag — only the ownership marker itself gets replaced.

   Also add **four** new items, found during this story's creation and deliberately not fixed here:
   - The three **coach**-side accept flows (`BookingService.acceptBooking`,
     `BookingBatchService.acceptOneBooking`, `RescheduleService.acceptReschedule`) can throw the same
     `booking.*` codes AC2 fixes on the parent side, but their frontend catch sites
     (`CoachBookingRequestsPage.vue`'s `handleAccept`/`handleAcceptAll`, and the coach accept-reschedule
     flow) remain generic — out of AC2's scope (see AC2's own scope note), logged here so it isn't
     silently unlisted.
   - `DrillDetailPanel.vue:382-390`'s video-upload catch block branches on
     `err?.response?.data?.helpCode` (`=== 'video.quotaExceeded'` / `'video.constraintViolated'`), but
     `helpCode` is a random per-request support ID, never an error code (see AC2's detailed explanation)
     — the same bug class AC2 fixes for booking, on a different page. Both branches have never fired in
     production; the fix is the same one-line field-name correction to `errorMsg.errorKey`, but it is a
     different file/module (video, not booking) so it is not folded into AC2.
   - **Product question, filed per AC4's rework:** `cancelBookingAsParent` has no guard against a parent
     cancelling a booking whose session start time has already passed. Should that case settle as a coach
     no-show (a different `BookingEvent`, different refund semantics) instead of falling through to
     `applyRefundLogic`'s `"NONE"` refund eligibility via the ordinary `CANCEL_PARENT` path? Not resolved
     by this story — AC4 only documents the current (true) behavior, it does not change it.
   - **Adjacent divergence found while verifying AC4, not fixed here:** `cancelBookingAsParent:647-649`
     computes a second, independent refund-eligibility boolean —
     `refundEligible = paymentWasCaptured && requestedStartTime.isAfter(now.plus(24, HOURS))` — attached
     to `BookingCancelledByParentEvent`, while `applyRefundLogic` (the method AC4 documents) separately
     writes a three-tier `FULL`/`PARTIAL`/`NONE` string onto the `Booking` row itself. For a cancellation
     6–24 hours before the session, the row says `"PARTIAL"` while the event says not-eligible — two
     different refund rules disagreeing on the same booking. Pre-existing, not introduced by this story;
     worth its own investigation into which rule is authoritative.

## Tasks / Subtasks

- [x] Task 1 — Add `CoachMediaItemTest` (AC: #1)
  - [x] Create `src/test/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItemTest.java`
    with the two cases in AC1 (sets when null, preserves when already set)
  - [x] `mvn -o test -Dtest=CoachMediaItemTest` green (2/2)

- [x] Task 2 — Booking error messaging on the three parent-side flows (AC: #2)
  - [x] Add all 6 `booking.*` keys to `messages.properties`, `messages_en.properties`,
    `messages_de.properties`, `messages_fr.properties` (match each file's neighboring-key escaping style;
    `batchSizeExceeded`'s backend copy carries no number — see AC2)
  - [x] Add a new `booking.errors` block with the same 6 keys to `en-US/index.js`, `de-DE/index.js`,
    `fr-FR/index.js`, as a sibling of `requests`/`completion`/`reschedule`/`batch`; `batchSizeExceeded`'s
    frontend copy is parameterized with `{max}` (see AC2)
  - [x] Change `BookingRequestPage.vue`'s `submit()` catch from bare `catch {` to `catch (err) {` and
    branch on `err?.response?.data?.errorMsg?.errorKey` for 3 codes
  - [x] Change `BookingRequestPage.vue`'s `submitBatchRequest()` catch from bare `catch {` to
    `catch (err) {` and branch on the same field for **4** codes only (`batchSizeExceeded`,
    `invalidSessionDuration`, `duplicateSlotStartTime`, `overlappingSlots` — NOT `coachUnavailable`/
    `slotUnavailable`, which `createBatch` cannot throw)
  - [x] Change `ParentBookingsPage.vue`'s `submitReschedule()` catch from bare `catch {` to `catch (err) {`
    and branch on the same field for `invalidSessionDuration` only
  - [x] `npx eslint` clean on all touched `.vue`/`.js` files
  - [x] `npx prettier --check` on all six touched frontend files; `--write` on the four that are clean
    today (three i18n bundles, `BookingRequestPage.vue`) if hand-editing broke their formatting; leave
    `ParentBookingsPage.vue`'s pre-existing violation untouched
  - [x] Hand-trace each new branch against its backend throw site to confirm `errorKey` string equality

- [x] Task 3 — Add `SubscriptionResourceIT` (AC: #3)
  - [x] Read `PlayerSubscriptionOwnershipIT.java` in full before writing anything — reuse its
    `TestSecurityConfig` via `@Import`, mirror its 5-bean mocking shape (not `PlayerProfileRepository`)
  - [x] Create `SubscriptionResourceIT.java` as `@WebMvcTest(SubscriptionResource.class)`,
    `@MockitoBean`-ing `SubscriptionService`, `CoachProfileRepository`, `SecurityUtil`, `JwtSecretService`,
    `VideoMetrics`
  - [x] Implement the minimum-coverage cases listed in AC3 (**9** endpoints: coach tiers/me/subscribe/
    change-tier/cancel, player tiers/subscribe/change-tier/cancel) plus the one 400 validation case — do
    not re-cover `GET /player/me`
  - [x] `mvn -o test -Dtest=SubscriptionResourceIT` green (13/13)

- [x] Task 4 — Document the negative-`hoursUntilSession` branch (AC: #4)
  - [x] Add the comment above `BookingService.java:762`

- [x] Task 5 — Add the two D22 boundary tests (AC: #5)
  - [x] Add `caseAB_boundary_balanceExactlyEqualsSessionPrice_zeroStripeCharge` to `CreditRoutingTest.java`
  - [x] Add `processCashOut_amountEqualsBalance_succeeds` to `CashOutServiceTest.java`
  - [x] `mvn -o test -Dtest=CreditRoutingTest,CashOutServiceTest` green (9/9, 5/5)

- [x] Task 6 — Ledger hygiene (AC: #6)
  - [x] Replace each of the 5 items' existing `[OWNED BY skillars-deferred-28 ACn]` marker with
    `[CLOSED by skillars-deferred-28 ACn]` plus a prose description of the actual change (not a bare tag)
  - [x] Log the four new items (coach-side accept-flow messaging, `DrillDetailPanel.vue`'s `helpCode` bug,
    the post-start parent-cancellation product question, the two-refund-rules divergence) as new
    `deferred-work.md` entries
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-28-...` entry status as this story progresses
    (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention

### Review Findings

Code review 2026-08-17. **Layer coverage is incomplete:** the Blind Hunter layer ran and returned 13
findings; the Edge Case Hunter and Acceptance Auditor layers both died on an API session limit and
returned nothing. Their prompt files (`code-review-prompt-2-edge-case-hunter.md`,
`code-review-prompt-3-acceptance-auditor.md`) and the frozen diff
(`code-review-diff-deferred-28.patch`) are on disk for a follow-up run. Four Blind Hunter findings were
dismissed as artifacts of its deliberate project-blindness (verified false against source): a
non-existent fourth frontend locale bundle, a scale-sensitivity claim against `eq(BigDecimal.ZERO)`, a
claim that the two 401 assertions contradict production behaviour, and a claim that `submit()`'s
`slotUnavailable` branch is dead.

Mechanically verified green before triage: `CoachMediaItemTest` 2/2, `CreditRoutingTest` 9/9,
`CashOutServiceTest` 5/5, `SubscriptionResourceIT` 13/13, `npx eslint` clean on all five touched frontend
files, and `prettier --check` showing the identical 4 pre-existing hunks as at HEAD (zero regression).

- [x] [Review][Decision] **`ChronoUnit.HOURS` truncation makes AC4's documented ">24h ⇒ FULL" rule
  behave as ">=25h", and the new comment does not disclose it** — `HOURS.between` truncates toward zero,
  so a cancellation 24 h 59 m out yields `24`, fails `> 24`, and is recorded `PARTIAL`. The sibling rule
  in `cancelBookingAsParent` (`refundEligible = ... isAfter(now.plus(24, HOURS))`) is exact, not
  truncated, so the two disagree across the whole 24–25 h window on top of the 6–24 h divergence already
  logged. AC4's nine-line comment documents only the negative case and presents the expression as fully
  analysed. Decision needed: is `> 24` the intended boundary (making the user-facing "24 hours" copy
  wrong), or should it be `>= 24`? Doc-only fix vs. behaviour change vs. defer.
  [`BookingService.java:761-762`]
  **Resolved (Mbah, 2026-08-17): doc-only.** Added a second comment block above the `hoursUntilSession`
  line disclosing the truncation quirk explicitly (`> 24` is really `>= 25` in practice) and noting it
  widens the already-logged divergence between `applyRefundLogic`'s truncated check and
  `cancelBookingAsParent`'s exact one to the full 24–25h window. No behavior change — the boundary intent
  itself remains an open product question, not decided by this patch.

- [x] [Review][Patch] Slice-test happy paths assert only the status code, so an argument-identity bug in
  the controller still passes green — an argument-specific stub that misses returns `null`,
  `ResponseEntity.ok(null)` still yields 200, and `@MockitoBean` does not flag the dead stub; affects the
  five tests that build a response DTO. Add one `jsonPath` or `verify(...)` each.
  [`SubscriptionResourceIT.java:70,88,108,147,165`]
  **Fixed.** Added a `jsonPath` assertion on the response body to all five: `getCoachTiers_authenticated_returns200`
  (`$[0].tier`), `getMyCoachSubscription_happyPath_returns200` (`$.tier`), `subscribeCoach_happyPath_returns200`
  (`$.tier`), `getPlayerTiers_authenticated_returns200` (`$[0].tier`), `subscribePlayer_happyPath_returns200`
  (`$.playerId`, `$.tier`). 13/13 still green.
- [x] [Review][Patch] The two load-bearing mock beans need a one-line comment or a future prune breaks
  the slice — `JwtSecretService` is required by `SecurityAdviceFilter` (a `@Component` filter) and
  `VideoMetrics` by `VideoApiAdvice` (`@RestControllerAdvice`); both are pulled into every `@WebMvcTest`
  slice, which is not obvious from this file. [`SubscriptionResourceIT.java:51-52`]
  **Fixed.** Added a comment above each field naming the exact class and constructor argument that
  requires it.
- [x] [Review][Patch] German wording diverges between backend and frontend for the same error code —
  backend says `Dieser Zeitfensterplatz ist nicht mehr verfügbar.`, frontend says
  `Dieses Zeitfenster ist nicht mehr verfügbar.`; "Zeitfensterplatz" is also unidiomatic and used nowhere
  else. Align the backend string to the frontend's. [`messages_de.properties:65`]
  **Fixed.** Backend string changed to `Dieses Zeitfenster ist nicht mehr verfügbar.`, matching the
  frontend exactly.
- [x] [Review][Patch] Cash-out boundary test hides its own boundary behind a literal — the point is
  `amount == balance`, but it stubs `new BigDecimal("100.00")` on one side and passes the `AMOUNT`
  constant on the other, so the equality is invisible at the call site. The story's AC5 specified
  stubbing `AMOUNT`. [`CashOutServiceTest.java:72`]
  **Fixed.** Balance stub changed from the `new BigDecimal("100.00")` literal to the `AMOUNT` constant
  directly. 5/5 still green.
- [x] [Review][Patch] Ledger sentence is misread as "`slotUnavailable` can never fire from parent
  flows" — the claim is correctly scoped to the `excl_bkg_coach_slot_overlap` *constraint* (true: its
  partial index excludes `REQUESTED`), but a reviewer parsed it as covering the code itself, which
  `BookingService.createBookingRequest:247-255` does throw. Tighten the wording to name the constraint
  explicitly. [`deferred-work.md:1461`]
  **Fixed.** Reworded to explicitly name the "DB constraint mapping" as the scope of "can never fire" and
  added a clarifying sentence that `booking.slotUnavailable` itself is throwable directly on the parent
  side (which is why AC2 wires it into `submit()`'s catch).

- [x] [Review][Defer] `{max}` quotes the client's cached batch limit, which is by definition not the
  limit that rejected the request [`BookingRequestPage.vue:512`] — deferred, needs a contract change
- [x] [Review][Defer] `router.push` inside `try` reports post-success navigation failures as booking
  failures, and `submitBatchRequest` can show success and failure toasts together
  [`BookingRequestPage.vue:484,507`, `ParentBookingsPage.vue:208`] — deferred, pre-existing
- [x] [Review][Defer] No telemetry when an unhandled `booking.*` code falls through to the generic toast
  [`BookingRequestPage.vue:485,509`, `ParentBookingsPage.vue:209`] — deferred, pre-existing

## Dev Notes

- **Scope discipline.** Five small, independently-safe items across three modules (marketplace, booking,
  payment). Do not use this as a pretext to "clean up while you're in there" — e.g. don't also fix the
  coach-side accept flows' generic error toasts (logged as a new ledger item instead, Task 6), don't fix
  `DrillDetailPanel.vue`'s identical `helpCode` bug (also logged, not fixed, Task 6), don't build a shared
  frontend error-mapping composable, don't widen `SubscriptionResourceIT` into exhaustive coverage of
  every field, don't tighten other credit-routing boundaries beyond the two D22 names, don't add other
  documenting comments to `applyRefundLogic` beyond the one AC4 asks for. If something else adjacent looks
  wrong, note it as a new `deferred-work.md` item; don't fix it here.

- **AC4 and AC5 are entirely test-coverage/documentation — zero other production behavior changes beyond
  AC4's one comment.** Both were added after the original three-item draft; re-verified independently
  against current source during this addition (2026-08-17), same discipline as the original three.

- **Do not trust this story's own line-number citations as gospel.** Re-run the greps cited in each AC at
  implementation time before writing code.

- **AC2 is this story's largest and only full-stack item.** 4 backend `.properties` files + 3 frontend
  i18n bundles + 2 `.vue` pages (carrying 3 catch-block edits between them) = 9 files touched for one
  logical fix. The single most important detail in this
  entire story: **branch on `err?.response?.data?.errorMsg?.errorKey`, never `err?.response?.data?.helpCode`.**
  `helpCode` is a random support ID (see AC2's full explanation) — a story-review of this story's own
  draft caught this exact mistake before any code was written, because the only existing precedent for
  this pattern in the codebase (`DrillDetailPanel.vue`) copies the wrong field and has therefore never
  worked. Get the exact `errorKey` strings right by reading the throw sites, not by guessing from the
  ledger's code-name list — `BookingError.java`'s enum values map through `getErrorCode()`, while
  `BookingBatchService`'s three codes are raw string literals passed straight to
  `BatchRuleViolationException`'s constructor (`getErrorCode()` returns the constructor argument verbatim,
  no transformation) — both land in `errorMsg.errorKey` unmodified via `ApiAdvice.toErrorDTO`.

- **AC2's ledger source item flags an interaction with `skillars-deferred-18` D6 (the backend's
  German/French validator-message locale resolution is broken — `SecurityAdviceFilter` stores
  `locale.getDisplayLanguage()` ("German") and `Locale.forLanguageTag` expects "de", so non-English
  backend `message` text is unreachable). This does NOT block AC2's fix**: the frontend branches on
  `errorMsg.errorKey` (a stable, locale-independent code) and renders the frontend's **own** vue-i18n
  string via `t(...)` — never the backend-resolved `message` body. The new `messages*.properties` entries
  this AC adds are for API-response completeness/non-frontend consumers only; the user-visible fix works
  regardless of D6. Do not treat D6 as a blocker or attempt to fix it as part of this AC.

- **AC3 follows an existing, working pattern rather than inventing one.** `PlayerSubscriptionOwnershipIT`
  is the only existing `SubscriptionResource` HTTP-level test and the concrete precedent for the
  `@WebMvcTest` + local `TestSecurityConfig` + `@MockitoBean` shape — read it fully before writing
  `SubscriptionResourceIT`, don't re-derive the pattern from a different module's IT.

- **AC1 closes a "no test exists yet" gap without needing the surrounding feature to exist** — it tests a
  package-private callback directly (no persistence, no upload feature needed). Do not treat it as
  blocked on other work.

- **File paths this story touches:**
  - `src/test/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItemTest.java` (AC1, new file)
  - `src/main/resources/i18n/messages.properties` (AC2)
  - `src/main/resources/i18n/messages_en.properties` (AC2)
  - `src/main/resources/i18n/messages_de.properties` (AC2)
  - `src/main/resources/i18n/messages_fr.properties` (AC2)
  - `src/frontend/src/i18n/en-US/index.js` (AC2)
  - `src/frontend/src/i18n/de-DE/index.js` (AC2)
  - `src/frontend/src/i18n/fr-FR/index.js` (AC2)
  - `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC2)
  - `src/frontend/src/pages/parent/ParentBookingsPage.vue` (AC2)
  - `src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceIT.java` (AC3, new file)
  - `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (AC4, comment only)
  - `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java` (AC5)
  - `src/test/java/com/softropic/skillars/platform/payment/service/CashOutServiceTest.java` (AC5)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC6)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC6, status line only)

### Project Structure Notes

- AC1 and AC3 are new-test-file additions to existing modules — no new production classes, no new
  migrations. AC2 is the only production-code-adjacent change (i18n resource files + `.vue` catch blocks),
  and even it adds no new Java/JS logic beyond a per-code branch mirroring an existing (corrected) pattern.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses
  in `sprint-status.yaml` (the "DEFERRED WORK" block, after `skillars-deferred-27`).

### References

- **Note on `story-review.md`:** this file is a rotating scratch file — each new review overwrites the
  previous one's contents rather than appending, so it is a live pointer only to the *most recent* review,
  not a durable citation. It has held (in order) a review of the earlier `skillars-deferred-27` draft
  (which identified the ID collision, the `helpCode` vs. `errorMsg.errorKey` defect, and the
  `SubscriptionResourceIT` false premises corrected in that rework) and then a review of this story's
  first `skillars-deferred-28` draft (which found the AC2 batch-flow/B3 `batchSizeExceeded`-limit defects,
  the AC3 incomplete mock list, and the AC4 false-reasoning defect, all corrected above). Do not cite a
  line number or quote from it going forward — treat its *contents* as ephemeral and this story file's own
  text as the durable record of what each review found and how it was resolved.
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from: code review of
  skillars-deferred-25-jpa-annotation-hygiene-and-stripe-metadata-test-coverage (2026-08-15)" (AC1);
  "## Deferred from: skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)" D2 (AC2);
  "## Deferred from: code review of skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)" D3
  (AC3); "## Deferred from: code review of skillars-3-4-booking-state-machine-sse (2026-06-15)" (AC4);
  "## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet" Group 6
  adversarial deferred (Tests), D22 (AC5)
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItem.java:1-38] —
  confirms AC1's `onCreate()` shape, package-private visibility, and current zero-caller state
- [Source: src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java:1-18] —
  confirms AC2's three enum-backed error codes and their exact `getErrorCode()` strings
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:95-219]
  — confirms `createBatch`'s complete throw set: `batchSizeExceeded` (`:98`), `invalidSessionDuration`
  (`:139`), `duplicateSlotStartTime` (`:159`), `overlappingSlots` (`:179`); confirms its inline
  non-`ACTIVE` coach check (`:115-117`) throws plain `MISSING_RIGHTS`, never `COACH_UNAVAILABLE`; and
  confirms it has no cross-booking-vs-database overlap check (its only overlap logic at `:173-181`
  compares batch slots against each other only) — together, why AC2 restricts `submitBatchRequest()`'s
  branch to four codes, not six
- [Source: src/main/resources/db/migration/V36__booking_batches.sql:19] — confirms
  `booking.batch.maxSize`'s seeded value is `'5'`, not 10 (the number a stale draft of AC2 used, actually
  `CreateBatchRequest`'s unrelated `@Size(max = 10)` bound); `grep -rn "booking.batch.maxSize"
  src/main/resources/db/migration/` confirms no later migration changes it
- [Source: src/frontend/src/pages/parent/BookingRequestPage.vue:258,297-303,530-531,901(en-US/index.js)]
  — confirms `maxBatchSize` is already loaded client-side from `getBatchConfig()` and used to cap basket
  size before submit, informing AC2's `{max}`-parameterized frontend copy and the reachability note on
  `batchSizeExceeded`/`duplicateSlotStartTime`
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:54-119]
  — confirms `requestReschedule`'s only `BookingError` throw is `INVALID_SESSION_DURATION` (`:87-93`); no
  coach-suspension or slot-overlap check exists in this method, which is why AC2 restricts
  `submitReschedule()`'s branch to that one code
  ("Reschedule is only allowed for CONFIRMED or UPCOMING" and duration-mismatch checks are the method's
  only guards)
- [Source: src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:142,619-651] —
  confirms `excl_bkg_coach_slot_overlap` → `booking.slotUnavailable` (409), and confirms `helpCode`
  (`logError`) is a random `SQIDS`-encoded support ID while the real error code lands in
  `errorMsg.errorKey` (`toErrorDTO`)
- [Source: src/frontend/src/boot/axios.js:137] — confirms the codebase's own interceptor already reads
  `data?.errorMsg?.errorKey`, not `data?.helpCode`, as the error-code field
- [Source: src/frontend/src/components/session/DrillDetailPanel.vue:382-390] — confirms the existing
  `helpCode`-branching pattern is itself broken (dead code, never fires) and must not be copied verbatim;
  logged as a new ledger item by Task 6 instead of being fixed here
- [Source: src/frontend/src/i18n/en-US/index.js:744,861,877,897] — confirms the `booking` block's existing
  `requests`/`completion`/`reschedule`/`batch` sibling structure AC2's new `errors` block joins
- [Source: src/frontend/src/pages/parent/BookingRequestPage.vue:480,499] — confirms AC2's two catch-block
  edit sites (`submit()`, `submitBatchRequest()`), both currently bare `catch {`
- [Source: src/frontend/src/pages/parent/ParentBookingsPage.vue:167,209] — confirms AC2's third catch-block
  edit site (`submitReschedule()`, currently bare `catch {`) and the unrelated `handleConfirmCompletion()`
  catch to leave untouched
- [Source: src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java:1-137] —
  confirms AC3's 10 endpoints, their `@PreAuthorize` guards, and the `resolveCoachId()`/`currentParentId()`
  helper methods AC3's mocks target
- [Source: src/test/java/com/softropic/skillars/platform/payment/api/PlayerSubscriptionOwnershipIT.java:1-158]
  — confirms AC3's `@WebMvcTest(SubscriptionResource.class)` + `TestSecurityConfig` + `@MockitoBean`
  pattern to reuse (all 6 mocked beans at `:71-76`), and the existing `GET /player/me` coverage AC3 must
  not duplicate
- [Source: src/main/java/com/softropic/skillars/platform/security/infrastructure/filter/SecurityAdviceFilter.java:34-46]
  — confirms this `@Component`/`OncePerRequestFilter` is picked up by `@WebMvcTest` regardless of the
  target controller and requires a `JwtSecretService` constructor argument, informing AC3's 5-bean mock list
- [Source: src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java:39-51] — confirms
  this `@RestControllerAdvice` is likewise picked up by `@WebMvcTest` and requires a `VideoMetrics`
  constructor argument, the second of AC3's two additional required mocks
- [Source: src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscribeRequest.java,
  CoachChangeTierRequest.java, PlayerSubscribeRequest.java, PlayerChangeTierRequest.java] — confirms every
  field on every request record AC3 covers is `@NotNull`, informing AC3's one added 400 case
- [Source: src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java:35-36] —
  confirms `HAS_COACH_ROLE`/`HAS_PARENT_ROLE`'s exact `hasRole(...)` expressions
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:611-661,757-772]
  — confirms AC4's `applyRefundLogic` current shape and line numbers, and confirms `cancelBookingAsParent`
  (the only caller reaching `applyRefundLogic` with `CANCEL_PARENT`) has no start-time guard — only an
  ownership check and a `PAYMENT_PENDING`+`CAPTURE_PENDING` refusal (`:639-643`) — which is why AC4's
  comment states a reachable behavior rather than the false "caller used wrong event" reasoning a stale
  draft asserted
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java:38-95] —
  confirms AC5's `caseA_fullCreditCoversBooking_noStripeCall` structure to mirror and fixture constants
  (`PARENT_ID`, `SESSION_PRICE`, `BOOKING_ID`)
- [Source: src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:177-182]
  — confirms AC5's `creditToUse`/`stripeAmount` boundary computation in `handleCreditBasedBooking`
- [Source: src/test/java/com/softropic/skillars/platform/payment/service/CashOutServiceTest.java:1-113] —
  confirms AC5's `processCashOut_feeCalculatedCorrectly_refundIssuedWithNetAmount` structure to mirror
- [Source: src/main/java/com/softropic/skillars/platform/payment/service/CashOutService.java:24-28] —
  confirms AC5's `balance.compareTo(requestedAmount) < 0` insufficient-balance guard

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- No implementation blockers or failed-first-attempt issues encountered — all 5 ACs matched the story's
  pre-verified throw sites/entity shapes/mirror-test structures exactly on the first implementation pass.
  All new/changed tests passed on their first run (`CoachMediaItemTest` 2/2, `SubscriptionResourceIT`
  13/13, `CreditRoutingTest` 9/9, `CashOutServiceTest` 5/5).
- `messages_de.properties` is a much smaller file than the other three backend bundles (64 lines, no
  `video.*` block) — the new `booking.*` block was inserted after `validation.timezone.invalid` instead
  of after a `video.*` block, matching this file's own actual neighboring-key convention rather than the
  other three files' shared structure.
- `mvn -o verify`'s stale-report trap (already documented by `skillars-deferred-16`/`-19`/`-27`): three
  pre-existing files in `target/surefire-reports/` (`SessionPackPurchaseRepositoryIT`,
  `SessionPackPaymentResourceIT`, `SessionPackPurchaseLockTimeoutIT`) show old failures from before this
  session (mtimes 2026-08-13/14 and 11:53 on 2026-08-17, before this run started at ~13:3x). One of the
  three source files no longer exists in `src/test/java` at all. Confirmed these are stale by report
  mtime filtering — this run's actual fresh surefire output (900 unit tests) and failsafe output (919 IT)
  are both 0 failures/0 errors; the two overlapping class names also have fresh, passing failsafe reports
  from this exact run (`SessionPackPurchaseRepositoryIT` 1/1, `SessionPackPaymentResourceIT` 13/13).

### Completion Notes List

- All 5 ACs closed as scoped; AC4 is doc-only (no behavior change), AC1/AC3/AC5 are pure test additions
  (no production code touched), AC2 is the only functional change (i18n resources + 3 Vue catch-block
  edits, no new Java/JS logic beyond per-code branches).
- AC1: `CoachMediaItemTest` added exactly as the story's own code sample specified — 2 cases, no
  persistence context, no Spring context. 2/2 green.
- AC2: added all 6 `booking.*` keys to the 4 backend `messages*.properties` bundles (placed in a new
  "Booking module"/"Module de réservation"/"Buchungsfehler" block, positioned per each file's own
  existing neighboring-key convention rather than a single shared layout) and a new `booking.errors`
  block to the 3 frontend `i18n/*/index.js` bundles (sibling of `batch`, matching each locale's existing
  `booking.*` sub-block nesting). Wired exactly 3 catch blocks to branch on
  `err?.response?.data?.errorMsg?.errorKey` (never `helpCode` — confirmed the codebase's only existing
  precedent for this pattern, `DrillDetailPanel.vue`, uses the wrong field and has never fired), with the
  exact per-flow code subsets the story's throw-site analysis specified (3 for `submit()`, 4 for
  `submitBatchRequest()`, 1 for `submitReschedule()`). `npx eslint` clean on all 5 touched files.
  `npx prettier --check` clean on the 4 files documented as pre-story-clean (3 i18n bundles +
  `BookingRequestPage.vue`) — `de-DE/index.js` needed one `--write` pass after hand-editing, now clean;
  `ParentBookingsPage.vue`'s pre-existing (pre-this-story) Prettier violation left untouched per the AC's
  explicit instruction. Hand-traced all 6 new frontend branches against their backend throw sites —
  string equality confirmed for all. No live browser check was performed (no browser-driving tooling in
  this session), per this project's established convention for i18n/UI changes without a test runner.
- AC3: `SubscriptionResourceIT` added as `@WebMvcTest(SubscriptionResource.class)`, importing
  `PlayerSubscriptionOwnershipIT.TestSecurityConfig` via `@Import` (not duplicated), mocking the same 5
  beans (`SubscriptionService`, `CoachProfileRepository`, `SecurityUtil`, `JwtSecretService`,
  `VideoMetrics`) minus `PlayerProfileRepository` (not needed — `PlayerOwnershipGuard` isn't imported
  since `GET /player/me` isn't re-covered here). Covers all 9 previously-uncovered endpoints plus the one
  400 validation case (`POST /player/subscribe` with `{}`). 13/13 green on first run.
- AC4: doc-only comment added above `BookingService.java:762` stating the verified true mechanism (no
  start-time guard exists on `cancelBookingAsParent`, so a post-start-time parent cancellation is a
  normal reachable path, not caller error) rather than the false "wrong event" explanation an earlier
  draft of this AC had proposed. No test required, no behavior change.
- AC5: added `caseAB_boundary_balanceExactlyEqualsSessionPrice_zeroStripeCharge` to `CreditRoutingTest`
  (balance stubbed to exactly `SESSION_PRICE`, asserts zero Stripe call) and
  `processCashOut_amountEqualsBalance_succeeds` to `CashOutServiceTest` (balance stubbed to exactly
  `AMOUNT`, asserts the refund still completes with the correct net amount) — mirroring each file's
  existing structure, omitting the two dead `ArgumentCaptor` declarations the mirrored `CashOutServiceTest`
  method carries (per the AC's explicit instruction not to copy them). 9/9 and 5/5 green respectively. No
  production code touched — both boundaries were already correct.
- AC6: all 5 `[OWNED BY skillars-deferred-28 ACn]` markers replaced (not appended to) with
  `[CLOSED by skillars-deferred-28 ACn]` plus a full prose description of the actual change, matching
  every prior `skillars-deferred-N` closure's convention in this file. Logged 4 new
  `deferred-work.md` items under a new "Deferred from: skillars-deferred-28 ... story creation
  (2026-08-17)" section: the 3 coach-side accept-flow catch sites (same bug class as AC2, different
  page/store), `DrillDetailPanel.vue`'s identical `helpCode` bug, the post-start-time
  parent-cancellation product question (AC4's rework), and the two-independent-refund-rules divergence
  found while verifying AC4. `sprint-status.yaml` updated `ready-for-dev` → `in-progress` at start of
  dev; → `review` at story completion (this update). Story file's own `Status:` field updated the same
  way.
- Full `mvn -o verify` regression suite run before marking the story `review`: exit code 0. Fresh
  surefire (unit) results for this run: 900 tests, 0 failures, 0 errors, 1 skipped. Fresh failsafe (IT)
  results for this run: 919 tests, 0 failures, 0 errors, 4 skipped. `target/surefire-reports/` also
  contains ~100 stale report files predating this run (see Debug Log References above) — filtered out by
  report mtime to get the figures above, matching the exact stale-report-directory trap
  `skillars-deferred-16`/`-19`/`-27` already documented in this ledger; not caused by this story's
  changes.
- **Post-review pass (2026-08-17):** code review appended a "Review Findings" section with 1 Decision + 5
  Patch items (plus 3 already-resolved Defer items requiring no action). Resolved all 6: the Decision
  (`ChronoUnit.HOURS` truncation on AC4's boundary) was doc-only per Mbah's explicit choice among the
  three options offered; the 5 Patches were mechanical (2 `SubscriptionResourceIT` fixes — `jsonPath`
  assertions on 5 happy-path tests, a comment on the 2 transitively-required mock beans — plus a German
  wording alignment, a `CashOutServiceTest` literal-vs-constant fix, and a `deferred-work.md` wording
  tightening). `SubscriptionResourceIT` re-verified 13/13, `CashOutServiceTest` re-verified 5/5. Full `mvn -o verify`
  re-run after all patches: exit code 0, 900 unit + 919 integration tests, 0 failures, 0 errors — no
  regressions from the review-response changes.

### File List

- `src/test/java/com/softropic/skillars/platform/marketplace/repo/CoachMediaItemTest.java` (new, AC1)
- `src/main/resources/i18n/messages.properties` (AC2)
- `src/main/resources/i18n/messages_en.properties` (AC2)
- `src/main/resources/i18n/messages_de.properties` (AC2)
- `src/main/resources/i18n/messages_fr.properties` (AC2)
- `src/frontend/src/i18n/en-US/index.js` (AC2)
- `src/frontend/src/i18n/de-DE/index.js` (AC2)
- `src/frontend/src/i18n/fr-FR/index.js` (AC2)
- `src/frontend/src/pages/parent/BookingRequestPage.vue` (AC2)
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` (AC2)
- `src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceIT.java` (new, AC3)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (AC4, comment only)
- `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java` (AC5)
- `src/test/java/com/softropic/skillars/platform/payment/service/CashOutServiceTest.java` (AC5)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC6)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC6, status line only)
- `_bmad-output/implementation-artifacts/skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test.md` (this story file — Tasks/Subtasks checkboxes, Dev Agent Record, Status)
