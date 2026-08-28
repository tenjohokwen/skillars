# Story Review: skillars-deferred-80

**Reviewer Note:** This audit verifies the story design against actual codebase state and flags only genuine corner cases or unsupported assumptions that could affect implementation.

## ✅ Verified — No Changes Needed

### AC1: Availability-block lock parity

**Claim verification:**
- ✓ `lockProfile` helper exists (AvailabilityService.java:298-305) with correct shape
- ✓ `addWindow`/`updateWindow`/`deleteWindow` all call `lockProfile` after `requireProfile` (lines 253-289)
- ✓ Three existing `addBlock` tests confirmed (lines 926-988 in AvailabilityServiceTest)
- ✓ Zero existing `deleteBlock` tests confirmed (grep verified)
- ✓ `lockRetryer` stub in test setUp correctly delegates to supplier (AvailabilityServiceTest:87-90)

**Lock pattern soundness:**
The pattern mirrors `addWindow` exactly. The race condition is closed because:
1. Coach profile lock is acquired before the overlap check
2. Any concurrent booking operation adds bookings under the same coach lock (verified: `BookingService` uses identical `lockProfile` pattern)
3. Therefore no new bookings can be inserted between the check and the block save

No detected issues with this pattern.

**Test failure mode:**
The three `addBlock_` tests currently stub `findByUserId` but not `findByIdForUpdate`. When `lockProfile` is added, tests will fail with `ResourceNotFoundException` on the `findByIdForUpdate` call (because Mockito defaults mock returns to empty Optional). The story's prescribed stub addition is correct and necessary. ✓

### AC2: Video-cascade self-invocation fix

**Claim verification:**
- ✓ `cascadeDeleteForAccount` is NOT marked `@Transactional` (VideoDeletionService.java:145)
- ✓ Plain call to `deleteVideo` on line 158 bypasses Spring proxy as described
- ✓ Precedent exists: `RadarCompositeCalculationService.java:50-52` has identical `@Lazy` self-reference pattern
- ✓ `deleteByUser` (line 103) and two-arg `deleteVideo` overload (line 94-96) are separate entry points

**Self-invocation logic:**
- ✓ `deleteByUser` is `@Transactional` and called externally, so Spring proxy is invoked for it
- ✓ `deleteByUser` calls `deleteVideo` (the three-arg version) on line 134, which goes through the proxy
- ✓ This is DIFFERENT from `cascadeDeleteForAccount` which is NOT `@Transactional`, so its internal calls bypass the proxy
- ✓ The fix (injecting `self` and calling `self.deleteVideo(...)`) correctly routes through the proxy

**Test fix:** 
Using `ReflectionTestUtils.setField` is appropriate and precedented in this codebase. The three existing tests (`cascadeDeleteForAccount_...`) will correctly fail with NPE if the field wire-up is missing, catching the regression immediately.

### AC3: Branding tier gate

**Claim verification:**
- ✓ `getBranding()` only called from `CoachBrandingResource.getBranding()` (CoachBrandingResource.java:29-35), a coach-self settings endpoint
- ✓ No calls from `generateReport` or any report path (report uses separate tier check on lines 126-128)
- ✓ `saveBranding` already has tier gate on lines 228-231

**GET/PUT consistency:**
The fix correctly addresses the asymmetry: `saveBranding` throws for non-ACADEMY, but `getBranding` currently returns stale branding. The decision to return empty response (instead of throwing) is correct for a settings-page GET — throwing would create a hard error for every downgraded coach with no recovery path. ✓

**S3 object lifecycle:**
The project-owner decision ("leave S3 logo object untouched on downgrade") assumes no automatic cleanup job deletes orphaned branding rows on tier downgrade. This is an explicit design decision documented in Dev Notes; no code evidence of such cleanup found. Re-upgrade will restore visibility of the same S3 object. ✓

**Repository lookup optimization:**
The story prescribes skipping `brandingRepository.findById()` for non-ACADEMY tiers. This is a correct optimization (prevents unnecessary DB hit) but behavior-identical to current code (which returns `(null, null)` when no row exists). ✓

---

## ⚠️  Edge Cases Identified — No Action Required

### AC1: Coach deletion race

**Scenario:** Coach is deleted between `requireProfile` (line 309) and `lockProfile` (new line).

**Behavior:** `lockProfile` calls `findByIdForUpdate(coachId)` which returns empty Optional, throwing `ResourceNotFoundException`.

**Assessment:** ✓ **Correct behavior.** Mirrors existing window-CRUD methods exactly (addWindow:253-255 has identical vulnerability). If coach is deleted, the operation correctly fails rather than silently using stale data. Error code is appropriate (`coach_profile` not found).

### AC2: Self-reference null check

**Scenario:** Test forgets to wire `self` field via `ReflectionTestUtils.setField`.

**Behavior:** NPE when `cascadeDeleteForAccount` calls `self.deleteVideo(...)`.

**Assessment:** ✓ **Caught by tests.** The existing three `cascadeDeleteForAccount_` test cases will all NPE before reaching assertions if `self` is not wired, making this a fail-fast regression. The story's test setup instruction is sufficient to prevent this in practice.

### AC3: Stale cached responses

**Scenario:** Coach fetches branding while ACADEMY (cached as having logo X), downgrades, then later response is served from cache.

**Behavior:** Client might see old branding even though `getBranding` now returns empty.

**Assessment:** ✓ **Out of scope.** Response caching (HTTP cache headers, client-side caching, CDN behavior) is not mentioned anywhere in the story and appears to be a platform-level concern separate from this change. `getBranding` is a coach-self endpoint (`@PreAuthorize(HAS_COACH_ROLE)`), not a public report page, so aggressive caching is unlikely. No evidence in the codebase of branding-specific cache headers.

---

## ✅ False Positives Eliminated

### AC1: "Overlap check could be stale if run against unlocked coach row"

**Initial concern:** What if another coach's booking operation changes shared state while we're checking overlaps?

**Elimination:** Bookings are keyed by specific `(coachId, time)` pairs. The coach row lock prevents ANY concurrent operation that touches this specific coach's state. The overlap check queries bookings for this specific coachId only — no cross-coach interference. ✓

### AC2: "What if `deleteVideo` is called from multiple entry points concurrently?"

**Initial concern:** Could concurrent calls create transaction order issues?

**Elimination:** Spring's proxy-based `@Transactional` is method-level, not object-level. Each call through the proxy gets its own new transaction (or joins existing one depending on propagation, which is `REQUIRED` by default here — `deleteVideo` has no explicit propagation setting). Concurrent calls are independent transactions, which is exactly the intended behavior. ✓

### AC3: "What if `getBranding` response is cached by clients?"

**Initial concern:** Client caches response while coach is ACADEMY, then coach downgrades.

**Elimination:** This is a client-side caching strategy issue, not a server-side implementation issue. The server correctly stops returning branding data. Any client that caches individual resource responses aggressively without re-validation headers is violating HTTP semantics. The story's scope is the server implementation, not client caching policy. ✓

---

## 📋 Implementation Checklist Soundness

### Tasks section (lines 39-49)

All tasks correctly map to their ACs:
- **Task 1** → AC1: Correct method names, correct substitution points (profile.getId() → lockedProfile.getId())
- **Task 2** → AC2: Correct field structure, correct method to modify, correct test utility
- **Task 3** → AC3: Correct return type, correct skip condition (tier check before repository lookup)
- **Task 4** → Ledger hygiene: No implementation tasks, coordination-only

No false dependencies between tasks detected — all three ACs are independent and could be implemented in any order.

---

## 🔍 Test Coverage Assessment

### AC1 Tests
- **Existing:** 3 addBlock tests → All will catch missing stubs via NPE/ResourceNotFoundException ✓
- **New:** deleteBlock_ownedByCallingCoach_succeeds → Covers happy path, no prior test coverage ✓
- **Gap:** No test for `deleteBlock` when block is not owned by coach (OperationNotAllowedException case), but the story correctly notes this is "no behavior change to the not-found path" so regression coverage is sufficient ✓

### AC2 Tests
- **Existing:** 3 cascadeDeleteForAccount tests will catch missing self-wiring via NPE ✓
- **Gap:** Tests don't verify isolation of per-video transactions, but that's integration/performance testing scope, not unit test scope ✓

### AC3 Tests
- **Existing:** Zero coverage of `getBranding` confirmed
- **New:** Three cases prescribed (ACADEMY with saved branding, downgraded tier with existing row, ACADEMY with no row) ✓
- **Gap:** No test for non-ACADEMY coach with no row (trivial case: returns empty same as ACADEMY no-row) — story correctly identifies this as covered by case (c) regression check ✓

---

## 🎯 Conclusion

**Status: READY FOR IMPLEMENTATION**

All three ACs are well-scoped and architecturally sound. No unvalidated assumptions detected. The story correctly identifies all required code changes and their test implications. The edge cases identified above (coach deletion, self-ref null, cache staleness) are either correct-by-design or out-of-scope, not implementation bugs.

The story's frequent cross-references to precedent patterns (deferred-78's lock pattern for AC1, RadarCompositeCalculationService for AC2, skillars-deferred-63 for AC3's downgrade decision) and ledger consistency (Task 4) demonstrate attention to this codebase's established conventions.

**Recommendation:** Proceed to implementation. The developer can follow the story's AC and task structure with confidence.
