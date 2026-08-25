# Story Review: Deferred-67 Booking Completion Lock-Conflict Error Code & Exception Chaining

**Review Date:** 2026-08-25  
**Reviewer:** Senior Dev Audit  
**Status:** READY FOR DEV — No blockers found. Minor documentation clarification noted.

---

## Executive Summary

The story is **well-scoped, specific, and technically sound**. All acceptance criteria are clearly defined with exact file line numbers and code shapes. The work is a straightforward copy-the-target-shape change across 7 methods with strong guardrails against drift.

One minor **documentation discrepancy** found (test count math), but does not affect the actual work to be performed. All corner cases and assumptions verified against current codebase.

---

## Verification Against Current Source

✅ **All 7 methods correctly identified** — Live-verified against `BookingCompletionService.java`:
- `startSession` (line 49): ✓ catches OLF, chains cause, has comment to remove
- `endSession` (line 65): ✓ catches OLF, does NOT chain cause
- `pauseSession` (line 83): ✓ catches OLF, does NOT chain cause
- `resumeSession` (line 96): ✓ catches OLF, does NOT chain cause
- `initiateQuickComplete` (line 109): ✓ catches OLF, chains cause, has comment to remove
- `submitWrapUp` LIVE branch (line 159): ✓ catches OLF (only in LIVE mode, not QUICK mode), chains cause, has comment to remove
- `confirmCompletion` (line 185): ✓ catches OLF, does NOT chain cause, has wrong message ("Session already confirmed")

✅ **Exception chaining constructor verified** — `OperationNotAllowedException(String, Throwable, ErrorCode)` added by deferred-66 is in use by the first 3 methods, ready to apply to the other 4.

✅ **SecurityError still needed** — `verifyCoachOwnership` (line 225) and `verifyStatus` (line 231) both throw `SecurityError.MISSING_RIGHTS`, so do NOT remove the import.

✅ **Three comments to remove correctly identified**:
- `startSession` lines 57–59: Comment explains MISSING_RIGHTS reuse choice ✓
- `initiateQuickComplete` line 121: Same explanatory comment ✓
- `submitWrapUp` LIVE branch line 163: Same explanatory comment ✓

---

## Acceptance Criteria Analysis

### AC1: Add `BookingError.CONCURRENT_MODIFICATION`

**✅ Error code location clear** — `BookingError.java` has 12 enum constants (line 30–42). New constant should go after `NO_SHOW_TOO_EARLY` (line 42).

**✅ Switch statement mapping** — The `getErrorCode()` method (line 45–60) must add one case for the new constant.

**✅ Doc comment guidance** — The instruction to add a "short paragraph following the two existing ones" is clear. The existing doc describes how the prior two splits came from re-mining ledger items; the new paragraph should explain this one differs in *kind* (concurrent race, not authorization split).

**✅ i18n message placement** — Story specifies exact line numbers for 4 locale files:
- `messages.properties:88` (base/fallback)
- `messages_en.properties:132` (alongside `booking.noShowTooEarly`)
- `messages_de.properties:75`
- `messages_fr.properties:122`

The 4 locale files are the only ones in this project; no other locales to handle.

**✅ Import addition** — Story correctly says to add `import com.softropic.skillars.platform.booking.contract.BookingError;` and NOT remove the `SecurityError` import (still used by verifier methods).

### AC2: Fix message + chain exception in 4 methods

**✅ Message fix in `confirmCompletion`** — Current: `"Session already confirmed"` (wrong — fires on any concurrent write, not specifically when already confirmed). New: `"Booking status changed concurrently — retry"` (matches the other 6 sites). Rationale is sound: a genuinely already-confirmed booking would fail `verifyStatus` earlier with a different message.

**✅ Exception chaining in 4 methods** — All four (`endSession:78`, `pauseSession:91`, `resumeSession:104`, `confirmCompletion:194`) currently use the 2-arg constructor and must switch to the 3-arg constructor, passing the caught exception `e`.

**✅ Final shape is byte-for-byte identical** — All 7 blocks will read:
```java
} catch (OptimisticLockingFailureException e) {
    throw new OperationNotAllowedException("Booking status changed concurrently — retry", e, BookingError.CONCURRENT_MODIFICATION);
}
```
This is clear and verifiable.

**✅ Test strengthening** — The 3 existing concurrency tests (lines 192–224) need one additional assertion each:  
`.extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION)`

**✅ New test setup details are correct**:
- `endSession_concurrentModification...`: Status must be `IN_PROGRESS` (passes the inline status check at line 70)
- `pauseSession_concurrentModification...`: Status must be `IN_PROGRESS` (passes verifyStatus call)
- `resumeSession_concurrentModification...`: Status must be `PAUSED` (passes verifyStatus call)
- `confirmCompletion_concurrentModification...`: Status is already `COMPLETED_PENDING_CONFIRMATION` from `setUp()` (line 83)

**✅ Test short-circuiting logic** — The story correctly notes that for `confirmCompletion`, the mocked `transition()` throw (line 192) occurs before the `completionDataRepository.findByBookingId` call (line 197), so the test should NOT stub that repository. This prevents test brittleness if the order ever changes.

---

## Corner Cases & False Assumptions — All Verified

### 1. **Multiple OptimisticLockingFailureException sites in submitWrapUp**
✅ **Verified**: Only the LIVE-mode branch (line 159–165) catches this exception. The DataIntegrityViolationException handler (line 153–157) is separate and unrelated to this story. Correct.

### 2. **Test status setup vs. setUp() defaults**
✅ **Verified**: The shared `setUp()` creates a booking with status `COMPLETED_PENDING_CONFIRMATION` (line 83). Each new test will explicitly set its own booking status before calling the method under test (e.g., `booking.setStatus(BookingStatus.IN_PROGRESS.name())`). No blocker — each test method gets a fresh booking object.

### 3. **TransitionContext.ActorRole parameter in transition calls**
✅ **Verified**: All 7 transition calls pass an `ActorRole` (COACH or PARENT). The mocked transition in tests just needs to match the booking ID and event type; the ActorRole goes into the `any()` matcher. Correct.

### 4. **Scope: Other methods in BookingCompletionService?**
✅ **Verified**: No other methods in this class catch `OptimisticLockingFailureException`. The `submitWrapUp` LIVE-branch check is the only other try-catch in the class, and it's a `DataIntegrityViolationException` (idempotency guard), unrelated. Story scope is complete.

### 5. **Scope: Other services in the Booking module?**
✅ **Verified as out of scope**: The story is scoped by deferred-work.md's "Deferred from: code review of skillars-deferred-66" section, which lists only BookingCompletionService items. If other services (BookingService, RescheduleService, etc.) have similar issues, they're not part of this story. This is intentional and documented.

### 6. **HTTP 403 semantics (concurrent modification ≠ authorization)**
✅ **Acknowledged trade-off, not a flaw**: The story explicitly chooses to keep HTTP 403 (unchanged from deferred-66) and distinguish the actual error at the wire level via the new error code, rather than changing to HTTP 409 (Conflict). This avoids breaking existing tests and API contracts. The story notes "No live frontend behavior changes" because current consumers ignore the error message and use fixed local copy anyway. This is acceptable.

### 7. **i18n message content & translations**
✅ **Verified**: The story provides the exact message for only the 4 locales the project supports (EN, DE, FR, and base/fallback). No missing translations beyond the project's supported set.

---

## ⚠️ Minor Issues Found

### **Issue 1: Test Count Discrepancy (Documentation)**
**Severity:** Low — Documentation only, does not affect actual work.

**Finding:** Story states: "*for **10 total concurrency-conflict tests** covering all 7 methods once shipped (up from today's 3-of-7 coverage*)"

**Math check:**
- Today: 3 existing concurrency tests (startSession, initiateQuickComplete, submitWrapUp)
- Adding: 4 new concurrency tests (endSession, pauseSession, resumeSession, confirmCompletion)
- **Total: 7, not 10**

**Impact:** The dev should expect 7 total concurrency tests, not 10. This is a typo in the story documentation, likely a mental slip (perhaps the author was thinking of a different phase or counting something else).

**No code impact** — the story's actual test requirements (3 existing tests strengthen + 4 new tests) are clear and correct. The dev reading this will understand from the explicit task list what to do.

---

## No False Positives Found

✅ The story contains no inaccurate or misleading statements about the codebase that would cause false work.
✅ All method signatures, exception types, and import statements match reality.
✅ The byte-for-byte identical end shape is achievable without refactoring.

---

## Guardrails Against Implementation Drift

The story includes several explicit "do NOT" instructions that prevent common mistakes:

✅ "Do not remove the `SecurityError` import" — clear because verifier methods still use it.
✅ "Do not touch `verifyCoachOwnership` or `verifyStatus`" — out of scope, unrelated to concurrency.
✅ "Do not redesign the concurrency handling" — copy-the-target-shape only.
✅ "Do not introduce a different message, exception type, retry loop, or change HTTP status" — scope guardrails.
✅ "Both ACs touch the same 7 blocks — implement together, not as two passes" — prevents repeated edits.

These are well-placed and comprehensive.

---

## Recommendations for Dev Agent

1. **Verify i18n file line numbers** before inserting the new key — the story provides specific line numbers (88, 132, 75, 122), and you should confirm these correspond to where `booking.noShowTooEarly` currently sits in each file, before inserting adjacent.

2. **Verify the test setup status for each new test** — when you write the 4 new concurrency tests, confirm that setting the booking status *before* calling the method under test is the pattern used by existing tests (it is; see test lines 165–172 for an example).

3. **All 7 catch blocks should be identical at the end** — use this as a final verification step: grep for the catch block pattern and confirm all 7 match byte-for-byte.

4. **The test count is 7, not 10** — don't overthink this discrepancy; the actual task list is clear.

---

## Final Verdict

✅ **READY FOR DEV**

The story is clear, specific, and technically sound. No blockers. One minor documentation typo (test count) has no impact on the actual work. All corner cases verified. The guardrails are strong. The dev should feel confident moving forward.
