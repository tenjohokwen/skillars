# Story Deferred-53: Concurrency Race-Test Determinism & Hardcoded Currency Configuration

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `BookingServiceConcurrencyIT`'s two coach-suspension race tests to synchronize on an actual
observed lock wait instead of a fixed `Thread.sleep(1500)` guess, and `StripePaymentGateway.chargeAndCapture`
to read its Stripe currency from `ConfigService` instead of a hardcoded literal (mirroring the
`platform.commission.rate` pattern one line above it),
so that the two race tests reliably prove the locked-re-read behavior they claim to prove regardless of
machine load, and the platform's payment currency is a configuration value instead of a code constant.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1641 lines at the time this story was created)
was re-mined end to end. The most recently active area of the ledger (`skillars-deferred-40` onward) was
confirmed thin again: of the untagged items in that range, `1598`/`1599` need a design decision this kind
of bundled story should not make ad hoc (async-recalculation race widening, migration-batching), `1603`
(`DisputeService`'s `CAPTURED`-only filter) explicitly says a bundled small-fix story shouldn't touch it
without a coordinated cross-service design decision, `1611`/`1620`/`1630`/`1634`/`1635`/`1636`/`1640` are
either standing accepted frontend-test-infrastructure gaps, already-reasoned accepted tradeoffs, or need a
locking-strategy/product decision (full detail in "Deliberately not picked up" below), and `1641` alone is
too small to justify a story. Two source items were instead re-mined from older, previously-unpicked
sections of the ledger (2026-06-24 and 2026-08-04) and both re-verified against live code at creation
time, not trusted from ledger text — full detail in each AC below. A third, closely-related defect (the
`acceptBooking` sibling of AC1's race test) was found independently while verifying AC1 and is filed fresh
below.

- **D1 (this story's AC1) — `BookingServiceConcurrencyIT#createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`
  stages its race with a fixed `Thread.sleep(1500)` instead of an observed synchronization point.**
  Sourced from `deferred-work.md`, section `## Deferred from: code review of skillars-deferred-12-booking-payment-review-integrity
  (2026-08-04)`, item **D1**: *"orders the two threads with `Thread.sleep(1500)` rather than a real
  barrier. If the booking thread is slow to reach its unlocked read, the suspender commits first, the
  unlocked check rejects with the same `COACH_UNAVAILABLE`, and the test passes green even with the
  `entityManager.refresh(...)` line deleted. ... Assert on something only the locked re-read can
  produce."* Re-verified live at `BookingServiceConcurrencyIT.java:262`: the `Thread.sleep(1500)` is still
  there, unchanged since the item was filed.
- **D2 (this story's AC2) — the sibling test `acceptBooking_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`
  has the identical `Thread.sleep(1500)` staging, not previously tracked under this name.** Found
  independently while verifying D1 above: this second test (`BookingServiceConcurrencyIT.java:319-378`)
  stages the exact same coach-suspension race with the exact same `Thread.sleep(1500)` pattern
  (`:339`) and the exact same weakness — if the accepting thread is slow to reach its unlocked read, the
  test can pass without ever exercising the locked re-read the `entityManager.refresh(lockedCoach,
  PESSIMISTIC_WRITE)` line (cited in this test's own class-level Javadoc) actually guards. Same bug class,
  same file, a second method the original ledger item never named.
- **D3 (this story's AC3) — `StripePaymentGateway.chargeAndCapture` hardcodes the Stripe currency to
  `"eur"` instead of reading it from `ConfigService`, unlike the commission rate one line above it.**
  Sourced from `deferred-work.md`, section `### Group 2 deferred (Services) — 2026-06-24` (nested under
  `## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)`),
  item **D9**: *"EUR currency hardcoded in `chargeAndCapture` — single-currency now; make configurable
  later."* Re-verified live at `StripePaymentGateway.java:48`: `.setCurrency("eur")` is still a string
  literal; `configService` is already a constructor-injected field on this class, used one line above
  (`:42`) for `platform.commission.rate`, and this is confirmed the only `.setCurrency(...)` call site in
  the file (`grep -c "setCurrency" StripePaymentGateway.java` → 1).

**Deliberately not picked up in this pass** (found while re-mining but out of this story's scope):
- Items `1598`/`1599`/`1603` (development-module async-recalculation race widening, `V98` unchunked
  backfill, `DisputeService`'s `FROZEN`-status filter gap) — all three explicitly say in their own ledger
  text that fixing them needs a design decision spanning multiple services or files, not a mechanical
  three-line change a bundled small-fix story should make ad hoc.
- Item `1620` (`playerStore.js`'s `resetSelfPlayerId()` not clearing the in-flight request-dedup cache) —
  its own text says "needs a design decision... before a fix is unambiguous."
- Item `1634` (`RescheduleService.acceptReschedule`'s unlocked availability-window read) — its own text
  says "a proper fix touches `CoachProfileService`'s locking strategy, out of this story's scope," the
  same class of locking-strategy decision `skillars-deferred-49`/`-50` already declined for a sibling item.
- Item `1630` (validation-logic duplicated across 3 booking call sites) — explicitly matches this
  project's own established anti-abstraction convention (the same DRY nit `skillars-deferred-48`'s code
  review already dismissed against 3 near-identical guard blocks); not fixed here for the same reason.
- Item `1635` (`duplicateNextWeek`'s DST-shift-of-duplicated-time quirk) and `1636`
  (`isSlotWithinAvailabilityWindow`'s cross-midnight-window limitation) — both pre-existing, both already
  explicitly out-of-scope per their own ledger text (no proposed fix / "out of scope to fix... Dev Notes
  explicitly direct reusing this helper as-is").
- Item `1640` (`duplicateNextWeek`'s overlap-check TOCTOU race with `save()`) — explicitly acknowledged
  and scoped out by the story that introduced it ("no new coach-row locking... same TOCTOU race the
  unlocked read already had"); needs the same locking-strategy decision as `1634`.
- Item `1611` (no frontend test coverage for `playerStore.js`'s `fetchSelfPlayerId`/`resetSelfPlayerId`) —
  matches this project's standing, repeatedly-accepted absence of frontend test infrastructure (the same
  reasoning `skillars-deferred-35`/`36`/`37`/`38` already left in place elsewhere); would require adding
  test tooling, not a mechanical fix.
- Item `1641` (a unit test that doesn't verify `findOverlappingBookings`'s call arguments) — real but
  explicitly "optional polish, not required by AC1's spec," and alone too small to justify a story.
- Ledger item `1211` (`skillars-deferred-16`'s D6, `SoftDeleteIT`'s concurrency-test synchronization
  concern) was **checked against live code and found already fixed**, unannotated: the current
  `SoftDeleteIT.java:246-283` already drives two real concurrent HTTP calls gated on a shared
  `CountDownLatch` and asserts on independently-observed `successCount`/`conflictCount`
  (`skillars-deferred-51`'s own creation notes record the same finding). Left stale in the ledger, not
  picked up here, not re-annotated (out of this story's scope to touch unrelated ledger housekeeping).
- Ledger items `1110` (`getParentBookings`'s `effectiveCredits` clamp) and `1118` (`CashOutServiceTest`'s
  `lastPaymentIntentId`/`stripePaymentMethodId` field-mismatch concern) were checked against live code:
  neither `effectiveCredits` nor the named fields exist under those names anymore (zero grep hits in
  `BookingService.java`/`CashOutService.java`/`CashOutServiceTest.java`) — both items are stale from
  refactors since 2026-06-24 and would need fresh investigation to even re-locate their subject, not a
  three-line fix. Not picked up; not re-annotated (same reasoning as the `SoftDeleteIT` item above).

## Acceptance Criteria

1. **AC1 — `BookingServiceConcurrencyIT#createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`
   synchronizes on an observed lock wait instead of a fixed sleep.**
   - File: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:239-302`.
   - Current shape (the suspender thread holds the `coach_profiles` row lock for a fixed 1500ms, hoping
     the booking thread reaches its own blocking `findByIdForUpdate` call within that window):
     ```java
     suspensionStagedAndLockHeld.countDown();
     // Hold the lock long enough for the booking thread to pass its unlocked read
     // and block on findByIdForUpdate; the repository's lock timeout is 5s.
     try {
         Thread.sleep(1500);
     } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
     }
     ```
   - Add a private helper to the test class that polls Postgres's own bookkeeping for a second backend
     genuinely blocked on a lock against `coach_profiles`, instead of guessing a fixed delay:
     ```java
     /**
      * Polls pg_stat_activity until another backend is observed blocked (wait_event_type = 'Lock') on a
      * query touching marketplace.coach_profiles, or fails the test if that never happens within the
      * timeout. Deterministic replacement for a fixed-duration sleep guess — pg_stat_activity is a
      * system view reflecting all backends instance-wide, so polling it from inside this thread's own
      * open transaction (via the same jdbcTemplate bean already used for the staging UPDATE two lines
      * above) is safe: it is not subject to this transaction's MVCC row-data snapshot.
      */
     private void awaitAnotherSessionBlockedOnCoachProfileLock(Duration timeout) throws InterruptedException {
         Instant deadline = Instant.now().plus(timeout);
         while (Instant.now().isBefore(deadline)) {
             Integer blockedCount = jdbcTemplate.queryForObject(
                 "SELECT count(*) FROM pg_stat_activity " +
                 "WHERE pid != pg_backend_pid() AND wait_event_type = 'Lock' AND query ILIKE '%coach_profiles%'",
                 Integer.class);
             if (blockedCount != null && blockedCount > 0) {
                 return;
             }
             Thread.sleep(50);
         }
         throw new AssertionError("No other session was observed blocked on the coach_profiles row lock "
             + "within " + timeout + " — this test's staging assumption failed, results below are not "
             + "trustworthy.");
     }
     ```
     Add `import java.time.Duration;` (not currently imported in this file). Then replace the
     `Thread.sleep(1500)` call above with `awaitAnotherSessionBlockedOnCoachProfileLock(Duration.ofSeconds(10));`,
     keeping the existing `catch (InterruptedException e) { Thread.currentThread().interrupt(); }` around
     it (the helper itself declares `throws InterruptedException`, same as the raw `Thread.sleep` call it
     replaces).
   - **Why this actually closes the gap** the ledger item describes: the old test could pass even with the
     production `entityManager.refresh(...)`-equivalent locked-re-read logic silently deleted, as long as
     the booking thread happened to be slow enough that the *unlocked* pre-check alone produced the same
     `COACH_UNAVAILABLE` outcome. The new helper only lets the suspender release its lock once a second
     backend is *actually* confirmed parked waiting on it — guaranteeing the booking thread has already
     passed its unlocked read and reached the locked re-read before the suspension commits, on every run,
     regardless of machine load. `10s` is a generous ceiling matching this file's other `.get(30,
     TimeUnit.SECONDS)`/`.await(10, TimeUnit.SECONDS)` timeouts elsewhere in the same class.
   - Related context, not something this AC needs to fix: `deferred-work.md`'s separately-tracked finding
     that `jakarta.persistence.lock.timeout` has no effect on this project's Hibernate/Postgres
     combination explains why the booking thread's `findByIdForUpdate` call genuinely blocks for the
     suspender's full hold duration rather than erroring out after 5 seconds — this is exactly why a
     precise, non-sleep-based release point matters here.
   - **Test coverage**: this AC's change *is* the test hardening — no separate new test needed. Confirm the
     hardened test still passes deterministically: run it standalone several times in a row (this file has
     no `@RepeatedTest` convention to reuse; a manual repeated `mvn -o test` invocation is sufficient, no
     new annotation needed) to build confidence the flakiness class is actually closed, not just
     relocated.

2. **AC2 — `BookingServiceConcurrencyIT#acceptBooking_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`
   gets the identical fix.**
   - File: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:319-378`.
   - Same current shape at `:339` (`Thread.sleep(1500)` inside the suspender's held transaction), same
     fix: call the same `awaitAnotherSessionBlockedOnCoachProfileLock(Duration.ofSeconds(10))` helper AC1
     adds (do not duplicate the helper — both tests share the one private method, same class).
   - Same rationale as AC1: without this fix, the test can pass even if the
     `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` line this test's own class-level Javadoc cites
     as load-bearing were deleted, as long as the accepting thread is slow enough that the *unlocked* check
     alone produces the same rejection.
   - **Test coverage**: same as AC1 — the fix itself is the hardening; no new test method needed.

3. **AC3 — `StripePaymentGateway.chargeAndCapture` reads the Stripe currency from `ConfigService` instead
   of a hardcoded `"eur"` literal.**
   - File: `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:40-49`.
   - New migration: `src/main/resources/db/migration/V99__payment_currency_config.sql`. The highest
     `main.platform_config` id used by any existing migration is `603` (`V93__session_duration.sql`) — use
     `604`:
     ```sql
     INSERT INTO main.platform_config (id, key, value, value_type, description, updated_at)
     VALUES (604, 'platform.payment.currency', 'eur', 'STRING',
             'ISO 4217 currency code (lowercase, as Stripe''s API expects) used for all Stripe charges. '
             || 'Single-currency platform today; extracted from a hardcoded literal so a future '
             || 'multi-currency change does not require a code deploy for this value alone.', NOW());
     ```
     Match the exact column list/style of the most recent prior seed (`V93__session_duration.sql`'s
     `(id, key, value, value_type, description, updated_at)` with `NOW()`), not the older no-`updated_at`
     style some earlier migrations use.
   - Current shape:
     ```java
     PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
         .setAmount(amountCents)
         .setCurrency("eur")
     ```
   - Change to (mirroring the `commissionRate` line immediately above it — same `configService.getString(...)`
     call shape, no new field, no new import):
     ```java
     PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
         .setAmount(amountCents)
         .setCurrency(configService.getString("platform.payment.currency"))
     ```
   - No new constructor field: `configService` is already injected (`StripePaymentGateway.java:36`),
     already used one line above for `platform.commission.rate`.
   - **Test coverage**: `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java`
     already has a `stubCoachAndCommission()` helper (`:53-59`) used by every `chargeAndCapture` test,
     which already stubs `configService.getString("platform.commission.rate")`. Add a sibling stub call
     `when(configService.getString("platform.payment.currency")).thenReturn("eur");` inside that same
     helper (not a separate one — every existing `chargeAndCapture` test already calls
     `stubCoachAndCommission()`, and `configService` is a strict Mockito mock via `MockitoExtension`, so an
     un-stubbed call would throw, not silently return null — every existing test needs this stub added,
     not just a new one). Then add one new test:
     `chargeAndCapture_passesConfiguredCurrencyToStripe` — stub the coach/commission/customer fixtures as
     the existing tests do, invoke `chargeAndCapture`, capture the `PaymentIntentCreateParams` argument
     passed to `stripeClient.createPaymentIntent(...)` via `ArgumentCaptor<PaymentIntentCreateParams>`
     (mirroring `chargeAndCapture_passesIdempotencyKeyDerivedFromReferenceIdAndParentId`'s existing
     `ArgumentCaptor` pattern one test above), and assert `paramsCaptor.getValue().getCurrency()`
     equals `"eur"`. Do not assert against a literal in the production code path — assert against the
     stubbed config value, so the test actually proves the value is read from config, not merely that "eur"
     appears somewhere.

4. **AC4 — Ledger hygiene.** This project's established convention (confirmed against the "Create Story"
   commits for deferred-38, -40, -41, -42, -45, -48, -49, -50, -51, -52) is: at **story-creation** time,
   tag an item this story is about to fix as `` `[PICKED UP by skillars-deferred-53 ACn]` `` — appended
   after the item's existing text/citation, without rewriting the body to describe a fix that hasn't
   happened yet. `` `[CLOSED by ...]` `` is reserved for items **verified already fixed by separate,
   completed work**. Only flip a `PICKED UP` tag to `CLOSED` in the **implementation** commit, once the
   corresponding code change actually lands — never at story-creation time. This was already applied
   correctly at this story's creation:
   - `deferred-work.md` line 1193 (D1, the `createBookingRequest` race test) tagged
     `` `[PICKED UP by skillars-deferred-53 AC1]` ``.
   - A new entry filed under a new `## Deferred from: skillars-deferred-53 story creation` section for the
     independently-found `acceptBooking` sibling (D2 above), tagged `` `[PICKED UP by skillars-deferred-53
     AC2]` `` from the moment it was filed (found and picked up in the same pass, matching how
     `skillars-deferred-52` handled its own independently-found `AdminVideoService.deleteVideo()`
     duplicate).
   - `deferred-work.md` line 1107 (D9, EUR hardcoding) tagged `` `[PICKED UP by skillars-deferred-53 AC3]` ``.
   This AC's job during **implementation** is to flip those three `PICKED UP` tags to `CLOSED` once AC1/AC2/AC3
   actually land — one commit, matching the code:
   - Once AC1 ships: flip D1's tag to `` `[CLOSED by skillars-deferred-53 AC1]` `` with a one-line closure
     note describing the actual fix, keeping the original text below it.
   - Once AC2 ships: flip the new `acceptBooking` sibling entry's tag to `` `[CLOSED by skillars-deferred-53
     AC2]` `` the same way.
   - Once AC3 ships: flip D9's tag to `` `[CLOSED by skillars-deferred-53 AC3]` `` the same way.
   - **If a partial implementation lands**, flip only the tags for the ACs that actually shipped — leave
     the rest at `PICKED UP`. The ledger must never claim a still-unfixed item is `CLOSED`.

## Tasks / Subtasks

- [ ] Task 1: `createBookingRequest` race-test determinism (AC: #1)
  - [ ] 1.1 Add the `awaitAnotherSessionBlockedOnCoachProfileLock(Duration)` private helper to
    `BookingServiceConcurrencyIT`, per AC1's snippet. Add the missing `java.time.Duration` import.
  - [ ] 1.2 Replace `createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`'s
    `Thread.sleep(1500)` call with the new helper.
  - [ ] 1.3 Run `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT` and confirm green. Run the
    single test method standalone 3-5 times in a row to build confidence in the fix.
- [ ] Task 2: `acceptBooking` race-test determinism (AC: #2)
  - [ ] 2.1 Replace `acceptBooking_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`'s
    `Thread.sleep(1500)` call with the same shared helper from Task 1.1 — no second helper method.
  - [ ] 2.2 Run `mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT` and confirm all tests in
    the class still green (both race tests plus the file's other, unrelated concurrency tests).
- [ ] Task 3: Currency configuration (AC: #3)
  - [ ] 3.1 Create `V99__payment_currency_config.sql` per AC3's snippet.
  - [ ] 3.2 Change `StripePaymentGateway.chargeAndCapture`'s `.setCurrency("eur")` to
    `.setCurrency(configService.getString("platform.payment.currency"))`.
  - [ ] 3.3 Add the `configService.getString("platform.payment.currency")` stub to
    `StripePaymentGatewayTest`'s `stubCoachAndCommission()` helper; add the new
    `chargeAndCapture_passesConfiguredCurrencyToStripe` test per AC3's description.
  - [ ] 3.4 Run `mvn -o test -Dtest=StripePaymentGatewayTest` and confirm green (all existing tests plus
    the new one). Run `mvn -o integration-test -Dit.test=PaymentWebhookIdempotencyIT` (or whichever
    payment-module IT actually exercises `chargeAndCapture` end to end — verify which one during
    implementation rather than assuming) to confirm no wiring regression from the new config key.
- [ ] Task 4: Ledger hygiene (AC: #4) — apply all annotations described in AC4 to `deferred-work.md`.

## Dev Notes

- **This story bundles two independent, decision-light findings — it is not a single coherent feature.**
  AC1 and AC2 share a helper method and a root cause (same file, same bug class, two methods) and should
  be implemented together for that reason. AC3 is fully independent — a different module, a different bug
  class (config extraction, not test determinism).
- **Reuse existing patterns, do not invent new ones.** AC1/AC2's polling approach uses only `jdbcTemplate`
  (already a field on the test class) and Postgres's own `pg_stat_activity` system view — no new test
  library, no new Maven dependency. AC3 mirrors the `platform.commission.rate` pattern
  (`ConfigService.getString(...)` + a `platform_config` migration row) that already exists one line above
  the change, in the same file, in the same method — do not invent a different configuration mechanism.
- **AC1/AC2's `Duration.ofSeconds(10)` timeout is a ceiling, not an expected wait.** In practice the
  polling loop should return within tens of milliseconds once the booking/accept thread reaches its
  blocking call — the 10s bound only exists to fail the test loudly (via the helper's own `AssertionError`)
  instead of hanging forever if the staging assumption is ever wrong for an unrelated reason (e.g. a
  future refactor changes which query blocks).
- **Do not attempt to fix `jakarta.persistence.lock.timeout` having no effect on this Hibernate/Postgres
  combination as part of this story.** That is a separately-tracked, already-filed ledger item
  (`deferred-work.md:1424`) spanning four `findByIdForUpdate` repositories — out of scope here; this story
  only needs to know *why* the booking thread blocks indefinitely rather than erroring after 5s, not fix
  that it does.
- **`IT`-execution gotcha (recorded by prior stories, still applies):** `*IT` classes run under
  `maven-failsafe-plugin`, bound to `integration-test`/`verify`, **not** `mvn test`. Use
  `mvn -o integration-test -Dit.test=<ClassName>` and confirm a `target/failsafe-reports/...txt` report
  was actually written.
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify`
  unless targeted verification proves insufficient.
- **No frontend changes in this story.** Both AC groups are backend-only (test code + production code +
  one migration).
- **`stubCoachAndCommission()`'s new stub is mandatory for every existing `chargeAndCapture` test in
  `StripePaymentGatewayTest`, not optional.** `configService` is a strict `@Mock` under
  `MockitoExtension` — an un-stubbed `getString("platform.payment.currency")` call throws
  `UnnecessaryStubbingException`/returns `null` (causing a Stripe SDK `IllegalArgumentException` on a null
  currency), it does not silently no-op. Every existing test that calls `stubCoachAndCommission()` will
  break without this change, which is exactly why the stub belongs inside the shared helper rather than
  only the new test.

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java` — new
  private helper method + `Duration` import; both race tests' `Thread.sleep(1500)` calls replaced (AC1,
  AC2).
- `src/main/resources/db/migration/V99__payment_currency_config.sql` — **new file** (AC3).
- `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java` —
  `chargeAndCapture`'s `.setCurrency(...)` call changed; no new fields/imports (AC3).
- `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java` —
  `stubCoachAndCommission()` gains one stub line; one new test method (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — three annotations/additions (AC4).
- No changes to `BookingService.java`, `AcceptBooking`/`CreateBookingRequest` production logic, or any
  frontend file — both AC groups touch only test code, one service method, and one migration.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1193, section `## Deferred from:
  code review of skillars-deferred-12-booking-payment-review-integrity (2026-08-04)`, item D1 — this
  story's AC1 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1107, section `### Group 2
  deferred (Services) — 2026-06-24`, item D9 — this story's AC3 source]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:239-378`
  — both race tests, AC1/AC2's targets]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java:29-34`
  — `findByIdForUpdate`, the `PESSIMISTIC_WRITE` JPQL query both race tests block on]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1424 — the related, separately-tracked
  `jakarta.persistence.lock.timeout`-has-no-effect finding, cited for context in AC1, not fixed here]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:34-48`
  — `chargeAndCapture`, AC3's target, and the `commissionRate` pattern it mirrors]
- [Source: `src/main/resources/db/migration/V93__session_duration.sql` — the most recent prior
  `platform_config` seed, whose column list/style AC3's new migration matches]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/service/StripePaymentGatewayTest.java:34-84`
  — `stubCoachAndCommission()` and the existing `ArgumentCaptor` pattern AC3's new test mirrors]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

_To be filled in during implementation._

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, bundling two items re-mined from previously-unpicked sections of `deferred-work.md` (2026-06-24 and 2026-08-04) after confirming the more recently active section (post-`skillars-deferred-40`) is thin — every untagged item there either needs a design/product decision this kind of bundled small-fix story should not make ad hoc, is a standing accepted frontend-test-infrastructure gap, or is too small alone to justify a story (full detail in "Deliberately not picked up" above). Both source items re-verified against live code at creation time: `BookingServiceConcurrencyIT.java:262`'s `Thread.sleep(1500)` is still present; `StripePaymentGateway.java:48`'s `.setCurrency("eur")` is still a string literal, confirmed the only `setCurrency` call site in the file. One related item (the `acceptBooking` sibling test at `:319-378`, carrying the identical `Thread.sleep(1500)` pattern at `:339`) was found independently while verifying the first and filed fresh as AC2, following the same "found while verifying, filed and picked up in the same pass" precedent `skillars-deferred-52` set for its own independently-found `AdminVideoService.deleteVideo()` duplicate. Two stale ledger items were checked and found to no longer name real code (`effectiveCredits` in `BookingService.java`, `lastPaymentIntentId`/`stripePaymentMethodId` in `CashOutService`/`CashOutServiceTest` — zero grep hits for either) and left un-annotated, out of this story's scope to fix ledger housekeeping unrelated to its own ACs. One item (`skillars-deferred-16`'s D6, `SoftDeleteIT`'s concurrency-test synchronization concern) was confirmed already fixed by separate, unannotated prior work — same finding `skillars-deferred-51`'s own creation notes already recorded — and was not re-picked-up or re-annotated here. |
