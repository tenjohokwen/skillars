# Story Deferred-69 Senior Dev Audit

Status: Ready for dev with noted corner cases and verification requirements

## Critical Issues (Must Verify Before/During Implementation)

### 1. **AC1: Timezone Assumption in Midnight-Crossing Check**
**Risk Level: Medium**

The midnight-crossing check compares dates across timezones without explicit conversion:
```java
if (!endZdt.toLocalDate().equals(startZdt.toLocalDate())) {
    throw ...
}
```

This assumes `startZdt` and `endZdt` are already in the window's timezone (or that their local dates are semantically comparable). This is likely true (sessions probably have a canonical timezone matching the coach's zone), but **not explicitly stated in the story**.

**Verification needed:**
- Confirm that `startZdt` and `endZdt` passed to `isSlotWithinAvailabilityWindow` are guaranteed to be in the same timezone
- If uncertain, convert both to the window's timezone before comparing local dates:
  ```java
  ZoneId windowZone = ZoneId.of(zoneId); // or however the zone is accessed
  if (!endZdt.withZoneSameInstant(windowZone).toLocalDate().equals(
      startZdt.withZoneSameInstant(windowZone).toLocalDate())) {
  ```
- Check existing call sites (5 locations) to see what timezone they pass

---

### 2. **AC5: Shared Validation Extraction Requires Exact Byte-For-Byte Match**
**Risk Level: High**

AC5 requires extracting the validation block from `requestReschedule` (lines 79-124) into `validateRescheduleProposal()` and calling it from both `requestReschedule` and the new `requestRescheduleAsCoach`. 

**Risk:** If the validation blocks differ even slightly (different exception messages, different guard conditions), extracting them will break one path.

**Verification needed:**
- Before extracting, do a careful line-by-line diff of what will be extracted
- If any differences exist, either consolidate them into the extracted method or leave as duplication (the story explicitly permits duplication only for "three similar lines" threshold)
- Verify both paths produce identical errors and messages post-extraction

---

### 3. **AC5: Shared Accept/Decline Body Extraction**
**Risk Level: High**

Similar to validation extraction, AC5 says: "Extract `acceptReschedule`'s shared lock/availability/overlap body (`:184-256`) into a private `acceptRescheduleShared()`. Both `acceptReschedule` and the new `acceptRescheduleAsParent` call it."

**Verification needed:**
- Carefully diff the lock-acquisition order, availability re-check, and overlap check
- Ensure the extracted method can handle both coach and parent calls without bifurcating logic
- Verify the transaction boundaries are correct (both callers are `@Transactional`, the shared method should not add its own `@Transactional` unless necessary)
- Test that any error handling (e.g., `OptimisticLockingFailureException` → `CONCURRENT_MODIFICATION`) works identically for both paths

---

### 4. **AC5: Coach Booking Page Location Unknown**
**Risk Level: Medium (Expected Exploration Task)**

The story explicitly states: "no coach-facing page with a reschedule-proposal UI currently exists (not located during story creation)."

AC5 requires adding "Propose New Time" UI to the coach's booking list/schedule page. The story gives guidance to "mirror `ParentBookingsPage.vue`'s dialog" but the coach page may use different component structure.

**Verification needed:**
- Locate the coach booking/schedule page (likely `CoachBookingsPage.vue`, `CoachScheduleePage.vue`, or similar)
- Verify it uses the same component library (Quasar, same Vue version) as parent page
- If component structure differs significantly, adapt the provided dialog/button design accordingly
- Verify coach can already see and act on `acceptReschedule`/`declineReschedule` for parent-initiated proposals (the UI for this should exist already)

---

## Moderate Issues (Verify, But Not Blockers)

### 5. **AC1: Comment Correction Requirement**
**Risk Level: Low**

The story requires correcting the misleading doc comment at lines 896-898. The comment claims to describe cross-midnight handling that the current code doesn't actually do.

**Verification needed:**
- Locate and read the actual comment before making the fix
- Ensure the corrected comment accurately describes the new behavior (rejection, not special handling)

---

### 6. **AC2: Fixed-Hour Test Assumption (10:00)**
**Risk Level: Low**

The test helper anchors to 10:00 AM fixed. The story says to "confirm each replaced test's assertions still make sense" but doesn't verify the ~20 call sites.

**Verification needed:**
- When replacing `Instant.now().plus(N, DAYS)` calls, check if the test needs a different time-of-day
- Most tests should be fine with 10:00 (safe from midnight crossing, within the fixture's 00:00-23:59 window)
- If any test expects a specific time-of-day (e.g., testing near-midnight behavior), that test should either be rewritten or explicitly use a different time

---

### 7. **AC3: Parent Cancel Status Gate May Be Too Restrictive**
**Risk Level: Low (Design Choice)**

The story gates the Cancel button on `['CONFIRMED', 'UPCOMING'].includes(booking.status)`, matching the Request-Change gate. But `cancelBookingAsParent` has "no status whitelist of its own beyond ownership."

**Potential gap:** If the server allows canceling other statuses (e.g., `COMPLETED` with partial refunds), the UI gate is overly restrictive. However, the story explicitly says the late-cancel behavior is out of scope and tracked separately.

**Verification needed:**
- Check if `cancelBookingAsParent` should allow other statuses
- If yes, widen the status gate or remove it entirely
- If no, the current gate is correct (defensive against future server changes)

---

### 8. **AC3: Error Handling Completeness**
**Risk Level: Low**

The story specifies handling `MISSING_RIGHTS` and `booking.paymentInProgress` errors. 

**Verification needed:**
- Confirm `cancelBookingAsParent` only throws these two error codes (not others)
- Add a catch-all error handler if there are any unexpected errors the service might throw

---

### 9. **AC4: Self-Booking Ownership Check Verification**
**Risk Level: Medium**

The story says: "Before assuming each of the 5 underlying service methods' ownership check follows the same `booking.getParentId().equals(callerId)` self-booking-compatible shape confirmed for `cancelBookingAsParent` above, **confirm each one individually**."

**Verification needed:**
1. `recordNoShowCoach` — does it check `booking.getParentId()` (not just coach ownership)?
2. `requestReschedule` — does it check `booking.getParentId()` for parent?
3. `confirmCompletion` / `BookingCompletionService.confirmCompletion` — does it check correctly?
4. `getParentSchedule` — does it filter by `booking.getParentId()`?
5. Whichever method backs the "no-show-coach" action

All must work for self-booking players (where `parentId` is the player's own userId).

---

### 10. **AC5: Event Direction-Agnosticism Assumption**
**Risk Level: Low**

The story reuses `RescheduleAcceptedEvent` "because it already carries both `parentEmail` and `coachEmail`". But the story doesn't show the constructor.

**Verification needed:**
- Read `RescheduleAcceptedEvent.java` constructor to confirm it takes both emails
- Verify `BookingEmailListener.onRescheduleAccepted` sends to both parties (direction-agnostic)
- If the listener assumes one direction (e.g., "coach accepted → notify parent"), it might need a guard to distinguish who accepted

---

### 11. **AC5: Event Constructor Shape for New Events**
**Risk Level: Low**

The story specifies new events `RescheduleRequestedByCoachEvent` and `RescheduleDeclinedByParentEvent` should mirror existing events but with swapped role fields.

**Verification needed:**
- Read constructors of `RescheduleRequestedEvent` and `RescheduleDeclinedEvent`
- Ensure the field names and types match (e.g., `parentEmail` vs. `coachEmail`)
- The new events should have identical structure, just with roles reversed

---

### 12. **AC6: lockRetryer Availability**
**Risk Level: Low**

The story assumes `BookingBatchService` already injects `lockRetryer` and uses it (e.g., at line 384). 

**Verification needed:**
- Confirm `lockRetryer` is injected and available
- If not, add the injection
- If it's not available, this entire approach fails

---

### 13. **AC7: Staleness Window Re-Validation Timing**
**Risk Level: Low**

AC7 re-validates immediately before persist ("`:189`, right before `BookingBatch batch = new BookingBatch();`"). This must happen inside the same transaction.

**Verification needed:**
- Confirm the re-validation is inside the transaction (no separate query that could go stale)
- Verify rolling back the transaction rolls back any partial inserts
- Confirm the story's note about "honest partial fix" is reflected in code comments

---

### 14. **AC8: Lock-Order Test Call Sequence**
**Risk Level: Low**

The test uses Mockito `InOrder` to verify `rescheduleRepo.findByIdForUpdate()` is called before `coachProfileRepository.findByIdForUpdate()`.

**Verification needed:**
- Verify both methods are called (and mocked) in the test
- The test is checking call order, not actual lock acquisition, which is appropriate
- Ensure the test doesn't create false positives if refactoring changes call sequence but not lock order

---

### 15. **AC9: Concurrency IT Implementation Complexity**
**Risk Level: Medium (Complex Implementation)**

AC9 requires holding a real `SELECT ... FOR UPDATE` lock from a background thread while the main thread contends. This requires careful coordination with `ExecutorService`, `CountDownLatch`, and `Future`.

**Verification needed:**
- Mirror `SessionPackPurchaseLockContentionIT`'s exact pattern (which should already work)
- Use a Testcontainers Postgres instance (not mocked)
- Ensure the background thread holds the lock long enough for the main thread to attempt contention
- Test both cases: brief contention (succeeds within retry budget) and prolonged contention (fails cleanly)
- Avoid race conditions in the test itself (e.g., background thread not started before main thread tries to contend)

---

### 16. **AC10: Shared State Removal Completeness**
**Risk Level: Low**

AC10 removes `completionLoading` and `completionError` from `booking.store.js`. The story says "confirmed zero consumers repo-wide" via grep.

**Verification needed:**
- Before removing, do a final grep for both variables across the entire project
- Check for dynamic property access (e.g., `state['completionLoading']`) that grep might miss
- Verify all 10 handlers that set these values are updated to remove assignments
- Confirm each handler still has its own error handling (not depending on shared state)

---

## Design & Flow Verification

### 17. **AC5: Coach-Proposed Reschedule End-to-End Flow**
**Risk Level: Low (But Should Be Tested)**

The new flow is: Coach proposes → parent gets notified → parent accepts/declines → coach gets notified.

**Verification needed:**
- Coach-side: "Propose New Time" button creates a reschedule request with `proposedBy='COACH'`
- Parent-side: existing "pending reschedule indicator" shows coach-proposed reschedules
- New accept/decline buttons appear and gate on `booking.pendingReschedule?.proposedBy === 'COACH'`
- Clicking accept/decline calls the new `acceptRescheduleAsParent`/`declineRescheduleAsParent` endpoints
- Backend publishes correct events (`RescheduleAcceptedEvent`, `RescheduleDeclinedByParentEvent`)
- Listeners notify coach correctly
- Coach's UI updates to show accepted/declined status

---

### 18. **AC3+AC4+AC5: Self-Booking Player Visibility**
**Risk Level: Low (Frontend Testing)**

AC4 widens two existing gates (`isParent && ...` → `isParent || isPlayer`). AC3 adds cancel. AC5 adds accept/decline.

**Verification needed:**
- A self-registered adult player viewing their own bookings should see ALL buttons:
  - "Request Change" (widened by AC4)
  - "Confirm Completion" (widened by AC4)
  - "Cancel" (new in AC3, gated same way)
  - "Accept/Decline Coach Proposal" (new in AC5, gated same way, when coach proposes)
- Status gates should work identically for players and parents
- Frontend auth guards (`authStore.isParent || authStore.isPlayer`) should work correctly

---

### 19. **AC1+AC2: Midnight-Crossing Test Flakiness**
**Risk Level: Low (Root Cause Fixed)**

AC1 throws `SESSION_CROSSES_MIDNIGHT` for any session crossing midnight. AC2 fixes the test time helper to use a fixed 10:00 start.

**Verification needed:**
- `RescheduleResourceIT` tests that rely on `Instant.now().plus(N, DAYS)` will fail before AC2 is applied if CI runs near midnight
- IMPORTANT: Don't ship AC1 without AC2, or tests will start failing near midnight in CI
- After both are applied, tests should be deterministically passing regardless of CI time

---

## Audit Metadata

**Reviewer's Confidence by AC:**
- AC1: **Medium** (timezone assumption needs verification)
- AC2: **High** (straightforward, well-scoped)
- AC3: **High** (existing endpoint, straightforward UI)
- AC4: **High** (scope is clear, verification requirement is documented)
- AC5: **Medium** (complex refactoring, coach page location unknown, event design requires verification)
- AC6: **High** (pattern is established in codebase)
- AC7: **High** (acknowledged as partial fix, clear scope)
- AC8: **High** (lightweight test, no live bug)
- AC9: **Medium** (complex concurrency IT, mirrors pattern but requires careful impl)
- AC10: **High** (grep-verified dead code)
- AC11: **High** (documentation update, straightforward)

**False Positives (Things That Look Wrong But Aren't):**
- Status gate in AC3 being conservative — this is acceptable per story scoping
- Duplication of validation/accept body before extraction — story explicitly calls for extraction
- Timezone handling in AC1 — likely correct assuming sessions have consistent canonical timezone
- Event reuse assuming direction-agnosticism — story authors verified this; trust it unless proven wrong

**Story Strengths:**
- Excellent documentation of assumptions and gaps
- Explicit guidance on verification steps (e.g., "confirm each service method individually" in AC4)
- Acknowledged limitations (e.g., AC7's partial staleness fix, AC9 not being a deadlock reproduction)
- Clear task breakdown avoids ambiguity

**Story Weaknesses:**
- Timezone handling in AC1 is implicit, not explicit
- Coach page location is unknown (expected exploration, but adds risk)
- Event constructor shapes not shown (dev agent must read source)
- Concurrency IT requires understanding an external pattern (SessionPackPurchaseLockContentionIT)

## Recommendation

**Ready to start development with these preconditions:**

1. **Before starting AC1**, verify timezone assumption (issue #1)
2. **Before extracting in AC5**, carefully diff the validation and accept/decline blocks (issues #2, #3)
3. **At AC5 start**, locate the coach booking page (issue #4)
4. **During AC4**, verify each of the 5 service methods' ownership checks (issue #9)
5. **For AC9**, mirror the pattern from `SessionPackPurchaseLockContentionIT` exactly (issue #15)

All other issues are either low-risk or have clear verification guidance in the story itself.

**No false positives detected.** All flagged items are either genuine corner cases or reasonable verification steps the dev agent should take.
