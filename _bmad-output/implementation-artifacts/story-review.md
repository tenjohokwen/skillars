# Story Review: skillars-deferred-79

**Story**: Coach availability-block booking enforcement  
**Status**: ready-for-dev  
**Review Date**: 2026-08-28  
**Reviewer**: Senior Dev Audit

---

## Summary

Story is **sound overall**. AC1 and AC2 both correctly identify the five call sites and specify precise locations. Project-owner decisions are well-documented. Story acknowledges known TOCTOU limitations consistently with codebase precedent. No critical gaps found.

**Minor gaps below require dev clarification or awareness during implementation; no blockers.**

---

## AC1: Block Enforcement at Booking-Write Paths

### Verified Correct
✓ Five call sites correctly identified (createBookingRequest, validateSlotDurationAndAvailability×2, validateRescheduleProposal, acceptRescheduleShared, duplicateNextWeek)  
✓ Correct insertion point specified for each (after window check, before overlap check)  
✓ `isSlotBlocked` mirror-design matches `isSlotWithinAvailabilityWindow` visibility/usage pattern  
✓ Repository query already exists; no new DB migration needed  
✓ TOCTOU window explicitly acknowledged per codebase precedent  
✓ `BookingDuplicationService` correctly identified as needing the check via delegation (no new dependency)  
✓ Test cases specify both rejection AND non-regression (slot outside any block still succeeds)

### Corner Cases / Clarifications Needed

**1. Error precedence not specified**
- Order of checks is: window → block → overlap. If a slot fails both window AND block, which error fires? Story doesn't specify.
- **Dev action**: Verify the implementation order matches spec and produces the intended error. Probably OK (window check throws first), but confirm.

**2. Batch pre-commit re-check + block additions between initial/commit**
- `BookingBatchService` validates at start, then again pre-commit. What if coach adds a block between the two?
- Pre-commit re-check (via same `validateSlotDurationAndAvailability` call) will catch it ✓, but user sees batch accepted then denied. Story doesn't discuss UX messaging for this scenario.
- **Dev action**: Ensure error message from pre-commit re-check is actionable ("try again" not "invalid input"). Probably handled by existing pattern, but worth verifying.

**3. Reschedule proposal + block added before accept**
- Coach adds block after parent proposes reschedule but before parent accepts it.
- Accept-time re-check catches it ✓. Good flow.
- **However**: Parent's UI was shown a valid proposal; they return to accept and now get `SLOT_BLOCKED_BY_COACH`. No mention of how UI handles this or whether a "re-proposal" flow exists.
- **Dev action**: Not in scope of this story (UI behavior), but implementation should assume accept rejection is possible and error messaging is clear.

**4. Time boundary precision not specified**
- Does a booking from 3:00-4:00 overlap a block from 4:00-5:00? (i.e., are boundaries inclusive or exclusive at endpoints?)
- Story defers to existing `findOverlappingBookings` and `findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore` semantics.
- **Dev action**: Verify these two queries use identical overlap logic. If they differ, this is a bug. Should be the same, but worth a grep-check.

**5. `BookingDuplicationService.duplicateNextWeek` check happens per occurrence?**
- Story says check goes "right after the `SLOT_OUTSIDE_AVAILABILITY` throw". Looking at the AC detail: "`duplicateNextWeek`, which already mirrors `createBookingRequest`'s window-check-then-overlap-check shape exactly".
- This implies the check happens within the loop that creates each week's copies. Correct?
- **Dev action**: Verify the check is inside the loop per-occurrence, not outside-loop (once per operation). Story language suggests per-occurrence, but worth double-checking the code structure.

---

## AC2: Reject `addBlock` on Booking Overlap

### Verified Correct
✓ Uses `findOverlappingBookings` with `BookingService.ACTIVE_SLOT_STATUSES`—same pattern AC1 uses elsewhere  
✓ Test specifies both rejection (overlap blocks) AND non-regression (free slot succeeds)  
✓ Test explicitly specifies terminal-status bookings do NOT block (e.g., `CANCELLED_PARENT`)  
✓ No new locking introduced (consistent with AC1's scope limits)  
✓ Distinct error code (`BLOCK_OVERLAPS_BOOKING`) separate from parent-facing error ✓

### Corner Cases / Clarifications Needed

**1. Which booking statuses count as "active"?**
- Story defers to `ACTIVE_SLOT_STATUSES` constant. Presumably this means non-terminal statuses (e.g., UPCOMING, CONFIRMED, maybe PENDING_PAYMENT).
- **Dev action**: On first implementation, grep `BookingService.ACTIVE_SLOT_STATUSES` to confirm the constant definition. If it includes intermediate states like PENDING_PAYMENT or RESERVED, good. If not, that's a latent gap (coach could block a slot with a payment-pending booking).
- **Critical**: Story's test case (c) is the right guard here—make sure the test actually runs with the real `ACTIVE_SLOT_STATUSES` constant, not a hardcoded mock list.

**2. Coach's availability window vs block relationship**
- No requirement that a block must fall *within* a coach's availability window. Coach can block time they don't claim to be available.
- Probably intentional (blocks are explicit opt-outs), but story doesn't clarify.
- **Dev action**: No change needed, but be aware that a coach can now have blocks outside their announced availability windows. This is fine (more permissive for coaches).

**3. Multiple bookings in the same slot: error messaging**
- Story specifies copy: "You already have a booking during this time — cancel or reschedule it first if you need to block this time out."
- What if the coach has 3 bookings in that range (multiple kids, overlapping classes)? Error lists just one? All three?
- **Dev action**: Probably the generic message is sufficient (coach should see *which* booking overlaps in the UI once they dismiss the error and retry). Story doesn't require itemization; keep it simple.

---

## Cross-AC Issues

**1. Race: Block deleted right after booking's block-check but before booking save**
- Booking passes block check, but block is deleted before booking commits.
- Booking succeeds on now-unblocked slot. Correct per TOCTOU acceptance.
- ✓ Story acknowledges this window exists; no false assumption here.

**2. Race: Booking added after block-check but before block saves**
- Coach runs `addBlock` check against bookings, check passes (no overlap), but booking is inserted before block saves.
- New block and new booking now overlap in DB. Broken invariant.
- Story says: "not requested by project-owner decision, which is about the business rule (reject up front), not about closing every race around it; out of scope."
- ✓ Accepted. Not a gap, but devs should be aware this is a known limitation (same class as everywhere else in codebase).

**3. Backward compatibility: Existing overlaps pre-ship**
- If a block overlaps a booking and both existed before this story ships, they remain.
- Story: "not backfilled or audited... forward-only, consistent with this codebase's established convention."
- ✓ Correct and acknowledged.

---

## Test Coverage Gaps (Minor)

**1. No test for overlapping blocks**
- What if two coaches try to add blocks in the same time slot? Can they both block the same calendar slot?
- **Not in AC1/AC2 scope** (each AC is single-coach), but might be worth a follow-up story if blocks should be global across all coaches (probably not, but confirm with PO).
- **Dev action**: No change needed for this story, but worth noting for future.

**2. No test for reschedule + concurrent block deletion**
- Proposal check passes (slot free), block is deleted, accept-time re-check also passes. ✓
- But what if block is *added* between proposal and accept? Re-check catches it ✓.
- **Dev action**: Verify the accept-time re-check is actually a *fresh* query of the block table, not a cached copy from proposal-time. Story says "separate method", so should be fresh. Confirm code doesn't cache.

**3. Batch pre-commit boundary**
- Story says `validateSlotDurationAndAvailability` is called twice (initial + pre-commit).
- Test should verify BOTH calls include the block check, or it's dead code at one call site.
- **Dev action**: When writing test, explicitly verify block-check error can fire at pre-commit stage (not just initial). Current story test spec doesn't explicitly require this; add it if missing.

---

## Implementation Checklists

### AC1 Implementation Order (Critical)
1. Add `isSlotBlocked` method to `BookingService` + inject repository into three service classes ✓
2. Wire check into all FIVE call sites in exact order: window→block→overlap ⚠️ **verify each call site**
3. Add error enum + i18n keys (7 files) ⚠️ **verify all 7 files touched, not 4 or 6**
4. Test all five sites + non-regression ⚠️ **verify `BookingDuplicationService` test exists**

### AC2 Implementation Order (Critical)
1. Add query to `addBlock` method ✓
2. Add error enum + i18n keys (7 files, same batch as AC1) ⚠️ **don't forget frontend i18n**
3. Test overlap rejection + non-regression + terminal-status-passes ⚠️ **test all three cases**

---

## Known Limitations (Accepted per Story)

- ✓ TOCTOU windows exist (block added/deleted concurrent with booking checks)
- ✓ No locking on `addBlock`/`deleteBlock`
- ✓ No retroactive backfill of existing overlapping pairs
- ✓ No coach-row lock for block CRUD (flagged for future story)

---

## Final Verdict

**No blockers. Story is implementable as written.**

Story demonstrates strong awareness of existing patterns (`ACTIVE_SLOT_STATUSES`, repository queries, error-code conventions, lock usage, TOCTOU acceptance). Five call sites are correctly identified with precise line numbers. AC2 correctly defers to existing booking-overlap logic.

**One implementation risk**: Ensure all FIVE call sites get the block check, and all SEVEN locale files are updated. Story is explicit about this, but implementation can slip. Add a pre-commit checklist step.

**Critical dev verifications before shipping**:
1. Confirm `ACTIVE_SLOT_STATUSES` includes the intended booking statuses (not just terminal-rejection states).
2. Verify both overlap-query methods (`findOverlappingBookings` and `findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore`) use identical boundary logic.
3. Double-check all SEVEN locale files are touched (4 backend + 3 frontend), not a subset.
4. Ensure test coverage includes terminal-status non-blocking behavior (AC2 case c).
