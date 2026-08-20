# Story Review: Deferred-45 — Self-Player-Id Resolution Guard & Drill-Library Request Sequencing

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing.md`
Method: every factual claim in the story (line numbers, "no other call sites", "no changes needed to X") was
re-verified against the current code on this branch, not trusted from the story's own prose.

## Findings

### 1. AC2 misses a second, more aggressive caller of the same race: `SessionBuilderPage.vue`

**Severity: Medium (scope/completeness gap in the story, not a defect in the proposed fix's mechanics).**

The story frames the `fetchDrills()`/`searchDrills()` concurrent-fetch race as living entirely between
`DrillLibraryPage.vue`'s `applyFilters`/`clearFilters`/`clearSearch`/debounced-search and `onTabChange`. Task
2.4 only asks the dev to "confirm `DrillLibraryPage.vue` requires no change," and the Dev Notes / Project
Structure Notes never mention any other caller.

But `session.store.js`'s `fetchDrills`/`searchDrills` have a second, independent caller:
`src/frontend/src/pages/coach/SessionBuilderPage.vue`. Its local `fetchDrills()` wrapper
(`SessionBuilderPage.vue:277-289`) calls `sessionStore.searchDrills(selectedLibrary.value)` and is wired to
**both**:
- the library `q-tabs`' `@update:model-value="fetchDrills"` (`:60`), and
- the search `q-input`'s `@update:model-value="fetchDrills"` (`:84`) — with **no debounce at all**, unlike
  `DrillLibraryPage.vue`'s 300ms `useDebounce`. Every keystroke fires a new `searchDrills()` call directly.

This page reads the exact same shared `sessionStore.drills` (`:95`) and `sessionStore.loading` (`:89`) state
that `DrillLibraryPage.vue` reads, and is exposed to the identical class of race described in AC2 — arguably a
worse instance of it, since there is no debounce to reduce call frequency. The story's own re-verification
step ("re-verified against live code, not trusted from ledger prose") did not surface this second call site.

This does not break AC2's fix: because the sequencing guard lives inside `session.store.js` itself (shared
across both functions, as AC2 correctly specifies), it transparently protects `SessionBuilderPage.vue`'s calls
too, with no page-level change needed there either — same as `DrillLibraryPage.vue`. So no additional AC or
task is required to *fix* anything.

**What's missing is verification/documentation, not code:** Task 2.4 and the Dev Notes should also state that
`SessionBuilderPage.vue` was checked and requires no change (mirroring the `DrillLibraryPage.vue` bullet),
so:
- a dev implementing this story doesn't get a false impression that `DrillLibraryPage.vue` is the only
  consumer relying on the new guard's correctness, and
- manual verification (this story ships with no automated frontend tests, per its own Dev Notes) actually
  exercises `SessionBuilderPage.vue`'s tab-change + rapid-keystroke search, which is the more easily
  triggered instance of the race and the best manual regression check available for this AC.

## Not flagged (verified accurate, no issue found)

- AC1's three call sites (`PlayerHomeRedirectPage.vue:22-30`, `CoachPublicProfilePage.vue:308-318`,
  `BookingRequestPage.vue:599-607`) all branch their `catch` on `err.response?.status !== 404` exactly as
  described; a plain thrown `Error` (no `.response`) correctly routes through the existing "genuine failure"
  branch at all three. Confirmed no other call site of `fetchSelfPlayerId()` exists.
- AC1's guard placement (throw after the existing generation-guarded cache write, `return profile.id` only
  reachable once `profile.id` is guaranteed non-null) is logically sound; no null-pointer path.
- AC2's mirrored `coachRequestsSequence` pattern in `booking.store.js:121-124,336-374` matches the story's
  description verbatim, including the discard-and-`console.warn` catch shape and the finally-guard shape.
- AC2's guard is behavior-preserving in the non-concurrent (common) case, as claimed — traced through
  manually.
- All three AC3 "stale" claims verified against current code: `CoachProfileService.java:334-341` returns
  `getAverageRating()`/`getReviewCount()` (not hardcoded), `GdprExportService.java` has zero `@Transactional`
  annotations, and `TimezoneSelect.vue` exists and is imported by both `ProfileBuilderStep1.vue` and
  `ProfileBuilderStep4.vue`. All five `deferred-work.md` tags (2 `[PICKED UP]`, 3 `[STALE]`) are already
  present verbatim in the ledger, matching AC3/Task 3 exactly.
- No frontend test files exist for `playerStore.js` or `session.store.js` — the "no new automated test"
  Dev Notes claim is accurate.
