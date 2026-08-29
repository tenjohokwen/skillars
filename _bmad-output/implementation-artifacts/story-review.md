# Story Review: skillars-deferred-82

## Summary
This is a three-part story: one small, real, mechanical test-coverage fix (AC1), and two frontend UX completions for self-booking player pack management (AC2–AC3), plus ledger hygiene. The story is well-researched and thoroughly contextualized, but has several verification gaps that create risk.

---

## Critical Issues

### 1. **Missing verification of backend pack-ownership change from deferred-81 AC4**
- **Severity**: HIGH — this is the foundational assumption for AC2/AC3
- **Issue**: The story claims "skillars-deferred-81 AC4 unlocked the backend (ownership check, 4 widened endpoints) ... for a self-booking player to buy a session pack" and references it multiple times, but does not cite specific code lines or show the actual implementation. A reader of this story alone cannot verify the backend actually supports this.
- **What's needed**: AC2/AC3 should cite the specific service method(s) that were widened to accept a self-booking player, and the ownership logic that makes `parentId` handle both parent and self-booking player cases. If deferred-81 is still in-flight, this story should explicitly note that dependency.
- **False positive risk**: Low — this is a reasonable dependency on prior work, but without code citations it's a trust-me claim.

### 2. **Template logic break in CoachPublicProfilePage: `playerStore.activePlayerId` remains undefined for self-booking players**
- **Severity**: HIGH — regression risk
- **Issue**: The story modifies `onMounted` and `handleCta` to support self-booking players, calling `bookingStore.loadPlayerPacks(selfPlayerId.value)` and using `selfPlayerId` for routing. However, the template and other computed properties still reference `playerStore.activePlayerId` throughout the file. For a self-booking player, `playerStore` is "a parent's linked-children store and meaningless here" (the story's own phrase), so `playerStore.activePlayerId` will be `undefined` or uninitialized.
- **What's at risk**: 
  - Does `CoachPublicProfilePage.vue` have any `v-if="playerStore.activePlayerId"` checks that will fail for a player?
  - Does the template render any coach-availability or booking-history that depends on a populated `playerStore`?
  - The `handleBuySessions` method reads `playerStore.activePlayerId` — the story says "confirm whether `handleBuySessions` needs its own `authStore.isPlayer` branch" but doesn't verify whether the template even calls this for a player, or whether any hidden template logic depends on it being set.
- **What's needed**: Do a full grep and template read of this file to confirm no template logic breaks when `playerStore` remains unpopulated for a self-booking player. Specifically verify: (1) no v-if checks on `playerStore.activePlayerId`, (2) no template bindings that expect it set, (3) the `handleBuySessions` call site and whether it's guarded to parent-only or could be called from a player context.
- **False positive risk**: Medium-high — this is a plausible gap that could easily be missed.

### 3. **MainLayout.vue error handling for `fetchSelfPlayerId()` is underspecified**
- **Severity**: MEDIUM — UX degradation if unhandled
- **Issue**: AC3 says to call `playerStore.fetchSelfPlayerId()` and "handle not-yet-resolved gracefully." The story says "treat a 404-on-fetchSelfPlayerId (verified-but-profile-not-finished) as 'hide the item' rather than a hard error" and to "follow this file's own existing precedent for how a not-yet-resolved async value should render," but does not:
  1. Verify that an existing precedent actually exists (grep the file for other async-resolved items).
  2. Specify what error/state actually occurs when the call returns empty (is it a 404 HTTP? An empty Optional? A thrown exception?).
  3. Show the exact code pattern to use for hiding the nav item.
- **What's needed**: Verify by direct read of `MainLayout.vue` whether any other nav item already handles async resolution (e.g., marketplace link, bookings, messaging). If one does, cite it and use the same pattern. If none do, the story should explicitly say "no precedent exists — hide the item using `v-if="selfPlayerId !== null && selfPlayerId !== undefined"`" (or similar).
- **False positive risk**: Medium — the guidance is reasonable but vague enough to cause implementation confusion.

### 4. **Route semantics: `/parent/players/:playerId/packs` for a self-booking player is confusing**
- **Severity**: LOW-MEDIUM — UX/clarity issue, not a functional bug
- **Issue**: The route is defined as `/parent/players/:playerId/packs` with a "parent/" prefix. For a self-booking player navigating to their own packs, this is semantically misleading (are they viewing parent packs? Their own packs?). The story doesn't address why reusing this route slug is preferable to creating a player-specific route or a role-agnostic one.
- **What's needed**: Either (1) accept the "parent/" prefix as acceptable for both roles (a reasonable choice for code reuse), or (2) add a comment in the route definition explaining why it's appropriate for both. This is not a show-stopper, but clarifying it avoids future confusion.
- **False positive risk**: Low — this is a minor clarity issue, not a missed flow.

---

## AC-Specific Issues

### AC1: `deleteBlock` unit test

**Status**: ✓ **No major issues.** The test specification is clear and correct. Minor notes:

- The test instructions say "stub `blockRepository.findByIdAndCoachId(blockId, coachId)` to return `Optional.empty()`" — confirm during implementation that `Mockito.when(blockRepository.findByIdAndCoachId(blockId, coachId)).thenReturn(Optional.empty())` is the syntax (or `.thenReturn(null)` if using a non-Optional, depending on the repo's signature).
- The assertion correctly verifies both the exception and that `delete()` was never called. Good.

---

### AC2: `CoachPublicProfilePage.vue` CTA logic

**Status**: ⚠ **Unclear scope and incomplete verification.**

**Issues**:

1. **Assumption not verified: does `bookingStore.loadPlayerPacks(playerId)` work identically for a self-booking player?**
   - The story says "Confirm during implementation whether the existing computed already works unchanged once `onMounted` populates `sessionPacks` for a player too, or whether it needs an explicit role branch."
   - This is deferring a critical verification to implementation time. If the backend treats a parent-call vs a player-call differently (e.g., different filtering, different pack states, different ownership checks), this could silently fail at runtime.
   - **What's needed**: Before coding, verify by reading `bookingStore.js`'s `loadPlayerPacks` method: does it accept a `playerId` parameter? Does it filter/transform the response based on caller role (e.g., checking `isParent()`)? Does it expect `playerStore.activePlayerId` to be set?

2. **`hasCreditsForCoach` verification deferred to implementation.**
   - The story says "confirm during implementation whether the existing computed already works unchanged." Again, this is defer-to-later verification.
   - If the computed property checks `authStore.isParent` anywhere, it will break for a self-booking player.
   - **What's needed**: Read the actual `hasCreditsForCoach` property in this file and confirm the predicate (`some(...)` logic) doesn't depend on role.

3. **`handleBuySessions` scope unclear.**
   - The story says "confirm whether `handleBuySessions` needs its own `authStore.isPlayer` branch ... by checking which `q-btn`(s) in this file's template actually call it."
   - This is reasonable, but the story should provide the line number of this method (`handleBuySessions`) in the file so the dev knows what they're looking at.
   - Also: if the method reads `playerStore.activePlayerId`, adding a player branch won't work — it will need to use `selfPlayerId.value` instead. The story touches on this but doesn't make it explicit.

4. **Manual test success criteria are vague.**
   - "confirming the CTA label and both purchase/booking routes behave identically to the equivalent parent-managed-player scenarios" — which scenarios? Which exact button clicks and expected behaviors?
   - **What's needed**: Explicit test steps:
     - Step 1: Log in as self-registered player with existing pack for Coach A → verify CTA shows "Book Session" and routes to `request-booking`.
     - Step 2: Log in as self-registered player with no pack for Coach B → verify CTA shows "Buy Sessions" and routes to `purchase-sessions`.
     - Step 3: Log in as self-registered player who hasn't completed profile yet → verify behavior degrades gracefully (CTA disabled? Hidden? Shows error?).
     - Compare each step to the equivalent parent-managed-player case.

---

### AC3: Pack dashboard reachability

**Status**: ⚠ **Incomplete guidance on async resolution.**

**Issues**:

1. **"Existing precedent" for async nav items may not exist.**
   - The story says "follow this file's own existing precedent for how a not-yet-resolved async value should render," but doesn't verify this precedent exists.
   - If `MainLayout.vue` has no async-resolved nav items (all three existing ones — Marketplace, Bookings, Messaging — are static routes), then there is no precedent to follow, and the dev is inventing a new pattern.
   - **What's needed**: Grep `MainLayout.vue` for any other `v-if` checks that involve async-resolved values. If none exist, state that explicitly and specify the pattern to use (e.g., `v-if="selfPlayerId"` to hide until resolved).

2. **Resolution timing and double-fetch risk.**
   - AC2 resolves `selfPlayerId` in `onMounted` in `CoachPublicProfilePage.vue`.
   - AC3 resolves it again in `MainLayout.vue` `onMounted`.
   - The story says `playerStore.fetchSelfPlayerId()` is "already cached, dedup-safe" with an "early-return-if-already-resolved check." Good. But does this early-return survive a logout? The story mentions "generation-guarded against a stale resolve racing a logout" — verify this by reading `playerStore.js:26-28` to confirm the guard actually exists and works for a double-call scenario (fetch in CoachPublicProfilePage, then fetch again in MainLayout on the same session).

3. **Route binding to unresolved value.**
   - The story says "Bind the new nav item's `:to` to a computed route using the resolved id."
   - If `selfPlayerId` is not yet resolved on first mount, the computed route will be invalid, and the nav item might try to navigate to `/parent/players/undefined/packs`.
   - **What's needed**: Explicitly handle the unresolved case in the computed route (return a falsy value or `''` if `selfPlayerId` is not yet set, then guard the nav item with `v-if="nav item route is truthy"`).

4. **No comment on route naming confusion (see Critical Issue #4 above).**

---

## Task 4: Ledger Hygiene

**Status**: ⚠ **Claims not backed by code citations.**

The story lists seven items to close/decide in `deferred-work.md`:

1. `getParentPlayerSchedule` N+1 — "no longer performs per-row lookups"
2. `PlaybackService.authorizePlayback` — "already takes `videoRepository.findByIdForUpdate`"
3. Session pack status constraints — "no persisted status column"
4. `CashOutServiceTest` field mismatch — "test already stubs the right field"
5. `pausePack` locking — "all four now call `findByIdForUpdate`"
6. Messaging orphaned-profile — "decided: leave as-is"
7. `drill_video_refs.video_id` FK — "decided: keep deferring"

**Issue**: The story says "confirmed by direct read" multiple times but does not cite line numbers or show excerpts. For a reviewer, this means trust-the-author on each claim.

**What's needed**: For items 1–5, include line-number citations (e.g., "`getParentPlayerSchedule` at `BookingService.java:623-661` no longer calls..."). For items 6–7, confirm the project owner's decision was actually documented and approved (the story says it was, but doesn't show evidence).

**Risk**: If any of these claims is wrong (e.g., `pausePack` actually still has an unlocked variant somewhere), the story will ship stale ledger entries. This is low-risk for functionality (no code changes) but creates maintenance burden.

---

## Non-Critical Observations

### Strengths
- Very thorough contextualization of why the story exists (dry ledger, project-owner decision).
- Good adherence to existing patterns (dual-role routing, `fetchSelfPlayerId`, opaque-payer-id).
- Explicit call-out of conventions (no frontend test framework).
- Task 4 is good housekeeping, reducing technical debt in the ledger.

### Suggestions
- AC2/AC3 tests would benefit from explicit "pass" criteria (e.g., "CTA shows 'Book Session' and routes correctly" vs. "CTA label and routes behave identically").
- Task 4 items should include code citations for verification.
- Consider adding a note: "AC1, AC2, AC3 must be implemented in sequence — AC2 depends on the `bookingStore.loadPlayerPacks(playerId)` contract being correct, AC3 depends on AC2's player-branch being complete."

---

## Recommendation

**Ready for dev with corrections**. The story is well-structured and addresses real gaps, but developers should:

1. **Before coding AC2**, verify the `bookingStore.loadPlayerPacks()` contract in `bookingStore.js` (does it work identically for player and parent? Any role-based filtering?).
2. **Before coding AC2**, verify no template logic in `CoachPublicProfilePage.vue` breaks when `playerStore` remains unpopulated for a self-booking player.
3. **Before coding AC3**, verify whether `MainLayout.vue` has any existing async-nav-item patterns to follow, or if this is a new pattern to establish.
4. **Before merging Task 4**, add line-number citations to the ledger-hygiene claims in this story and in `deferred-work.md` for traceability.

No showstoppers, but verification gaps create unnecessary risk during implementation.
