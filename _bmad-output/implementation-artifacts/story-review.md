# Story Audit: Skillars-Deferred-121 (Coach Enforcement Status TOCTOU Lock Gap)

**Audit Date:** 2026-09-18  
**Auditor:** Senior Dev Review  
**Status:** ✅ **PASS — No false positives, well-scoped, ready for implementation**

---

## Executive Summary

This is a high-quality bug report with a clear, genuine TOCTOU race condition that is reachable today in single-instance deployment. The fix mirrors established patterns elsewhere in the codebase (`suspendCoach`, `ReliabilityStrikeService.issue`). All false leads are correctly ruled out. No missed corner cases found.

---

## Verified Claims

### ✅ Bug Premise: Two Writers Skip the Lock Every Other Writer Uses

**Inventory table checked against codebase:**

1. `ReliabilityStrikeService.issue:88` — **✅ CONFIRMED locked** (`findByIdForUpdate` + `lockRetryer.withBoundedRetry`)
2. `AdminCoachEnforcementService.suspendCoach:107` — **✅ CONFIRMED locked** (same pattern as item 1)
3. `AdminCoachEnforcementService.reinstateCoach:151` — **✅ CONFIRMED UNLOCKED** (plain `findById`, AC1 finding)
4. `AdminCoachEnforcementService.deleteStrike:224` — **✅ CONFIRMED UNLOCKED** (plain `findById`, AC1 finding)
5. `CoachProfileService.publishProfile/saveStep4` — **✅ CONFIRMED protected** (locked at line 249-255, profile instance stays managed through same user flow; no plausible concurrent second admin-like writer of DRAFT status)
6. `CoachProfileService.saveStep1/getOrCreateDraft` — **✅ N/A (new row, no concurrent reader)**

**CoachProfile has no `@Version`:** ✅ CONFIRMED (read CoachProfile.java:29 — only @Id, not @Version)

### ✅ Failure Scenario is Realistic and Reachable Today

**Concrete race traced:**
- Coach at PENDING_REVIEW
- Admin A reads stale PENDING_REVIEW → decides ACTIVE → waits on write lock
- Admin B calls suspendCoach, takes lock, updates to SUSPENDED
- Admin A's UPDATE unblocks and overwrites to ACTIVE (using stale decision)
- Result: `suspendCoach`'s side effects (booking cancellations, events) are applied but status silently reverts

**This is single-instance, no multi-deploy required:** ✅ CONFIRMED (two ordinary concurrent HTTP requests, or one admin request racing an automatic strike escalation)

### ✅ Fix Pattern is Established Elsewhere

- `suspendCoach` (line 107–108) uses exact same pattern: `lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(...))`
- Story explicitly cites this existing usage as the template
- No new infrastructure or utility needed

### ✅ False Leads Ruled Out (Examined and Dismissed Correctly)

**DisputeService.resolveDispute/dismissDispute:**
- `@Version` column on Dispute entity → optimistic locking prevents double-writes
- Money-movement and dispute update in same transaction → loser's version check fails, entire transaction rolls back (no double-refund)
- ✅ **Correctly ruled out as not needing a fix**

**ReviewFlagService.flag (threshold re-check):**
- Two concurrent flags at threshold boundary both call `coachRatingService.recompute()` → duplicate work only
- Unique index `review_flags_unique_flagger` already prevents the serious case (same user double-flagging)
- ✅ **Correctly ruled out — idempotent work, no corruption** (caveat: assumes `recompute()` is idempotent; implementer should verify if non-obvious)

**GdprRequestService.requestErasure/requestExport:**
- DB-level partial unique index on `(user_id, request_type) WHERE status IN ('PENDING', 'PROCESSING')` prevents constraint violation
- Global `@ExceptionHandler(DataIntegrityViolationException.class)` converts to handled 400
- ✅ **Correctly ruled out** — race is closed by DB, error message is cosmetic trade-off (400 vs 409)

---

## Corner Cases & Assumptions Checked

### ✅ Scope Boundaries Are Sound

**Explicitly excluded (not re-opening without new evidence):**
- `CoachProfileService.publishProfile` — lacks concurrent admin action that could race it; profile instance already locked one step prior in multi-step builder flow
- `DisputeService`, `ReviewFlagService`, `GdprRequestService` — all three examined and ruled out as false leads
- **Correct decision:** scope-creep risk is low, these exclusions are defensible

### ✅ Task 2 (deleteStrike) Reordering is Correct

Current order: delete strike (218) → compute count (220) → read coach (224) → decide revert (231)  
Proposed: delete strike (218) → **lock + read coach** (before 220) → compute count → decide revert

**Rationale:** Decision is made with fresh count under the coach lock, minimizing staleness window.  
**Why strike deletion stays early:** It's a separate row, doesn't need coach lock.  
✅ **Correct approach** (mirrors `ReliabilityStrikeService.issue`'s ordering principle)

### ✅ resolveOpenStrikeAlert Behavior (Implicit Assumption)

`reinstateCoach:167` and `deleteStrike:240` both call `resolveOpenStrikeAlert` after the status update.  
Story doesn't explicitly address whether alert state needs re-checking after acquiring lock.

**Assessment:** ✅ **Acceptable assumption** — `resolveOpenStrikeAlert` (lines 302–310) is idempotent:
- Finds alert by (coachId, type=STRIKE_THRESHOLD, status=OPEN)
- If found, resolves; if not found, no-op
- Calling it based on stale read is safe; it will resolve whatever alert existed at the moment of update

**Minor note:** If a new STRIKE_THRESHOLD alert is created between stale read and update, it won't be resolved by this call. This is acceptable (alert was created by concurrent event outside the scope of this admin action).

### ✅ Lock Acquisition Doesn't Prevent Related Rows from Changing

`deleteStrike` computes fresh strike count AFTER acquiring coach lock, but:
- Strike table is not locked by coach row lock
- Other transactions can still add/delete strikes while coach lock is held
- **This is correct:** The lock's purpose is to serialize coach status updates, not prevent strike changes. Fresh count under lock still gives better decision-making than stale count.

### ✅ Concurrent Admin Actions on Same Coach

Two admins calling different methods (`suspendCoach` vs `reinstateCoach`) concurrently:
- Both compete for same coach row lock via `findByIdForUpdate`
- Serialized deterministically by lock acquisition order
- Second thread re-reads coach with updated status from first
- If state transition invalid, explicit checks (line 157–161 in reinstateCoach, line 110 in suspendCoach) will catch and throw or no-op
- ✅ **Correct behavior**, test should verify this

### ✅ Coach Deletion Edge Case

If coach is deleted between strike deletion and coach lock acquisition in `deleteStrike`:
- `findByIdForUpdate().orElseThrow()` throws ResourceNotFoundException
- Exception bubbles up, caller handles (or gets 500)
- This is correct (coach shouldn't disappear mid-operation)

---

## Test Strategy Assessment

### ✅ Concurrency Test Approach

**Mirrors ReliabilityStrikeConcurrencyIT correctly:**
- Use CountDownLatch to release both threads simultaneously
- Use ExecutorService for thread management
- Race one thread calling `suspendCoach(...)`, other calling `reinstateCoach(...)`
- Assert final status is deterministic and correct

**Potential clarification needed:** Story says "the coach ends `SUSPENDED` when `suspendCoach` is the one to actually persist last." Test should:
1. Run both orderings (or let test runner handle thread scheduling)
2. Capture which operation acquires lock first
3. Assert: final status = what the lock-acquirer-second intended to write (they overwrite the first thread)

This means:
- If `suspendCoach` gets lock last → coach is SUSPENDED ✓
- If `reinstateCoach` gets lock last → coach is ACTIVE ✓
- Never mixed/inconsistent ✓

### ✅ Mutation Check

Story explicitly requires: revert fix (use plain `findById` again), confirm test fails.  
✅ **Correct requirement** — proves test would have caught the bug pre-fix, not just green by construction

### ✅ Secondary deleteStrike Test

Story mentions "a second, simpler test" for deleteStrike's revert path.  
✅ **Reasonable** — simpler unit/mock test confirming count is read after lock, not just integration test

---

## Potential Implementation Pitfalls (Flagged for Dev)

1. **Order of lines in Task 2:** When moving the locked read in `deleteStrike` to before the count computation, ensure:
   - Strike deletion (line 218) stays first
   - Locked read moves to before count (line 220)
   - Count reads AFTER lock is held
   - Reverted decision uses fresh count and fresh lock-held coach

2. **Exception consistency:** Both `reinstateCoach` and `deleteStrike` should use the same exception message as `suspendCoach`:
   ```java
   new ResourceNotFoundException("Coach profile not found", "coach_profile")
   ```
   (Story already specifies this for reinstateCoach, apply to deleteStrike too)

3. **Test isolation:** When seeding coach at PENDING_REVIEW for IT, use same raw-SQL pattern as `ReinstateIT.setUp()` (referenced in story), not HTTP client, since IT calls service directly.

4. **Booking side effects verification:** Test should verify `suspendCoach`'s booking cancellations are applied and not undone when the coach status race is closed (part of mutation check narrative).

---

## Completeness & Coverage

| Aspect | Status | Notes |
|--------|--------|-------|
| Bug is genuine | ✅ Yes | TOCTOU race confirmed in code |
| Bug is reachable today | ✅ Yes | Single-instance, no scale threshold |
| Fix pattern exists | ✅ Yes | `suspendCoach` demonstrates exact approach |
| All writers inventoried | ✅ Yes | Six sites checked, two unlocked |
| False leads examined | ✅ Yes | Three candidates ruled out with reasoning |
| Scope boundaries clear | ✅ Yes | Exclusions well-documented |
| Test strategy sound | ✅ Yes | Mirrors established precedent |
| Tasks are actionable | ✅ Yes | Specific line numbers, clear changes |
| Dev notes are helpful | ✅ Yes | References, conventions, patterns cited |

---

## No False Positives Found

✅ All claimed bugs are genuine (not noise).  
✅ All false leads are correctly dismissed (no re-opening risk).  
✅ Fix is minimal and focused (no scope creep).  

---

## Recommendation

**Ready to implement immediately.** The story is well-researched, clearly scoped, and provides a solid foundation for development. The fix is low-risk (mirrors existing patterns), high-confidence (TOCTOU race is real and proven), and addresses a genuine today-reachable bug.

**No blockers or clarifications needed before starting.**
