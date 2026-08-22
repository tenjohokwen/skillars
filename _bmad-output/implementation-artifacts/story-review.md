# Story Review: Deferred-54 (Pack Deduction Failure Path Unit Coverage)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-54-pack-deduction-failure-path-unit-coverage.md`
Method: every factual claim (line numbers, method signatures, control flow, existing test behavior) was
re-verified against the live files it cites — `PaymentLifecycleService.java`, `PackSessionService.java`,
`CreditRoutingTest.java`, `BookingPaymentPersistenceService.java`, `BatchAcceptPaymentIT.java`, and
`deferred-work.md` — not taken on the story's word.

## Verdict

No defects found. The story's technical claims all check out, the proposed test snippet compiles and
correctly exercises the branch it targets, and the AC2 ledger-hygiene state matches what's actually in
`deferred-work.md`. One minor completeness suggestion below (non-blocking).

## Claims verified accurate

- `PaymentLifecycleService.java:162-175` (`handlePackBasedBooking`) catches exactly `PaymentGatewayException`
  from `packSessionService.deductSession(purchaseId)` and calls `persistenceService.persistPaymentFailure(...)`
  with `BigDecimal.ZERO` as the reversal amount, then `return`s — matches the story's description and the
  proposed test's assertions line for line.
- `PackSessionService.deductSession(UUID)` (`PackSessionService.java:51-61`) is `void` and throws
  `PaymentGatewayException` from two sites (`payment.packNotFound`, `payment.packExhausted`) — the story's
  `doThrow(...).when(...)` guidance is correct; `when(...).thenThrow(...)` would not compile here.
- `CreditRoutingTest.java` import block (`:1-35`) has no `org.mockito.Mockito.doThrow` static import today —
  the story correctly flags this as a required addition.
- `persistPaymentFailure`'s real signature (`BookingPaymentPersistenceService.java:207-209`) is
  `(UUID bookingId, BigDecimal creditToReverse, Long parentId, String parentEmail, String coachDisplayName,
  Instant requestedStartTime, String canonicalTimezone)` — exactly 7 params, matching the proposed test's
  7-matcher `verify(...)` call arg-for-arg (type and position).
- `persistPaymentSuccess`'s real signature (`BookingPaymentPersistenceService.java:178-181`) has 10 params —
  matching the proposed `never()` verification's 10 `any()` matchers, and consistent with how every other
  test in the file already verifies it (e.g. `duplicateEvent_idempotencyNoOp`).
- Placement instruction ("immediately after `packBasedBooking_noStripeNoCreditConsulted`") is accurate:
  that test spans `:147-161` in the live file, and `stripeDecline_chargesCaptureFails_callsPersistFailureWithZeroReversal`
  (the test AC1 says it mirrors) spans `:163-181` — both citations are exact.
- `@Mock BookingPaymentPersistenceService persistenceService` is present at `CreditRoutingTest.java:50` and
  wired via `@InjectMocks` — the "no new mock infrastructure needed" claim, which is what makes D21's
  original "requires new mock infrastructure" blocker stale, is correct.
- No naming collision: no method named `packBasedBooking_deductSessionFails_callsPersistFailureWithZeroReversal`
  (or similar) already exists in `CreditRoutingTest.java`.
- `deferred-work.md:1119` (D21) is tagged `` `[PICKED UP by skillars-deferred-54 AC1]` `` exactly as the story
  states, confirming AC2's starting state is accurate.
- The "deliberately not picked up" D20 claim — that neither `lastPaymentIntentId` nor `stripePaymentMethodId`
  appear anywhere in `CashOutService.java`/`CashOutServiceTest.java` — was independently re-grepped and
  confirmed: zero hits for both names in either file.
- `BatchAcceptPaymentIT.acceptAll_packDeductionFails_bookingReachesDeclined` (`:116-126`) exists exactly as
  cited and does exercise a pack-deduction failure at the batch level.
- Trace through `onBookingAccepted` with the proposed test's setup confirms the exception path is reached
  as described: `isSettled`/`hasReservation` both read the `lenient()`-stubbed empty `Optional` from
  `setUp()`, `event.getSessionPackPurchaseId() != null` routes into `handlePackBasedBooking`, and the mock's
  `doThrow` fires exactly where the catch block expects it. No stubbing-strictness (`MockitoExtension`
  `STRICT_STUBS`) issue: the new test's own stub is consumed by the call it's meant to trigger.

## Non-blocking observation (not a defect, not requested as a fix)

**The "batch-path equivalent" is not byte-for-byte the same failure-handling code, even though the story
never claims it is.** The batch path's pack loop (`PaymentLifecycleService.java:247-267`) catches the
*broader* `Exception` (not just `PaymentGatewayException`) and calls `persistenceService.declineBatchBooking(...)`,
not `persistPaymentFailure(...)`. The story's language — "batch-path equivalent of this exact failure is
already covered... this story closes the single-booking-path gap to parity with the batch path, at the unit
level" — is accurate as written (parity of *coverage*, not parity of *mechanism*), but a future reader
skimming quickly could misread "parity with the batch path" as implying the two code paths converge on the
same persistence call. They don't. No change requested — the story's actual AC1 text and test snippet
correctly target `persistPaymentFailure`, matching the real single-booking code path.

## Minor completeness suggestion (optional, non-blocking)

The proposed test omits `verify(packSessionService).deductSession(packId);`, even though its direct sibling
success test (`packBasedBooking_noStripeNoCreditConsulted`) includes that same verification. This isn't a
correctness gap — the stubbed `doThrow` firing is itself proof the mock was invoked, and the mirrored
`stripeDecline_...` failure test the story cites as AC1's template also omits verifying its own triggering
call (`chargeAndCapture`) for the same reason — so omitting it is actually *consistent* with the pattern
being mirrored. Flagging only as an optional add for symmetry with the pack success test specifically, not
as something AC1 needs to change.

## Scope check

Confirmed this story makes no production code claims that don't hold: `handlePackBasedBooking`'s catch
branch was re-read directly and does exactly what AC1's Dev Notes assert, with no other exception type or
branch in that method needing coverage. The single try/catch in `handlePackBasedBooking` is the entire
failure surface D21 describes — there is no partial-success or secondary-failure state within this method
that the proposed single test would miss.
