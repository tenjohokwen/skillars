# Story Review: Deferred-68 Booking-Module Concurrency-Conflict Error Handling Parity

**Date:** 2026-08-25  
**Reviewer:** Senior Dev Code Audit  
**Status:** READY FOR DEV (minor clarifications noted, no blockers)

---

## Summary

The story is **well-researched, appropriately scoped, and technically sound**. The six identified gaps in `BookingService` are real, the `RescheduleService` extension is justified, and the `BookingBatchService` fix closes a known fallback. No false assumptions found that would break the implementation. Three clarifications below are worth reading before dev starts, but none require story edits.

---

## Verified Correct

✅ **The audit premise is sound**: The story correctly re-audited the specific question deferred by `skillars-deferred-67`'s code review ("does anything else reuse MISSING_RIGHTS for this exception?"), found the literal answer is no, but surfaced the broader, real gap — unguarded `OptimisticLockingFailureException` in six interactive write paths.

✅ **Six BookingService methods identified correctly**: Spot-checked the method signatures and write call patterns against the AC1 description — all six are accurate, all are REST-reachable, all reach `transitionInternal`'s unlocked save.

✅ **RescheduleService.acceptReschedule is a real gap**: Does indeed load the booking unlocked and save it with no catch, distinct from `requestReschedule` and `declineReschedule` which only mutate the reschedule request, not the booking itself.

✅ **Already-safe exclusions are properly justified**: 
- `cancelBookingAsParent` — correctly identified as having `PESSIMISTIC_WRITE` lock (per prior `skillars-deferred-64 AC2`)
- `AvailabilityService` — correctly confirmed by grep that neither `CoachAvailabilityWindow` nor `CoachAvailabilityBlock` has `@Version`
- `BookingReminderScheduler`, `BookingExpiryScheduler`, `QuickCompleteTimeoutService` — correctly noted as already catching or logging this exception
- `BookingBatchService.acceptOneBooking` — correctly identified as wrapped in the per-booking `catch (Exception e)` loop, but with a missing error-code branch

✅ **The fix shape is proven**: Reuses the exact message, exception chaining, and error code already decided by `skillars-deferred-66`/`-67`. Consistent application with no invented variants.

✅ **Test strategy is sound**: One concurrency test per site, mirroring existing happy-path patterns, using standard Mockito stubs and AssertJ fluent assertions.

---

## Clarifications for Dev (No Story Edits Needed)

### 1. AC2 Wording vs. Code Example — Read Carefully

**In AC2**, the text says:
> "Wrap exactly the three existing statements:
> ```
> booking.setRequestedStartTime(req.getProposedStartTime());
> booking.setRequestedEndTime(req.getProposedEndTime());
> try {
>     bookingRepository.save(booking);
> } catch (OptimisticLockingFailureException e) {
> ```"

**Potential confusion**: The phrase "wrap exactly the three existing statements" might read as if all three (`setRequestedStartTime`, `setRequestedEndTime`, `save`) go inside the `try` block. The code example clarifies that only the `save` is wrapped. This is **correct** — setters cannot throw `OptimisticLockingFailureException`; only the `save` can.

**Action for dev**: Follow the code block, not the phrase. Only `bookingRepository.save(booking)` goes in the `try` block.

---

### 2. AC1 cancelBookingAsCoach — The Two-Statement Wrap and Event Publishing

**In AC1**, the story says:
> "Compute `resolvedReason` **before** the `try` block ... so it remains in scope for the event-publish call after the `try`/`catch`"

**Potential confusion**: The code block shows:
```java
String resolvedReason = cancelReason != null ? cancelReason : "OTHER_UNEXCUSED";
try {
    transition(..., BookingEvent.CANCEL_COACH, ...);
    booking.setCancelReason(resolvedReason);
    bookingRepository.save(booking);
} catch (OptimisticLockingFailureException e) {
    throw new OperationNotAllowedException(...);
}
// Does event publishing happen here?
```

The story mentions an "event-publish call after the try/catch", but the `catch` block **throws**, so there is no code after it in this method. The story likely means the event is published **inside** `transition()` (which enqueues the `BookingEvent.CANCEL_COACH` event), not after the exception handler.

**Action for dev**: Read the actual `cancelBookingAsCoach` method to see where event publishing happens. If it's inside `transition()` or inside the repository save, this is fine. If there is truly a separate event-publish call after the current catch block in the live code, the story's rationale for computing `resolvedReason` early is correct. Either way, computing it before the `try` block (pure local logic) is safe.

---

### 3. AC1 acceptBooking — Transaction Scope Assumption

**In the story**, under AC1 justification:
> "This single call covers both internal `transitionInternal` legs (`ACCEPT` then `INITIATE_PAYMENT`) since `acceptAndInitiatePayment` is reached by plain self-invocation from `acceptBooking`, inside the same transaction"

**Potential edge case**: This assumes `acceptAndInitiatePayment` runs in the **same transaction** as `acceptBooking`, so that `transitionInternal` is called twice within one transaction boundary and both state transitions are protected by a single `try`/`catch` block.

**Action for dev**: Verify that `acceptAndInitiatePayment` is **not** annotated with `@Transactional(propagation=REQUIRES_NEW)` or any other propagation that would create a nested transaction. If it is, the two transitions happen in separate transactions, and the version check occurs independently for each. In that case, wrapping the single `acceptAndInitiatePayment()` call is still correct (catches both), but the assumption of "same transaction" is worth verifying for understanding. The actual implementation (wrapping the call once) is correct either way.

---

## No Issues Found

❌ **No false positives**: All identified gaps are real. No over-reach or cargo-cult fixes.

❌ **No missed flows**: 
- Batch error handling (AC3) is correctly isolated to the `resolveFailureCode` helper; control flow is unchanged.
- Ledger hygiene (AC5) correctly closes the deferred item by reference.
- No new i18n keys needed (message key already added by `skillars-deferred-67`).

❌ **No edge cases missed**:
- The two-save pattern in `cancelBookingAsCoach` is correctly wrapped as a single unit (both writes happen on the same versioned row, either can throw).
- `recordNoShowPlayer`, `recordNoShowCoach`, `cancelDueToPause` are all single-call wraps with no special edge cases.
- `BookingBatchService` fix is purely in error classification, no control-flow changes.

---

## Minor Notes

1. **Test for `cancelBookingAsCoach` is the first test for this method in `BookingServiceTest`**: The dev notes correctly flag this. This is fine — the story is focused on concurrency, not adding happy-path coverage. But if the dev agent finds it easy to add one happy-path test for this method (e.g., a simple "method exists and transitions booking" case), it would close a pre-existing gap. Not required by this story.

2. **No frontend re-verification**: The story explicitly notes it did not re-verify whether frontends consume these six endpoints and read the error message. This is honest and out of scope. If a frontend is found to be consuming one of these endpoints, that's a signal the fix has real user value; if not, it's still defensive correctness.

3. **`BookingReminderScheduler` and `BookingExpiryScheduler` swallow all exceptions**: The story correctly notes these are already safe from a UX perspective (no 500 to user), but the exception swallowing is a design choice made before this story. Not touched here, correctly excluded.

---

## Recommendation

**Status: APPROVED FOR DEV**

The story is ready to implement. The three clarifications above are light reading for context, not blockers. Follow the code blocks in AC1-AC3 exactly (they're precise), verify the transaction scope for `acceptBooking` out of curiosity, and implement the tests mirroring the existing patterns in each test class.

No story edits needed. The implementation is straightforward, the scope is tight, and the quality bar is high.
