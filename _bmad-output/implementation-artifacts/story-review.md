# Story Review: Deferred-70

**Status:** Ready for dev; no blocking issues. Minor clarification needed on AC4.

---

## Issues Found

### AC4 – Session Status Transition: Implicit Assumption About Valid States

**Concern Level:** Moderate — not a bug, but an unclear assumption that could cause unexpected behavior.

**Issue:**
The proposed `handleBookingCompleted` listener transitions a session plan to `COMPLETED` whenever a booking completes, using only an idempotency guard: `if (!"COMPLETED".equals(session.getStatus()))`. This logic assumes the only possible states are `DRAFT` (initial) and `COMPLETED` (terminal), and that overwriting any other state to `COMPLETED` is correct.

However, the story doesn't document what all valid `SessionPlan.status` values are or whether a coach can explicitly set a session to states other than `DRAFT`/`COMPLETED`. If a coach manually sets a session to a meaningful state (e.g., `"IN_PROGRESS"`, `"PAUSED"`, or some other domain state), the listener will overwrite that choice when the booking completes.

**Questions:**
1. Are there valid session states other than `DRAFT` and `COMPLETED`?
2. If a coach explicitly sets a session to a non-`COMPLETED` state and then the booking completes, should that explicit choice be overwritten, or should there be a guard to preserve non-terminal states that a coach set?
3. Should the guard be more conservative, e.g., `if ("DRAFT".equals(session.getStatus()))` (transition only from DRAFT), rather than `if (!"COMPLETED"...)` (transition from anything)?

**Recommendation:** Before implementation, clarify with the product owner or check existing domain tests whether sessions have states beyond `DRAFT`/`COMPLETED`, and confirm that overwriting any non-`COMPLETED` state is the intended behavior. If the answer is "yes, there are other states, and we should only auto-transition from DRAFT," change the guard.

---

### AC1 – Self-Booking Query-Param Guard: Edge Case with Null/Missing `selfPlayerId`

**Concern Level:** Low — likely handled by existing auth middleware, but not explicit in the story.

**Issue:**
The fix gates the query-param override behind `!authStore.isPlayer`, but assumes `selfPlayerId.value` is always set when `authStore.isPlayer === true`. If a player somehow reaches `BookingRequestPage` without a resolved self id (e.g., a race condition during auth state initialization), the computed `playerId` could be `undefined` or null, which would likely fail at the backend.

**Confidence:** Low risk in practice—the page should not be reachable without a valid auth context, and the backend will reject anyway. But the story doesn't explicitly document this assumption.

**Recommendation:** No action needed, but add a comment in the code explaining that `selfPlayerId.value` is guaranteed to be set when `authStore.isPlayer === true` (due to auth middleware), or wrap it in a fallback if there's any doubt.

---

## Verified – No Issues

### AC2 – Dead Sort Branch Collapse
✅ **Clean.** The fix correctly identifies and collapses identical branches while preserving the explanatory comment. The comment is actually improved with context about why both branches existed. Testing strategy (existing test suite, no behavioral change) is correct.

### AC3 – Constraint Split (`NOT VALID` + `VALIDATE CONSTRAINT`)
✅ **Clean.** Follows established precedent (`V105`/`V106`). The migration strategy is sound:
- `DROP` + re-`ADD ... NOT VALID` avoids extended locks on already-existing constraints
- Separate `VALIDATE` in a second migration (separate transaction) mirrors successful prior patterns
- The assumption that existing rows already satisfy the constraint is valid (since the constraint was already applied in `V93`)
- Handles the "already-applied migration cannot be edited" constraint correctly

Testing strategy is appropriate: existing tests should pass unchanged since constraint semantics are identical.

### AC5 – Ledger Hygiene
✅ **Clean.** All three updates are justified:
1. **Gallery-reorder closure:** Verified via grep that `displayOrder` is read-only everywhere; no reorder feature exists in the codebase. Appropriate to close with a note for when/if the feature is built.
2. **D8 cross-reference closure:** Correctly identifies that the decision was already made in `skillars-deferred-63` (one day after D8's "still open" tag); appending the cross-reference removes the stale framing without duplicating reasoning.
3. **`getPublicProfile` re-evaluation:** Correctly distinguishes "8 round-trips for one coach" from "N+1 scaling problem"; re-filing with corrected framing (not closed, but marked as speculative rather than urgent) is appropriate.

---

## Corner Cases – Handled Correctly

**AC1:** Correctly handles the case where a stray `?playerId=` query param from browser history or a copy-pasted link is present.

**AC4:** Correctly handles QUICK-mode bookings with no associated session plan via `ifPresentOrElse`.

**AC4 Idempotency:** The check `!"COMPLETED"` handles re-delivered events gracefully, avoiding unnecessary writes.

**AC3 Migration:** Correctly handles the case where the constraint already exists in every environment (because `V93` was already applied).

---

## Summary

**No blocking issues.** The story is mechanically sound and follows established patterns throughout. AC4 has one moderate-concern assumption (session states) that should be clarified before dev, but is not a bug — it's a product-logic question. All other ACs are clean.

**Recommendation:** Clear AC4 assumption with product owner or existing domain tests before starting implementation. Proceed with dev once clarified.
