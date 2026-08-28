# Story Review: skillars-deferred-81

**Reviewer**: Senior Developer Audit  
**Date**: 2026-08-28  
**Status**: Ready for dev with flagged assumptions to verify during implementation

## Critical Load-Bearing Assumptions (verify immediately)

### AC4: Session-pack self-booking purchase — `parent_id` reuse assumption
**Severity**: HIGH — whole AC depends on this  
**Assumption**: `session_pack_purchases.parent_id` can be safely repurposed as an opaque payer-id for self-booking players, with no other code path assuming it references a genuine parent.

**What's missing**:
- Story says "Verify no other `session_pack_purchases.parent_id` reader assumes a genuine parent (load-bearing check before the rest of this task)" but doesn't specify the search scope. Dev should grep across:
  - Java: `session_pack_purchases`, payment/notification paths, email templates, admin dashboards
  - Database: Views, stored procedures, audit logs
  - Frontend: Any display logic that treats `parent_id` as a user lookup
- Particularly risky: notification/email send logic that might attempt to fetch user details via `parent_id` assuming it's a real parent, which would break for self-booking players where it's the player's own `userId`

**Verify**: As the first implementation step, run a codebase-wide grep + code inspection. If any code path assumes `parent_id` → parent entity lookup, AC4's framing is invalid.

---

### AC1: N+1 batching — repository method availability assumption
**Severity**: MEDIUM  
**Assumption**: `CoachProfileRepository` has a `findAllById(...)` method, mirroring `PlayerProfileRepository` and `UserRepository`.

**What's missing**:
- Story assumes all three repositories have batch-fetch methods, but only explicitly names `playerProfileRepository.findAllById` and `userRepository.findAllById`
- Doesn't verify `coachProfileRepository.findAllById(...)` exists — AC1 says "batch its per-row coach-name lookup, confirmed identical pattern one line away" but doesn't confirm the repo actually has the method

**Verify**: Check `CoachProfileRepository` source before starting AC1 Task 1. If `findAllById` doesn't exist, either add it or use a fallback (e.g., build the map manually from a `findByIdIn` or filter a full fetch).

---

### AC3: Lock order deadlock safety assumption
**Severity**: MEDIUM  
**Assumption**: Drill → Video lock order is safe because these two methods don't call each other and have no mutual callers that would violate the order.

**What's missing**:
- Story mandates "Lock-acquisition order: acquire the existing Drill-row lock first, then... Video-row lock" and says "This order must be identical in both initiateUpload and deleteVideo to avoid introducing a *new* deadlock risk"
- Doesn't verify: are there any **other callers** of these two methods that might acquire locks in a different order? E.g., a caller that calls both `deleteVideo(drill1)` and `initiateUpload(drill2)` might accidentally reverse the order if not careful.

**Verify**: Search for all call sites of `DrillUploadService.initiateUpload` and `deleteVideo`. If any are co-called with locks held in the opposite order, the AC's lock order could introduce deadlock, not prevent it.

---

## High-Confidence Issues (not false positives)

### AC2: Incomplete handler discovery risk
**Severity**: MEDIUM  
**Issue**: Story identifies four call sites for video-error notifications but then says "confirm during implementation whether their emitted event is caught by one of the three page-level handlers above, or reaches a *different* handler". This suggests incomplete discovery.

**What's missing**:
- `DrillSuggestionPanel.vue` and `DrillDetailPanel.vue` both re-emit `video-error` upward, but the story doesn't guarantee that these re-emitted events bubble to one of the four listed handlers
- If they reach a fifth handler (e.g., in a different page or a shared modal) that's not in the list, that handler needs the same notification or the user gets no feedback in that flow
- Story says "Confirm during implementation... Do not add a second, duplicate notification if a parent handler already fires one" — this is defensive but suggests the story author didn't complete the call-chain audit

**Verify**: Trace the event flow from `DrillDetailPanel.vue:306-327` and `DrillSuggestionPanel.vue:27` up through the component tree to confirm they reach one of the four handlers listed (or find the actual handler if different).

---

### AC3: Concurrency test realism gap
**Severity**: MEDIUM  
**Issue**: Story says "add DrillUploadServiceConcurrencyIT cases proving that `deleteVideo(sourceDrillId)` and `deleteVideo(cloneDrillId)` now serialize" and "The existing same-drill concurrency case skillars-deferred-75 AC5 already added must continue to pass unchanged".

**What's missing**:
- Story doesn't clarify: does the existing test actually exercise the race, or does it just verify the operation succeeds without validating lock scope?
- If the existing test doesn't exercise the race condition (e.g., it runs operations sequentially or with high-level assertions only), adding the new lock might pass trivially without actually serializing
- The test needs to prove that **concurrent** calls to `deleteVideo(sourceDrill)` and `deleteVideo(cloneDrill)` with a shared `videoId` now block each other, and that exactly one publishes `VideoPhysicalDeletionEvent`. This is tricky to verify in a test — how do you assert one thread blocked?

**Verify**: During implementation, ensure the concurrency test either:
- Uses thread inspection (e.g., thread dumps) to verify blocking, or
- Uses careful timing/locking instrumentation to prove serialization, or
- At minimum, proves the expected side effect (exactly one deletion event) over many parallel runs

---

### AC1 + AC4: Transaction isolation assumptions
**Severity**: LOW-MEDIUM  
**Issue**: Neither AC explicitly discusses transaction isolation for the batched/branched operations.

**What's missing**:
- AC1's batch fetch happens before the stream that consumes it. If a booking's player or parent is deleted/modified between the batch call and the stream's consumption, behavior might diverge from current code
  - Current code: fires a single query per row inside the stream, so each row sees the data as-of that query
  - New code: all rows see data as-of the batch call, which happened earlier
  - This is probably fine (atomicity per-row is not guaranteed anyway), but it's a subtle behavior change
- AC4's XOR branch check (`player.getParentId() != null ? ... : ...`) uses the player fetched once, then branches. If the player's parent changes between this check and the actual purchase, the ownership check could be bypassed. Story doesn't address this, but it's probably fine because the operation is atomic within a transaction.

**Verify**: Confirm that both methods run inside a single `@Transactional` method or an explicit transaction, so the fetch and subsequent operations see consistent data.

---

## Medium-Confidence Issues (valid but could be false positives)

### AC2: Video error UX ambiguity
**Severity**: LOW  
**Issue**: Story says to add notify "immediately alongside the existing refetch call — do not remove or change the refetch itself" but the refetch is async and the notify is sync.

**What's missing**:
- User gets immediate toast feedback (good), but the broken video element persists on screen until refetch succeeds or fails (could be seconds/minutes)
- Story doesn't clarify whether the toast should include a timeout or persist until the video loads. If the toast auto-dismisses in 3 seconds but the refetch takes 5 seconds, user loses the context.
- Story mentions "a coach or player sees a clear signal when a video genuinely fails rather than staring at a broken player" — but a toast that auto-dismisses might not constitute "clear signal" for long-running refetches

**Verify**: During manual testing, check the toast lifetime against refetch timing. If the refetch regularly takes longer than default toast timeout, consider making this toast non-dismissible (sticky) or longer-lived.

---

### AC3: "Not in scope" interpretation risk
**Severity**: LOW  
**Issue**: Story says "Not in scope: a genuinely new video row for a brand-new videoId has no existing Video entity yet at the point initiateUpload would want to lock it... Confirm this ordering assumption against VideoService's actual upload-initiation flow during implementation".

**What's missing**:
- This is defensive language, but it reveals the AC author didn't fully trace the upload pipeline
- If the Video entity is created as part of `initiateUpload`, then the new lock on it would fail (lock on a non-existent row)
- Story says to confirm during implementation, which is fine, but this is a **blocking confirmation** — if the assumption is wrong, the AC needs redesign

**Verify**: Trace `VideoService.initiateUpload` and the upload pipeline end-to-end. Confirm the order: (1) Drill-row lock, (2) fetch existing videoId, (3) Video-row lock (if video exists), (4) upsert operations, (5) Video-row creation (if new).

---

### AC4: Credit wallet exclusion might be incomplete
**Severity**: LOW  
**Issue**: Story says "a self-booking player's pack purchase is card-payment-only... Do not add a player-scoped credit concept as part of this AC" but doesn't verify self-booking players actually have no credit concept.

**What's missing**:
- What if a self-booking player has a `PaymentMethodLedger` or `CreditWallet` row with `playerId` instead of `parentId`? The story doesn't check.
- What if the purchase flow attempts to apply credits and fails? Story should verify this is blocked upstream or handled gracefully.

**Verify**: Grep for `credit` in `SessionPackPaymentService` and its callers. Confirm that self-booking players are not expected to have credits anywhere in the payment path.

---

## Low-Confidence / Minor Issues

### AC1: Test coverage clarity
**Severity**: LOW  
**Issue**: Story says "extend `BookingServiceTest` with one test per touched method (or extend an existing multi-booking test if one already seeds ≥2 bookings)".

**What's missing**:
- Doesn't specify: how many bookings are needed to reliably catch the N+1? If test uses only 2 bookings and the implementation has an off-by-one bug, it might miss it.
- The test should use at least 3-5 bookings with distinct players/parents to make a 1-vs-many call pattern obvious in `times(1)` vs `times(N)` assertions.

**Verify**: When writing tests, use ≥3 bookings per test case to catch off-by-one errors.

---

### AC2: Localization scope
**Severity**: LOW  
**Issue**: Story adds three frontend i18n files but doesn't mention: are there other language packs (Japanese, Spanish, etc.) that should also be updated?

**What's missing**:
- The three files mentioned (en-US, de-DE, fr-FR) might not be exhaustive. Story should either confirm these are the only three or flag that future language packs need the same key.

**Verify**: Check `src/i18n/` directory structure. If only three language files exist, this is fine. If more exist, add the key to all of them.

---

### AC4: Endpoint widening scope ambiguity
**Severity**: LOW  
**Issue**: Story says "confirm during implementation exactly which of this resource's endpoints are pack-purchase-specific... do not blanket-widen the whole resource".

**What's missing**:
- This is reasonable due diligence, but the story should have done it beforehand. If the resource has 10 endpoints and only 2 are purchase-related, the task is 5x smaller than if all 10 need widening.

**Verify**: Read `SessionPackPaymentResource.java` and categorize each `@PreAuthorize(HAS_PARENT_ROLE)` endpoint:
- Purchase-related (widen): `purchasePack`, `getPurchaseHistory`, etc.
- Management-only (leave parent-only): `getPacksForParent`, `updatePackAssignments`, etc.

---

## False Positive / Non-Issues (verified as correct)

### AC1: Fallback behavior parity
Story correctly notes the fallback logic (`Map::getOrDefault("Unknown Player")`) mirrors the current single-row path (`resolvePlayerName`'s `.orElse("Unknown Player")`). ✓ Correctly scoped.

### AC3: Same-drill case regression
Story correctly notes existing `skillars-deferred-75` AC5 test must pass. Adding a broader lock should not break this — only widen the lock scope, not change it. ✓ Valid assumption.

### AC4: XOR branch precedent
Story correctly cites existing `BookingService.createBookingRequest` and `BookingBatchService.createBatch` as precedents for the ownership XOR pattern. Direct inspection would confirm these already use the pattern correctly. ✓ Valid precedent reuse.

---

## Summary of Verification Tasks (before starting implementation)

**Blocking (stop and redesign if false)**:
1. AC4: Grep `session_pack_purchases.parent_id` across all readers — no code assumes genuine parent relationship
2. AC3: Trace upload pipeline — Video row exists before or immediately after Drill lock acquisition
3. AC1: Verify `CoachProfileRepository.findAllById(...)` exists

**High Priority (adjust approach if assumptions differ)**:
4. AC3: Search all call sites of `initiateUpload`/`deleteVideo` — confirm no mutual caller violates lock order
5. AC2: Trace `video-error` event flow from re-emitting components — confirm all handlers are in the listed four

**Medium Priority (inform test strategy)**:
6. AC4: Categorize `SessionPackPaymentResource` endpoints — determine which ones need widening
7. AC2: Check for non-English locale files beyond the three mentioned
8. AC1: Verify transaction isolation (all operations in same `@Transactional`)

**Implementation Verification (during dev)**:
9. AC2: Test toast lifetime vs. refetch timing in manual dev-server run
10. AC3: Ensure concurrency test actually exercises the race (not just sequential success)

---

## Recommendation

**Proceed with development**, but prioritize the three "blocking" verifications above before heavy implementation work on AC1, AC3, and AC4. AC2 is lower risk and can be implemented in parallel. All flagged assumptions are reasonable; none are deal-breakers, but confirming them early will save rework.

No false positives detected — all flagged issues are valid verification gaps or subtle behavior changes that deserve explicit confirmation.
