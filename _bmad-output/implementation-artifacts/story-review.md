# Story Deferred-75 Audit: Session-Template Guards, Drill-Upload Concurrency, Homework Fixes & Library-Type Rename

**Audit Date:** 2026-08-27  
**Auditor:** Senior code review  
**Status:** Ready for dev with one critical assumption (AC4) to verify before deployment

---

## Summary

The story is well-researched, thoroughly specified, and technically sound across all 12 ACs. The re-verification pass that preceded AC design correctly identified issues the ledger had missed or misframed. **One critical frontend assumption in AC4 requires explicit verification before deployment.** All other ACs have appropriate guards or acknowledge contingencies. No false positives found — issues correctly identified as bugs rather than design decisions.

---

## AC1: SessionTemplateService Guards ✓

**Status:** Well-specified, no issues.

- **Guard logic:** Adding ARCHIVED check to `deleteTemplate` mirrors existing checks in `renameTemplate`/`deployTemplate`. Correct.
- **Defensive copy:** New ArrayList assignment prevents shared-reference footgun. Correctly identified as latent (no current mutation path, but real if future code changes).
- **Test:** `deleteTemplate_alreadyArchived_returns403` appropriately mirrors existing patterns.
- **No corner cases missed.** ✓

---

## AC2: SessionPlanService Booking Terminal-Status Guard ✓

**Status:** Well-specified, contingency appropriately noted.

- **Guard logic:** Reuses `BookingStateMachine.isTerminal()` rather than hand-listing statuses. Correct — extends guard beyond just `COMPLETED` to all terminal states.
- **Ordering:** Story explicitly notes putting booking check *after* session check to keep existing tests passing. Intentional, not a bug. Accepted trade-off.
- **Corner case — booking not found:** AC shows `.orElseThrow(... "Booking not found")`, assumes `bookingQueryService.getBookingSnapshot()` returns `Optional`. **Verify this method signature exists.** (Low risk — .orElseThrow() syntax strongly implies it.)
- **Test shape:** Correctly specifies checking `CANCELLED_PARENT` terminal status. Appropriately calls for verifying it's terminal via `BookingStateMachineTest` first.
- **No missed flows.** ✓

---

## AC3: Existing Test Fix ✓

**Status:** Mechanical fix, no issues.

- Just fixing assertion shape on existing test and applying it to AC2's new test.
- References correct precedent pattern from `RescheduleResourceIT.java`.
- **No corner cases.** ✓

---

## AC4: DrillCard.vue Context Gating — ⚠️ **CRITICAL ASSUMPTION**

**Status:** Design is correct, but one assumption MUST be verified.

- **Current:** Tag-edit block only gated on `libraryType === 'COACH'`.
- **Fix:** Add `context !== 'locker-room'` check.
- **Proposed conditional:** `v-if="drill.libraryType === 'PRIVATE' && context !== 'locker-room'"`

### **CRITICAL ISSUE:**

If the `context` prop is **not always passed** to DrillCard (or if it can be `null`/`undefined`), then:
- `undefined !== 'locker-room'` evaluates to `true`
- Tag-edit UI renders even on locker-room page
- AC4 fails silently

**Mitigation required before deployment:**

Story states "DrillCard is used from four places (confirmed by grep)" with context values. **This needs explicit verification** that:
1. All 4 usages pass the context prop (manual code-read of each usage)
2. None are conditional or have fallback paths omitting context
3. Context is never `null` or `undefined`

**Safer alternative fix** (if context is optional):
```vue
<div v-if="drill.libraryType === 'PRIVATE' && context !== 'locker-room' && context" class="drill-card__tags q-mt-sm">
```
or (if being explicit):
```vue
<div v-if="drill.libraryType === 'PRIVATE' && (context === 'library' || context === 'session-builder')" class="drill-card__tags q-mt-sm">
```

**Recommendation:** Before writing code, inspect each of the 4 usages to confirm context is always passed. If any usage could omit it, add explicit null-check to conditional.

---

## AC5: DrillUploadService Locking — ✓

**Status:** Well-designed, no issues.

- **Lock pattern:** Correctly mirrors `CoachProfileService` (findByIdForUpdate + PessimisticLockRetryer + entityManager.refresh).
- **Lock scope:** Locking only the Drill row is correct — serializes all callers on that drill's video state.
- **Provider call inside lock:** Story correctly notes `videoService.initializeUpload()` must stay inside locked region so next caller sees committed write. This prevents the race. ✓
- **Double-publish race:** AC correctly identifies the `existsByVideoId() → publish deletion` race and fixes it with same lock. ✓
- **Comment-only follow-up:** Adding comment to `CoachSubscriptionTier` noting enum order is load-bearing — proportionate fix.
- **Retry logic:** `PessimisticLockRetryer` has built-in bounded retry with SAVEPOINT rollback. Handles conflict/prolonged contention. ✓
- **Test structure:** Correctly references `SessionPackPurchaseLockContentionIT` as structural precedent.
- **No missed flows.** ✓

---

## AC6: HomeworkAssignmentService Batching & Size Guard — ✓

**Status:** Well-specified, contingency appropriately noted.

- **N+1 fix:** Batch query replaces N individual `hasActivePack(coachId)` calls with 1 query returning subset of coachIds with active packs. Correct approach.
- **Query correctness contingency:** Story appropriately calls for re-verifying batch query conditions against `findActivePacks()` before writing. This is **dev responsibility** — must match exact same filters. **Mark as verification step.**
- **Size guard:** `handleBookingCompleted` now truncates at 2 and logs WARN. Appropriate defense-in-depth for async path.
- **Truncation vs. rejection:** Correctly chooses truncation (defense) over rejection for async listener (no caller to return error to).
- **Testing:** Batching test verifies query count doesn't scale. Size-limit test verifies only 2 assignments created. Appropriate.
- **No missed flows.** ✓

---

## AC7: homework_assignments.pack_id FK — ✓

**Status:** Well-designed, contingency noted.

- **Verification:** Story correctly verified column points at real, live `payment.session_pack_purchases.purchase_id`. Not dead or orphaned. ✓
- **Migration safety:** Orphan-clear before FK addition. Defensive and correct.
- **ON DELETE SET NULL:** Appropriate choice (column already nullable).
- **Precedent:** Mirrors existing cross-schema FK from `booking.bookings → payment.session_pack_purchases`.
- **Testing:** FK-violation IT correctly specified.
- **No missed flows.** ✓

---

## AC8: Rename 'COACH' → 'PRIVATE' — ✓

**Status:** Well-scoped, constraint-name assumption must be verified.

- **Scope verification:** Targeted grep sweep correctly identifies all locations (8 prod code, multiple test files).
- **Constraint assumption:** Story assumes `drills_library_type_check` is auto-generated name for single-column CHECK. **Contingency noted:** failure is loud, not silent. Acceptable.
- **Dev step before migration:** Run `\d session.drills` in psql to confirm auto-generated name. **Mark as verification step.**
- **Migration structure:** Correctly drops and re-adds both constraints with 'PRIVATE' value. ✓
- **Enum consistency:** Code patterns show hardcoded strings, not enum. Assuming String field. **Verify there's no Java enum this migration missed.** (Low risk.)
- **Test sweep:** Running full `session` package tests confirms sweep is complete. ✓
- **No false assumptions.** ✓

---

## AC9: Drill Video URL Refresh on Error — ✓

**Status:** Well-designed, contingency appropriately noted.

- **Error handler:** Adding `@error` handler on `<video>` elements is correct.
- **Refetch guard:** `videoRetried` ref prevents loops. Each component instance gets one retry. Correct.
- **One retry per mount:** If component unmounts/remounts, guard resets — acceptable (still bounded, intentional).
- **Store field assumption:** Story explicitly calls out verifying `sessionStore.currentLibrary` exists. **Mark as verification step.** ✓
- **Scope:** 1 element in DrillCard.vue + 3 in DrillDetailPanel.vue = 4 total. Correct.
- **Fallback behavior:** If refetch also fails, video stays broken but no loop. Acceptable.
- **Testing:** Manual verification (no frontend test runner) — confirm exactly one refetch fires. Appropriate.
- **No missed flows.** ✓

---

## AC10: Deterministic Seed Drill IDs — ✓

**Status:** Appropriate risk acceptance and safe guard.

- **Problem:** V39 uses `gen_random_uuid()` for 20 platform drills. Different id per environment.
- **Risk:** Reassigning ids orphans any pre-existing references.
- **Mitigation:** Guard aborts migration (fails loudly) if any references found. If guard fires, environment needs hand-written follow-up.
- **Accepted risk:** Project owner was warned and chose to accept. Appropriate escalation.
- **Guard logic:** Checks both `drill_video_refs` and `homework_assignments`. Covers known foreign-key tables. ✓
- **UUID format:** `00000000-0000-4000-8000-000000000001` through `...000000000020` are valid v4 UUIDs. Extremely unlikely to conflict. ✓
- **trans_key verification:** Story calls for re-verifying all 20 against live V39 before running. **Mark as verification step.**
- **Idempotency:** Second application of same UPDATEs is a no-op. ✓
- **Testing:** Post-migration assertions verify all 20 drills have fixed ids. ✓
- **No false assumptions.** ✓

---

## AC11: developmentFocus Validation — ✓

**Status:** Well-specified, source-of-truth location should be documented.

- **Real issue:** `computeFocusScore()` is not a stub — it's real 8-code switch with formulas. Story correctly notes ledger framing was wrong.
- **Sync problem:** Frontend has same 8 codes hardcoded. Nothing enforces sync. Backend accepts free-form strings.
- **Fix approach:** Add validator at request boundary restricting to known codes. Correct place.
- **Source of truth:** Story says keep in "one place" but doesn't specify where. **Recommendation:** Document location (enum constant? service method? validator class?) clearly so future changes stay synced.
- **Validator shape:** Story allows `@Pattern` or dedicated `@ValidFocusCode` annotation. Either acceptable; pick one.
- **Defense-in-depth:** WARN log in switch default for any code bypassing validation. Correct approach.
- **Known codes:** 8 total. Matches frontend selector. ✓
- **Old sessions:** If invalid focus code exists, read is OK, update logs WARN and scores 0. Acceptable backward-compat.
- **Testing:** Test rejecting unrecognized code (400) and accepting all 8 known codes (regression). ✓
- **No missed flows.** ✓

---

## AC12: Ledger Hygiene — ✓

**Status:** Procedural/mechanical, no issues.

- 14 ledger items closed with citations.
- Story corrects false framings correctly (e.g., "already fixed unannotated", "premise was wrong").
- One item (W3 from `skillars-4-3`) correctly kept open — mid-request crash leaving orphaned asset is separate gap, not fixed by locking.
- Distinction correctly made. ✓
- **No missed items.** ✓

---

## Verification Checklist for Dev Before Implementation

Add these verification steps to the AC task list:

### **Before Coding:**

1. **AC4 (CRITICAL):** Inspect all 4 usages of DrillCard and confirm every usage passes the `context` prop. If any could be undefined, add explicit null-check to v-if conditional.

2. **AC2:** Verify `BookingStateMachine.isTerminal()` method exists and is already a `@Component`.

3. **AC6:** Read `hasActivePack()` implementation and underlying `findActivePacks()` query. Copy exact filter conditions into new batch query.

4. **AC8:** Before running migration, verify constraint names:
   - Run `\d session.drills` in psql to confirm `drills_library_type_check` is auto-generated name
   - If different, update migration accordingly

5. **AC8:** Confirm there is no Java enum `LibraryType` anywhere. Story assumes String fields.

6. **AC9:** Inspect `src/frontend/src/stores/session.store.js` and confirm it has `currentLibrary` ref or equivalent. If not, add it as part of AC9.

7. **AC10:** Before writing migration, verify all 20 trans_key values by reading live `V39__session_foundation_20_drills.sql` and comparing against the 20 UPDATEs.

8. **AC11:** Decide and document where to keep single source of truth for 8 known focus codes.

### **Before Running Tests:**

9. **AC2:** Verify in `BookingStateMachineTest` that `CANCELLED_PARENT` is indeed terminal (not false assumption).

10. **Migrations (AC7, AC8, AC10):** Confirm no new migrations landed since V108. Run `ls src/main/resources/db/migration/ | sort -V | tail -5` and renumber V109/V110/V111 upward if needed.

---

## Summary of Findings

| AC | Status | Issues | Contingencies |
|----|--------|--------|---|
| AC1 | ✓ | None | None |
| AC2 | ✓ | None | Verify BookingQueryService.getBookingSnapshot() returns Optional |
| AC3 | ✓ | None | None |
| AC4 | ⚠️ **CRITICAL** | **context prop may be undefined** | **Verify all 4 usages pass context** |
| AC5 | ✓ | None | None |
| AC6 | ✓ | None | Verify batch query matches hasActivePack() filters |
| AC7 | ✓ | None | None |
| AC8 | ✓ | None | Verify drills_library_type_check constraint name |
| AC9 | ✓ | None | Verify currentLibrary exists on store (or add it) |
| AC10 | ✓ | None | Verify all 20 trans_key values against live V39 |
| AC11 | ✓ | Minor | Document single source of truth for focus codes |
| AC12 | ✓ | None | None |

---

## Conclusion

**The story is well-written, thoroughly researched, and technically sound.** The one critical assumption (AC4 context prop) must be verified before deployment. All other contingencies are appropriately noted and manageable. The story correctly identifies real bugs and corner cases — no false positives found.

**Recommendation: Proceed to dev with the 10 verification steps above tracked as part of implementation.**
