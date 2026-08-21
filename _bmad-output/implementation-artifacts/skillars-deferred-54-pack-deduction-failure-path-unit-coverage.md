# Story Deferred-54: Pack Deduction Failure Path Unit Coverage

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `PaymentLifecycleService.handlePackBasedBooking`'s pack-deduction-failure branch (the case where
`PackSessionService.deductSession` throws) to have a unit test in `CreditRoutingTest`, mirroring the
already-covered success path and the already-covered batch-path equivalent of this same failure,
so that a future regression in the single-booking pack-decline path is caught at the unit level instead
of relying solely on IT-level coverage of a different call path.

### Why this story exists

This story ships a single item alone, breaking this project's usual "no small stories" bundling rule.
`_bmad-output/implementation-artifacts/deferred-work.md` was re-mined end to end (twice, across two
passes) looking for groupable, decision-light items to bundle with this one. Both passes confirmed the
ledger is dry right now: the recently-active section (post-`skillars-deferred-40`) is thin per
`skillars-deferred-53`'s own creation notes, and a fresh re-mine of older, pre-2026-08-04 sections turned
up four superficially-plausible candidates that were all checked against live code and found stale or
already fixed (a moot migration-status check against a table `V89` already dropped, an AOP lock/transaction
ordering concern already resolved by `AsyncConfig.java:34`'s `@EnableSchedulerLock(order = ...)`, a
missing-`@Transactional` concern that isn't real because Spring Data's `findById()` carries its own
read-only transaction regardless of caller, and an `AdminVideoService` item already fixed by
`skillars-deferred-52` AC2 but never tagged closed on its original ledger line). The user was explicitly
asked how to proceed given the dry ledger and chose: ship this one real, mechanical, currently-uncovered
gap as its own story rather than skip the cycle or resolve a design-decision item instead.

- **D21 (this story's AC1) — `PackSessionService.deductSession()`'s failure path, reached via
  `PaymentLifecycleService.handlePackBasedBooking` (the single-booking flow), has zero unit-level test
  coverage.** Sourced from `deferred-work.md` line 1119, section `### Group 6 adversarial deferred (Tests)
  — 2026-06-24`, item **D21**: *"Pack deduction failure path (`PackSessionService.deductSession()` throws)
  entirely untested at unit level — requires new mock infrastructure for `persistenceService` in
  `CreditRoutingTest` (or a dedicated `PackBasedBookingDeclineTest`); deferred to Story 7.3."* Re-verified
  live: `PaymentLifecycleService.java:162-175` (`handlePackBasedBooking`) still catches
  `PaymentGatewayException` from `packSessionService.deductSession(purchaseId)` and calls
  `persistenceService.persistPaymentFailure(...)` — the exact behavior D21 says is untested. The
  "requires new mock infrastructure" blocker D21 cited **no longer applies**: `CreditRoutingTest.java:50`
  already declares `@Mock BookingPaymentPersistenceService persistenceService`, wired via `@InjectMocks`
  into the `service` field (added by a later, unrelated story). This is now a small, mechanical addition of
  one test method to an existing test class — not new infrastructure, and not a design decision.
  `CreditRoutingTest.java:147-161`'s `packBasedBooking_noStripeNoCreditConsulted` already covers the
  pack-based *success* path; there is no sibling failure-path test. The batch-path equivalent of this exact
  failure is already covered by `BatchAcceptPaymentIT.acceptAll_packDeductionFails_bookingReachesDeclined`
  (`BatchAcceptPaymentIT.java:116-126`) — this story closes the single-booking-path gap to parity with the
  batch path, at the unit level (matching how the existing success-path sibling test is unit-level, not IT).

**Deliberately not picked up in this pass** (found while re-mining but out of this story's scope):
- Items requiring a design/product decision this kind of single-item or bundled story should not make ad
  hoc: `1598`/`1599` (async-recalculation race widening, migration-batching), `1603` (`DisputeService`'s
  `FROZEN`-status filter gap), `1634`/`1640` (reschedule/duplicate-next-week locking-strategy questions,
  already declined by `skillars-deferred-49`/`-50` for the same reason).
- Standing accepted gaps/tradeoffs, unchanged since prior stories reasoned about them: `1611` (no frontend
  test infrastructure), `1620` (`playerStore.js` dedup-cache design decision), `1630` (validation-logic
  duplication, matches this project's own accepted anti-abstraction convention), `1635`/`1636` (DST-shift
  and cross-midnight-window quirks, both pre-existing and out-of-scope per their own ledger text).
- `D20` (`CashOutServiceTest`/`CashOutService` field-mismatch concern, `deferred-work.md:1118`) — checked
  against live code: neither `lastPaymentIntentId` nor `stripePaymentMethodId` exist under those names in
  the current `CashOutService`/`CashOutServiceTest`, confirmed stale by zero grep hits; would need fresh
  investigation to even re-locate its subject, not a mechanical fix. Left un-annotated, out of scope.
- Four items checked and found already-resolved/moot during this pass's re-mine of older sections (not
  independently worth filing as new ledger entries, since none names a real, still-open defect): a
  migration-status check against a table (`booking.session_packs_purchased`) `V89__drop_legacy_session_packs.sql`
  already dropped; an `@SchedulerLock`/`@Transactional` AOP-ordering concern already resolved by
  `AsyncConfig.java:34`; a missing-`@Transactional` concern on `BookingBatchStatusListener.onBookingStatusChanged`
  that isn't real (Spring Data's `findById()` carries its own transaction); and the old `AdminVideoService`
  `Def17` entry, already fixed by `skillars-deferred-52` AC2 but never tagged closed on its original line.

## Acceptance Criteria

1. **AC1 — `CreditRoutingTest` gains a unit test proving `handlePackBasedBooking`'s deduction-failure
   branch calls `persistPaymentFailure`, not `persistPaymentSuccess`.**
   - File: `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java`.
   - Add a new test method, sibling to `packBasedBooking_noStripeNoCreditConsulted` (`:147-161`) and
     modeled on `stripeDecline_chargesCaptureFails_callsPersistFailureWithZeroReversal` (`:163-181`, the
     existing credit-path failure test this mirrors). **`deductSession(UUID)` returns `void`**
     (`PackSessionService.java:52`), so stub its exception with `doThrow(...).when(...)`, not
     `when(...).thenThrow(...)` — the latter does not compile against a void method:
     ```java
     @Test
     void packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal() {
         UUID packId = UUID.randomUUID();
         doThrow(new PaymentGatewayException("payment.packExhausted"))
             .when(packSessionService).deductSession(packId);

         service.onBookingAccepted(event(packId));

         verify(creditWalletService, never()).getBalance(any());
         verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any());
         verify(persistenceService, never()).persistPaymentSuccess(
             any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
         verify(persistenceService).persistPaymentFailure(
             eq(BOOKING_ID), eq(BigDecimal.ZERO),
             eq(PARENT_ID), anyString(), anyString(), any(Instant.class), anyString());
     }
     ```
     Add the static import `import static org.mockito.Mockito.doThrow;` (not currently imported in this
     file — confirmed by reading the existing import block, `:27-35`). `PaymentGatewayException` is already
     imported (`:7`, used by the existing `stripeDecline_...` test).
   - **Why `creditToReverse` is `BigDecimal.ZERO`, matching the existing credit-path failure test's own
     assertion**: `handlePackBasedBooking` (`PaymentLifecycleService.java:162-175`) always calls
     `persistenceService.persistPaymentFailure(bookingId, BigDecimal.ZERO, ...)` on the catch branch — no
     credit was ever debited on the pack-based path (credit and packs are mutually exclusive routing
     branches in `onBookingAccepted`, `:152-159`), so there is nothing to reverse.
   - **Why this actually closes the gap** the ledger item describes: before this test, a regression that
     made `handlePackBasedBooking`'s catch branch silently swallow the exception, call
     `persistPaymentSuccess` instead of `persistPaymentFailure`, or drop the catch entirely (causing an
     unhandled `PaymentGatewayException` to propagate) would have no unit-level test to catch it — only
     `BatchAcceptPaymentIT`'s *batch*-path equivalent exercises this failure shape today, and that IT
     drives a materially different code path (`BookingBatchService`/`acceptAll`, not
     `PaymentLifecycleService.onBookingAccepted`).
   - **Test coverage**: this AC's change *is* the test coverage — no production code changes. Run
     `mvn -o test -Dtest=CreditRoutingTest` and confirm all tests green (existing tests plus the new one).

2. **AC2 — Ledger hygiene.** This project's established convention (confirmed against the "Create Story"
   commits for deferred-38 through -53) is: at **story-creation** time, tag an item this story is about to
   fix as `` `[PICKED UP by skillars-deferred-54 AC1]` `` — appended after the item's existing text/citation,
   without rewriting the body to describe a fix that hasn't happened yet. This AC's job during
   **implementation** is to flip that tag to `` `[CLOSED by skillars-deferred-54 AC1]` `` with a one-line
   closure note describing the actual fix, once AC1 actually lands — keeping the original text below it.
   Already applied correctly at this story's creation: `deferred-work.md` line 1119 (D21) tagged
   `` `[PICKED UP by skillars-deferred-54 AC1]` ``.

## Tasks / Subtasks

- [ ] Task 1: Pack-deduction-failure unit test (AC: #1)
  - [ ] 1.1 Add the `import static org.mockito.Mockito.doThrow;` static import to `CreditRoutingTest.java`.
  - [ ] 1.2 Add `packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal` per AC1's snippet,
    placed immediately after `packBasedBooking_noStripeNoCreditConsulted` (`:147-161`).
  - [ ] 1.3 Run `mvn -o test -Dtest=CreditRoutingTest` and confirm all tests green (existing tests plus the
    new one). This is a plain Surefire unit test class (no `IT` suffix) — `mvn -o test`, not
    `mvn -o integration-test`, is the correct command.
- [ ] Task 2: Ledger hygiene (AC: #2) — flip `deferred-work.md` line 1119's `[PICKED UP by
  skillars-deferred-54 AC1]` tag to `[CLOSED by skillars-deferred-54 AC1]` with a one-line closure note,
  once AC1 ships.

## Dev Notes

- **This is a single-item, test-only story — no production code changes.** `PaymentLifecycleService`'s
  `handlePackBasedBooking` catch branch already does the right thing (confirmed by direct read,
  `:162-175`); this story only adds the missing regression test proving it.
- **`deductSession(UUID)` returns `void`.** Stub its exception with `doThrow(...).when(packSessionService).deductSession(packId)`,
  not `when(...).thenThrow(...)` — the latter does not compile against a void method. This is the one
  implementation pitfall this story exists to flag; get it right on the first attempt.
- **Reuse the existing `event(UUID packPurchaseId)` helper** (`CreditRoutingTest.java:63-67`) and the class
  constants `BOOKING_ID`/`PARENT_ID` already used by every other test in this file — do not introduce new
  fixtures.
- **`persistenceService` is a strict Mockito mock** (`@Mock BookingPaymentPersistenceService
  persistenceService`, `MockitoExtension`) — the new test's `never()` assertions on `persistPaymentSuccess`
  are necessary, not decorative: without them, a regression that calls both `persistPaymentFailure` *and*
  `persistPaymentSuccess` would still pass the `verify(persistenceService).persistPaymentFailure(...)`
  assertion alone.
- **Do not add a second, IT-level test for this.** `BatchAcceptPaymentIT.acceptAll_packDeductionFails_bookingReachesDeclined`
  already exists at the IT level for the *batch* path; the single-booking path (`onBookingAccepted`) is
  reached by real HTTP acceptance flows exercised elsewhere in the IT suite, and adding IT coverage
  specifically for this exception branch is out of this story's scope (D21 itself only asks for unit-level
  coverage).
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify` unless
  targeted verification proves insufficient.
- **No frontend changes in this story.**

### Project Structure Notes

- `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java` — one new test
  method + one new static import (AC1). No new file.
- `_bmad-output/implementation-artifacts/deferred-work.md` — one annotation (AC2).
- No changes to `PaymentLifecycleService.java`, `PackSessionService.java`, or any other production file.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1119, section `### Group 6
  adversarial deferred (Tests) — 2026-06-24`, item D21 — this story's AC1 source]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:136-175`
  — `onBookingAccepted`/`handlePackBasedBooking`, AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:52-61`
  — `deductSession`, confirming its `void` return type and `PaymentGatewayException` throw sites]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/service/CreditRoutingTest.java:1-195`
  — existing test class, mock infrastructure, and the `packBasedBooking_noStripeNoCreditConsulted`/
  `stripeDecline_chargesCaptureFails_callsPersistFailureWithZeroReversal` patterns AC1's new test mirrors]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BatchAcceptPaymentIT.java:116-126`
  — `acceptAll_packDeductionFails_bookingReachesDeclined`, the batch-path equivalent this story brings the
  single-booking path to parity with]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, shipping a single item alone rather than bundled, per explicit user decision after the ledger was confirmed dry of groupable items across two re-mining passes. D21 (`deferred-work.md:1119`) re-verified against live code: `PaymentLifecycleService.java:162-175`'s catch branch still calls `persistPaymentFailure` on `deductSession` failure with no unit test covering it; the "requires new mock infrastructure" blocker D21 originally cited no longer applies, since `CreditRoutingTest.java:50` already has `persistenceService` mocked and `@InjectMocks`-wired (added by unrelated later work). AC1 adds one new unit test mirroring the existing credit-path failure test's assertion shape. AC2 ledger hygiene. |
