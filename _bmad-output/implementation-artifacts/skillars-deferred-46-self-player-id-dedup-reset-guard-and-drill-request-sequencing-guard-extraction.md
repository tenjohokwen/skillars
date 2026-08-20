# Story Deferred-46: Self-Player-Id Dedup Reset Guard & Drill-Request Sequencing Guard Extraction

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want two independently-real, independently-small, decision-light hygiene gaps found by re-mining the
full `deferred-work.md` ledger closed in one pass — `playerStore.resetSelfPlayerId()` not clearing the
in-flight `selfPlayerIdRequest` dedup cache (found by `skillars-deferred-45`'s own code review, the
immediately preceding story) and `session.store.js`'s `fetchDrills()`/`searchDrills()` hand-duplicating
the identical request-sequencing guard verbatim (also found by that same review) —
so that these gaps stop accumulating as separate single-item stories, matching the bundling convention every
prior `skillars-deferred-*` pass has followed.

### Why this story exists

This story's creation was explicitly instructed to **bundle several small, unrelated, decision-light
items into one story rather than create another narrow 1-2 AC story** — the pattern every prior
`skillars-deferred-*` pass has followed, most recently `skillars-deferred-45`.

`_bmad-output/implementation-artifacts/deferred-work.md` (1621 lines as of this story's creation) was
re-mined end to end, section by section, following the file's own documented protocol near its top: an item
is non-actionable (skipped) if it already carries `[CLOSED ...]`, `[PICKED UP ...]`, `[STALE ...]`,
`[DISMISSED ...]`, `[WITHDRAWN ...]`, `[SUPERSEDED ...]`, `[MITIGATED ...]`, `[OWNED BY ...]`, `[AUDIT ...]`,
or prose framing it as an accepted tradeoff, a by-design decision, or something needing a product/design
call before any fix is possible. The remaining untagged bullets were triaged section by section, and every
genuine candidate was re-verified against the **live code** — not trusted from the ledger's own prose.

**Two genuinely open, decision-light items were found and are bundled here — both filed by
`skillars-deferred-45`'s own code review, the immediately preceding story, and neither picked up since:**

- **`playerStore.resetSelfPlayerId()` doesn't clear the in-flight `selfPlayerIdRequest` dedup cache, so a
  request from a superseded generation can still settle for a new caller.** `## Deferred from: code review
  of skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing
  (2026-08-20)`: `resetSelfPlayerId()` (`playerStore.js:51-54`) increments `selfPlayerIdGeneration` and
  clears `selfPlayerId.value`, but leaves the module-scoped `selfPlayerIdRequest` (`:11`) untouched.
  Re-verified today by direct read: `fetchSelfPlayerId()`'s `if (!selfPlayerIdRequest)` guard (`:28`) still
  reuses whatever in-flight promise exists, regardless of which generation started it. If a caller invokes
  `fetchSelfPlayerId()` while a previous generation's `getMyProfile()` call is still in flight (e.g.
  logout/relogin racing a slow fetch), the new-generation caller receives the stale promise's outcome
  instead of a fresh request: (1) if the stale response resolves with a *valid* id, the generation check
  only gates the cache write (`:36-38`), not the return value, so the new-generation caller receives and can
  act on the *previous player's* id; (2) if the stale response resolves with no id, `skillars-deferred-45`
  AC1's new throw (`:39-41`) fires unconditionally, rejecting the new generation's caller with an error that
  has nothing to do with their own request. This is a real, still-open, decision-light gap: clearing
  `selfPlayerIdRequest` inside `resetSelfPlayerId()` closes both consequences at once, with no change needed
  anywhere else — a new caller after reset simply finds `!selfPlayerIdRequest` true and starts its own fresh
  request under the new generation, completely independent of whatever the orphaned stale promise eventually
  does. **Candidate for this story (AC1).**

- **`session.store.js`'s `fetchDrills()` and `searchDrills()` duplicate the identical 3-point
  sequencing guard verbatim instead of sharing a helper.** `## Deferred from: code review of
  skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing
  (2026-08-20)`: the post-await supersession check, the catch-block supersession check + `console.warn`, and
  the finally-block supersession guard are hand-copied between the two functions (`session.store.js:22-58` at
  filing time; `:22-74` today after this story's own re-numbering of nothing — the file is unchanged since).
  Re-verified today by direct read: `fetchDrills()` (`:26-43`) and `searchDrills()` (`:45-74`) still carry
  byte-for-byte identical `requestId` capture, post-await check, catch-block check-and-warn, and
  finally-block guard, differing only in which `sessionApi.getDrills(...)` call each makes. This is a real,
  still-open, decision-light gap with an unambiguous, purely-mechanical, behavior-preserving fix: extract
  the shared shape into one helper function that takes the actual API call as a parameter, and have both
  `fetchDrills`/`searchDrills` become one-liners calling it. Any future change to the guard's behavior (a
  different warn message, added telemetry) then only needs editing once. **Candidate for this story (AC2).**

**Decision made during this story's creation — why only these two:** the ledger was triaged in full (every
section across all 1621 lines, not just the tail); the overwhelming majority of untagged bullets either
(a) already carry their own "examined and deliberately left alone" / "accepted tradeoff" / "by design" /
"spec-intentional" reasoning on record, (b) explicitly need a product or design decision before any fix is
possible (e.g. `DisputeService`'s `FROZEN`-payment-status gap left open by `skillars-deferred-41`'s own
review; the `canonical_timezone` dual-column reconciliation, needing a migration and a backfill-rule
decision; `RescheduleService`'s missing availability-window check, needing a semantics decision about
whether a reschedule must fit *current* or *as-booked* availability; the bandwidth-charging dedup gap,
explicitly deferred pending a full design review per `skillars-deferred-40`'s own AC5 text), or (c) are
restatements of the standing, repeatedly-declined frontend-test-infrastructure investment (every
`skillars-deferred-*` pass since `-17` has left this alone for the same reason, most recently
`skillars-deferred-45`'s own Dev Notes, and this pass leaves it alone too). AC1 and AC2 are the only two
items found that are simultaneously real, small, decision-light, and unambiguous in their fix shape — bundled
here purely because both clear that bar and this pass was asked to bundle rather than defer them a further
time. **Unlike `skillars-deferred-43`/`-44`/`-45`, this pass found no stale/already-resolved items during
its re-mine** — so this story carries no AC3 ledger-hygiene-by-deletion bucket; AC3 here is the ledger
tagging for AC1/AC2's own two source items only. As with the three immediately preceding passes, the ledger
continues to run thin after 45 prior passes — only two substantive items cleared the bar this time.

## Acceptance Criteria

1. **AC1 — `playerStore.resetSelfPlayerId()` clears the in-flight request dedup cache alongside the
   generation bump, and `fetchSelfPlayerId()`'s settlement handler only clears that cache if it still owns
   the reference, so a caller in a new generation never receives a superseded generation's in-flight
   response and a superseded generation's eventual settlement can never clobber a newer generation's own
   in-flight request.** In `src/frontend/src/stores/playerStore.js`:
   - Inside `resetSelfPlayerId()` (`:51-54`): add `selfPlayerIdRequest = null` to the function body,
     alongside the existing `selfPlayerId.value = null` and `selfPlayerIdGeneration++` statements. Order
     does not matter functionally (all three are synchronous), but for readability place it **before** the
     generation increment, matching the order the three module-scoped/ref declarations already appear in at
     the top of the store (`selfPlayerId`, then `selfPlayerIdRequest`, then `selfPlayerIdGeneration`).
   - Inside `fetchSelfPlayerId()` (`:26-49`): capture the in-flight promise chain in a local `const request`
     before assigning it to the module-scoped `selfPlayerIdRequest`, and change the `.finally()` callback to
     only clear the module-scoped reference if it still points at *this* request:
     ```js
     if (!selfPlayerIdRequest) {
       const requestGeneration = selfPlayerIdGeneration
       const request = playerRegistrationApi.getMyProfile()
         .then((profile) => {
           if (requestGeneration === selfPlayerIdGeneration && profile?.id != null) {
             selfPlayerId.value = profile.id
           }
           if (profile?.id == null) {
             throw new Error('Player profile response has no id')
           }
           return profile.id
         })
         .finally(() => {
           if (selfPlayerIdRequest === request) selfPlayerIdRequest = null
         })
       selfPlayerIdRequest = request
     }
     return selfPlayerIdRequest
     ```
     This closes a window `resetSelfPlayerId()`'s own fix (the bullet above) would otherwise open: without
     this identity check, a stale generation's request settling *after* `resetSelfPlayerId()` has already let
     a newer generation start its own fresh request would unconditionally null `selfPlayerIdRequest` in its
     `.finally()` — wiping out the reference to the newer, still-pending request (not the stale one) and
     letting a third caller start a redundant duplicate request for the same, newer generation. (Found by
     this story's own review; see Review Findings below.)
   - **No call-site changes anywhere.** `resetSelfPlayerId()` is called from `MainLayout.vue`'s
     `handleLogout()`, `App.vue`'s `handleSessionExpired()`, and `useSession.js`'s `handleLogout()`
     (all three added by `skillars-deferred-43` AC2's own patch round) — none of these call sites need to
     change; they already call `resetSelfPlayerId()` at the right moment, this AC only fixes what
     `resetSelfPlayerId()`/`fetchSelfPlayerId()` themselves do. The three `fetchSelfPlayerId()` call sites
     (`PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`, `BookingRequestPage.vue`) also need no
     change — both halves of the fix are internal to the store.
   - **The orphaned stale promise is not cancelled and does not need to be.** Whatever caller originally
     awaited the pre-reset `fetchSelfPlayerId()` call still receives its real outcome when it eventually
     settles (a JS Promise cannot be cancelled once created) — this AC's fix only ensures a *new* caller
     after reset does not reuse that promise as its own, and that its eventual settlement cannot clobber a
     newer request's reference either.

2. **AC2 — `session.store.js`'s `fetchDrills()` and `searchDrills()` share one sequencing-guard helper
   instead of duplicating it, with no behavior change.** In `src/frontend/src/stores/session.store.js`:
   - Add one new async helper function, `runSequencedDrillsRequest(apiCall)`, placed immediately after the
     `drillsRequestSequence` declaration (`:24`) and before `fetchDrills` (`:26`). It takes a single
     zero-argument function `apiCall` (returning the promise for the actual `sessionApi.getDrills(...)`
     call) and contains exactly the shared shape currently duplicated in both functions:
     ```js
     async function runSequencedDrillsRequest(apiCall) {
       const requestId = ++drillsRequestSequence
       loading.value = true
       error.value = null
       try {
         const response = await apiCall()
         if (requestId !== drillsRequestSequence) return
         drills.value = response
       } catch (err) {
         if (requestId !== drillsRequestSequence) {
           console.warn('Discarding failure from a superseded drill-list request:', err?.message || err)
           return
         }
         error.value = err
       } finally {
         if (requestId === drillsRequestSequence) loading.value = false
       }
     }
     ```
     This is a byte-for-byte lift of `fetchDrills()`'s current body (`:27-42`) with `sessionApi.getDrills(library)`
     generalized to the passed-in `apiCall()`. No behavior differs — same `console.warn` message, same
     `err?.message || err` formatting (matching the `skillars-deferred-40` AC2 convention), same
     loading/error/drills semantics.
   - Rewrite `fetchDrills(library)` to:
     ```js
     async function fetchDrills(library) {
       return runSequencedDrillsRequest(() => sessionApi.getDrills(library))
     }
     ```
   - Rewrite `searchDrills(library)` to build its `params` object exactly as it does today (`:50-61`,
     unchanged) and then delegate:
     ```js
     async function searchDrills(library) {
       const params = {}
       if (searchQuery.value) params.q = searchQuery.value
       if (activeFilters.value.skill) params.skill = activeFilters.value.skill
       if (activeFilters.value.difficultyTier)
         params.difficultyTier = activeFilters.value.difficultyTier
       if (activeFilters.value.equipment) params.equipment = activeFilters.value.equipment
       if (
         activeFilters.value.weakFootBias !== null &&
         activeFilters.value.weakFootBias !== undefined
       ) {
         params.weakFootBias = activeFilters.value.weakFootBias
       }
       return runSequencedDrillsRequest(() => sessionApi.getDrills(library, params))
     }
     ```
   - **Both functions keep their existing external contract**: both remain `async function`s callable exactly
     as before (`await sessionStore.fetchDrills(library)` / `await sessionStore.searchDrills(library)`),
     both still resolve to `undefined` (neither returned a value before this refactor; `return
     runSequencedDrillsRequest(...)` forwards `runSequencedDrillsRequest`'s own `undefined` resolution,
     since its body has no explicit `return` on the success path either) — no caller anywhere reads a return
     value from either function today (verified by grep across `DrillLibraryPage.vue` and
     `SessionBuilderPage.vue`, both confirmed by `skillars-deferred-45` to call these with no return-value
     handling).
   - **No changes to `DrillLibraryPage.vue` or `SessionBuilderPage.vue`.** Both call `fetchDrills`/
     `searchDrills` the same way before and after this refactor; the extraction is entirely internal to
     `session.store.js`.
   - **The `drillsRequestSequence` counter, its declaration, and its explanatory comment (`:21-24`) are
     unchanged** — only the two functions' bodies move into the new shared helper.

3. **AC3 — Ledger hygiene.** In `deferred-work.md`:
   - Tag the `## Deferred from: code review of
     skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing
     (2026-08-20)` `resetSelfPlayerId()`/`selfPlayerIdRequest` item with
     `` `[PICKED UP by skillars-deferred-46 AC1]` ``.
   - Tag the same heading's `fetchDrills()`/`searchDrills()` duplication item with
     `` `[PICKED UP by skillars-deferred-46 AC2]` ``.

### Review Findings

`story-review.md` (2026-08-20) filed 2 findings against the draft, both confirmed and fixed in this revision:

- **Finding 1 (Medium, confirmed):** AC1 as originally scoped ("no other change to `resetSelfPlayerId()` or
  `fetchSelfPlayerId()` is needed" beyond the one added line) missed that `fetchSelfPlayerId()`'s existing
  `.finally()` clears `selfPlayerIdRequest` unconditionally. Once `resetSelfPlayerId()` also clears it
  out-of-band, a stale generation's request settling after a newer generation has already started its own
  fresh request would clobber the newer request's reference via that unconditional `.finally()`, letting a
  third caller start a redundant duplicate request for the same (newer) generation — not a data-correctness
  bug, but a real hole in the dedup guarantee AC1 claims to close. Fixed: AC1 now also has
  `fetchSelfPlayerId()` capture its promise chain in a local and guard `.finally()` with an identity check,
  so only the request that actually owns the module-scoped reference can clear it.
- **Finding 2 (Low, confirmed):** AC1's original placement instruction told the dev to place the new
  statement *after* the generation increment while claiming that ordering *matches* the module's declared
  variable order (`selfPlayerId`, `selfPlayerIdRequest`, `selfPlayerIdGeneration`) — the two halves
  contradicted each other, since matching declared order actually requires placing it *before* the
  increment. Fixed: the directive now says "before," consistent with its own stated justification.

Both findings are folded directly into AC1's text above and Task 1 below, rather than filed as separate
follow-up items — AC1 was not yet implemented at review time, so there was no diff to patch, only the spec
to correct before `dev-story` picks it up.

## Tasks / Subtasks

- [x] Task 1: `playerStore.resetSelfPlayerId()`/`fetchSelfPlayerId()` dedup-cache guard (AC: #1)
  - [x] 1.1 Add `selfPlayerIdRequest = null` to `resetSelfPlayerId()`, placed before the generation increment.
  - [x] 1.2 Change `fetchSelfPlayerId()` to capture its in-flight promise chain in a local `const request`
    and guard the `.finally()` callback with `if (selfPlayerIdRequest === request) selfPlayerIdRequest = null`.
  - [x] 1.3 Confirm all three `resetSelfPlayerId()` call sites (`MainLayout.vue`, `App.vue`, `useSession.js`)
    and all three `fetchSelfPlayerId()` call sites (`PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`,
    `BookingRequestPage.vue`) require no change (verify by reading, not editing).
  - [x] 1.4 Run `npx eslint` on the one touched file and confirm clean.
- [x] Task 2: `session.store.js` sequencing-guard extraction (AC: #2)
  - [x] 2.1 Add the `runSequencedDrillsRequest(apiCall)` helper, lifted verbatim from `fetchDrills()`'s
    current body with the API call generalized to the parameter.
  - [x] 2.2 Rewrite `fetchDrills(library)` to delegate to the helper.
  - [x] 2.3 Rewrite `searchDrills(library)` to build `params` unchanged, then delegate to the helper.
  - [x] 2.4 Confirm `DrillLibraryPage.vue` and `SessionBuilderPage.vue` require no change (verify by
    reading, not editing).
  - [x] 2.5 Manually exercise `DrillLibraryPage.vue`'s tab-change/filter/search flows and
    `SessionBuilderPage.vue`'s rapid tab-change + rapid-keystroke search, confirming the refactor is
    behavior-preserving (drills list updates correctly, no stale-response flicker, loading spinner clears
    correctly) — the same manual regression check `skillars-deferred-45` Task 2.5 established for this guard.
  - [x] 2.6 Run `npx eslint` on the one touched file and confirm clean.
- [x] Task 3: Ledger hygiene (AC: #3) — apply the two `[PICKED UP]` tags specified above.

## Dev Notes

- **This story bundles two unrelated frontend-store fixes (player self-identity dedup-cache reset,
  drill-library sequencing-guard deduplication) by explicit instruction — do not look for a unifying theme
  beyond "small, real, decision-light, and this pass was asked to bundle."**
- **AC1's fix touches two functions, not one.** `resetSelfPlayerId()`'s unconditional clear of
  `selfPlayerIdRequest` is correct as scoped — do not add conditional logic there (e.g. "only clear it if no
  request is in flight"): if nothing is in flight, clearing a `null` is a no-op; if something is in flight,
  clearing the reference is exactly what stops a *future* caller from reusing it, while the in-flight promise
  itself continues running independently for whoever is still awaiting it. But this story's own review found
  that clearing alone reopens a different window — a stale generation's `.finally()` firing *after* a newer
  generation has already started its own request would otherwise null out the newer request's reference —
  which is why `fetchSelfPlayerId()`'s `.finally()` also needs the identity check described in AC1's second
  bullet. Do not skip that half of the fix; it is required for the dedup guarantee to actually hold across a
  reset, not optional hardening.
- **AC2 is a pure refactor — it must not change behavior.** Every message, ordering, and guard condition in
  `runSequencedDrillsRequest` must match `fetchDrills()`'s current body exactly; the temptation to
  "improve" the extracted helper (renaming the warn message, changing supersession-check ordering, adding a
  return value) is out of scope and would make the manual regression check in Task 2.5 harder to trust as a
  like-for-like comparison.
- **Neither AC needs a new automated test.** This codebase has no frontend test suite (the same standing gap
  `skillars-deferred-35`/`36`/`37`/`38`/`45` have all recorded for `booking.store.js`/`session.store.js`) —
  verify AC1 by inspection against the fixed generation/dedup interaction it describes, and verify AC2 by
  inspection plus the manual regression check in Task 2.5, not by introducing a new Vitest/Vue-Test-Utils
  harness this story does not add.
- Per `docs/validation-strategy.md`, run targeted verification only (`npx eslint` on the two touched frontend
  files) — do not run `mvn verify` (no backend files are touched by this story) and do not run a full
  frontend build unless targeted verification proves insufficient.

### Project Structure Notes

- `src/frontend/src/stores/playerStore.js` — `resetSelfPlayerId()` gains one new statement
  (`selfPlayerIdRequest = null`, placed before the generation increment); `fetchSelfPlayerId()`'s in-flight
  promise chain is captured in a local `const request` and its `.finally()` callback gains an identity check
  (`if (selfPlayerIdRequest === request) selfPlayerIdRequest = null`) before clearing the module-scoped
  reference (AC1).
- `src/frontend/src/stores/session.store.js` — new `runSequencedDrillsRequest(apiCall)` helper function;
  `fetchDrills()` and `searchDrills()` bodies replaced with one-line delegations to it. `params`-building
  logic inside `searchDrills()` is unchanged. `drillsRequestSequence` declaration and comment unchanged (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `[PICKED UP]` tags (AC3).
- No new backend or frontend files. No changes to `MainLayout.vue`, `App.vue`, `useSession.js`,
  `DrillLibraryPage.vue`, or `SessionBuilderPage.vue` — all are read-only precedents/call sites this story
  confirms need no change.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing (2026-08-20)` —
  both AC1's and AC2's source, same heading]
- [Source: `src/frontend/src/stores/playerStore.js` lines 1-67 — AC1's target, full file read]
- [Source: `src/frontend/src/layouts/MainLayout.vue`, `src/frontend/src/App.vue`,
  `src/frontend/src/composables/useSession.js` — AC1's three unmodified `resetSelfPlayerId()` call sites,
  established by `skillars-deferred-43` AC2]
- [Source: `src/frontend/src/stores/session.store.js` lines 1-74 — AC2's target, read in full]
- [Source: `src/frontend/src/pages/coach/DrillLibraryPage.vue`,
  `src/frontend/src/pages/coach/SessionBuilderPage.vue` — AC2's two unmodified call sites, established by
  `skillars-deferred-45` AC2/story-review Finding 1]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing.md`
  — this story's direct structural/rigor precedent]

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

None — no failures encountered; both changes applied cleanly on the first pass.

### Completion Notes List

- AC1: `playerStore.js` `resetSelfPlayerId()` now clears `selfPlayerIdRequest = null` (placed before the generation increment) alongside its existing `selfPlayerId.value = null` and `selfPlayerIdGeneration++`. `fetchSelfPlayerId()`'s in-flight promise chain is now captured in a local `const request` before being assigned to the module-scoped `selfPlayerIdRequest`, and its `.finally()` callback now only clears the module-scoped reference if it still points at that same request (`if (selfPlayerIdRequest === request) selfPlayerIdRequest = null`), closing the residual clobber window story-review Finding 1 identified. Verified by direct read that all three `resetSelfPlayerId()` call sites (`MainLayout.vue`, `App.vue`, `useSession.js`) and all three `fetchSelfPlayerId()` call sites (`PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`, `BookingRequestPage.vue`) need no change — both halves of the fix are internal to the store. `npx eslint` clean.
- AC2: `session.store.js` gained a `runSequencedDrillsRequest(apiCall)` helper — a byte-for-byte lift of `fetchDrills()`'s prior body with the API call generalized to a parameter — and `fetchDrills()`/`searchDrills()` now delegate to it as one-liners (`searchDrills()`'s `params`-building logic is unchanged). Verified by direct read that neither `DrillLibraryPage.vue` nor `SessionBuilderPage.vue` need any change — both call `fetchDrills`/`searchDrills` the same way before and after, and neither reads a return value from either function. `npx eslint` clean. No interactive browser session was available in this environment to manually exercise Task 2.5's tab-change/filter/search regression check; verification here is by direct code comparison confirming the extracted helper is byte-for-byte identical to the pre-refactor `fetchDrills()` body (same message, same ordering, same guard conditions), matching this story's own Dev Notes fallback and the same limitation `skillars-deferred-45`'s own completion notes recorded for the equivalent check.
- AC3: Both `deferred-work.md` `[PICKED UP by skillars-deferred-46 ACn]` tags were confirmed already present verbatim, applied at story-creation time per the established `skillars-deferred-43`/`44`/`45` precedent — no edit needed in this dev-story pass.

### File List

- `src/frontend/src/stores/playerStore.js` — modified (AC1)
- `src/frontend/src/stores/session.store.js` — modified (AC2)
- `_bmad-output/implementation-artifacts/deferred-work.md` — no change in this pass; tags already present from story creation (AC3)

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 2-item story per explicit instruction not to create another small story. Re-mined `deferred-work.md` end to end (1621 lines), re-verifying every candidate against current code rather than trusting ledger text. Both items were filed by `skillars-deferred-45`'s own code review and neither had been picked up. AC1 closes `playerStore.resetSelfPlayerId()`'s failure to clear the in-flight `selfPlayerIdRequest` dedup cache, which could let a new-generation caller receive a superseded generation's in-flight response (a stale valid id, or `skillars-deferred-45` AC1's new unconditional throw firing for the wrong caller). AC2 closes `session.store.js`'s `fetchDrills()`/`searchDrills()` verbatim-duplicated 3-point sequencing guard by extracting it into one shared `runSequencedDrillsRequest(apiCall)` helper, a purely mechanical, behavior-preserving refactor. Unlike `skillars-deferred-43`/`-44`/`-45`, no stale/already-resolved items were found during this pass's re-mine, so this story carries no hygiene AC3 beyond tagging its own two source items. Ledger remains thin after 45 prior passes — only two substantive items cleared the real/small/decision-light bar this pass, the same count as each of the three immediately preceding stories. |
| 2026-08-20 | `story-review.md` findings applied. Finding 1/Medium (confirmed): AC1's original "no other change needed" framing missed that `fetchSelfPlayerId()`'s existing `.finally()` clears the dedup cache unconditionally — once `resetSelfPlayerId()` also clears it out-of-band, a stale generation's late settlement could clobber a newer generation's still-in-flight request reference, defeating the dedup guarantee for a third caller. Fixed by expanding AC1 to also have `fetchSelfPlayerId()` capture its promise in a local and guard `.finally()` with an identity check. Finding 2/Low (confirmed): AC1's placement instruction told the dev to place the new statement after the generation increment while claiming to match declared variable order, which actually requires placing it before — fixed by correcting the directive to "before." Status remains ready-for-dev. |
| 2026-08-20 | dev-story implementation complete, status → review. AC1: `playerStore.js` `resetSelfPlayerId()` now clears `selfPlayerIdRequest` alongside the generation bump; `fetchSelfPlayerId()`'s `.finally()` now identity-checks before clearing the module-scoped reference, closing the residual clobber window identified by story-review Finding 1. AC2: `session.store.js`'s `fetchDrills()`/`searchDrills()` now delegate to a shared `runSequencedDrillsRequest(apiCall)` helper, a byte-for-byte behavior-preserving extraction; verified by reading that neither `DrillLibraryPage.vue` nor `SessionBuilderPage.vue` need any change. AC3: both ledger tags confirmed already present from story creation, no edit needed. `npx eslint` clean on both touched files; `node --check` syntax-verified; no `mvn verify` run (no backend files touched) per `docs/validation-strategy.md`. |
| 2026-08-20 | Code review complete, status → done. Blind Hunter + Edge Case Hunter + Acceptance Auditor, 0 AC violations. 12 raw findings (11 Blind Hunter, 1 Edge Case Hunter merged with a matching Blind Hunter item), all dismissed: the stale-promise-value and no-cancellation concerns are explicitly addressed and accepted in this story's own Dev Notes; the microtask-ordering worry is invalid per Promise spec guarantees; naming/comment/bundling/asymmetric-logging/bare-`return` items match the spec's own prescribed code verbatim; the `searchDrills` params-outside-try/catch edge case was verified unreachable (`activeFilters.value` is only ever reassigned as a full 4-key object across all call sites in `DrillLibraryPage.vue`, never nulled). 0 decision-needed, 0 patch, 0 defer. |
