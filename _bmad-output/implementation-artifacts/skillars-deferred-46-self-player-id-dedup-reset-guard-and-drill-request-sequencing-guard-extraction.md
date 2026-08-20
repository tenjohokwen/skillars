# Story Deferred-46: Self-Player-Id Dedup Reset Guard & Drill-Request Sequencing Guard Extraction

Status: ready-for-dev

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
   generation bump, so a caller in a new generation never receives a superseded generation's in-flight
   response.** In `src/frontend/src/stores/playerStore.js`, inside `resetSelfPlayerId()` (`:51-54`):
   - Add `selfPlayerIdRequest = null` to the function body, alongside the existing
     `selfPlayerId.value = null` and `selfPlayerIdGeneration++` statements. Order does not matter
     functionally (all three are synchronous), but for readability place it after the generation increment,
     matching the order the three module-scoped/ref declarations already appear in at the top of the store
     (`selfPlayerId`, then `selfPlayerIdRequest`, then `selfPlayerIdGeneration`).
   - **No other change to `resetSelfPlayerId()` or `fetchSelfPlayerId()` is needed.** `fetchSelfPlayerId()`'s
     existing `if (!selfPlayerIdRequest)` guard (`:28`), its generation-guarded cache write (`:36-38`), and
     its unconditional throw-on-missing-id (`:39-41`, `skillars-deferred-45` AC1) are all correct as written
     once `selfPlayerIdRequest` is properly reset — the fix works entirely by ensuring a post-reset caller
     never sees a truthy stale `selfPlayerIdRequest` to reuse in the first place.
   - **No call-site changes anywhere.** `resetSelfPlayerId()` is called from `MainLayout.vue`'s
     `handleLogout()`, `App.vue`'s `handleSessionExpired()`, and `useSession.js`'s `handleLogout()`
     (all three added by `skillars-deferred-43` AC2's own patch round) — none of these call sites need to
     change; they already call `resetSelfPlayerId()` at the right moment, this AC only fixes what that
     function itself does.
   - **The orphaned stale promise is not cancelled and does not need to be.** Whatever caller originally
     awaited the pre-reset `fetchSelfPlayerId()` call still receives its real outcome when it eventually
     settles (a JS Promise cannot be cancelled once created) — this AC's fix only ensures a *new* caller
     after reset does not reuse that promise as its own.

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

## Tasks / Subtasks

- [ ] Task 1: `playerStore.resetSelfPlayerId()` dedup-cache clear (AC: #1)
  - [ ] 1.1 Add `selfPlayerIdRequest = null` to `resetSelfPlayerId()`.
  - [ ] 1.2 Confirm `fetchSelfPlayerId()` requires no change (verify by reading, not editing).
  - [ ] 1.3 Confirm all three `resetSelfPlayerId()` call sites (`MainLayout.vue`, `App.vue`, `useSession.js`)
    require no change (verify by reading, not editing).
  - [ ] 1.4 Run `npx eslint` on the one touched file and confirm clean.
- [ ] Task 2: `session.store.js` sequencing-guard extraction (AC: #2)
  - [ ] 2.1 Add the `runSequencedDrillsRequest(apiCall)` helper, lifted verbatim from `fetchDrills()`'s
    current body with the API call generalized to the parameter.
  - [ ] 2.2 Rewrite `fetchDrills(library)` to delegate to the helper.
  - [ ] 2.3 Rewrite `searchDrills(library)` to build `params` unchanged, then delegate to the helper.
  - [ ] 2.4 Confirm `DrillLibraryPage.vue` and `SessionBuilderPage.vue` require no change (verify by
    reading, not editing).
  - [ ] 2.5 Manually exercise `DrillLibraryPage.vue`'s tab-change/filter/search flows and
    `SessionBuilderPage.vue`'s rapid tab-change + rapid-keystroke search, confirming the refactor is
    behavior-preserving (drills list updates correctly, no stale-response flicker, loading spinner clears
    correctly) — the same manual regression check `skillars-deferred-45` Task 2.5 established for this guard.
  - [ ] 2.6 Run `npx eslint` on the one touched file and confirm clean.
- [ ] Task 3: Ledger hygiene (AC: #3) — apply the two `[PICKED UP]` tags specified above.

## Dev Notes

- **This story bundles two unrelated frontend-store fixes (player self-identity dedup-cache reset,
  drill-library sequencing-guard deduplication) by explicit instruction — do not look for a unifying theme
  beyond "small, real, decision-light, and this pass was asked to bundle."**
- **AC1's fix is a single added line.** Do not add any conditional logic around clearing
  `selfPlayerIdRequest` (e.g. "only clear it if no request is in flight") — the whole point is that clearing
  it unconditionally on every reset is always correct: if nothing is in flight, clearing a `null` is a no-op;
  if something is in flight, clearing the reference is exactly what stops a *future* caller from reusing it,
  while the in-flight promise itself continues running independently for whoever is still awaiting it.
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
  (`selfPlayerIdRequest = null`). No other line changes (AC1).
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



### Debug Log References



### Completion Notes List



### File List



## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 2-item story per explicit instruction not to create another small story. Re-mined `deferred-work.md` end to end (1621 lines), re-verifying every candidate against current code rather than trusting ledger text. Both items were filed by `skillars-deferred-45`'s own code review and neither had been picked up. AC1 closes `playerStore.resetSelfPlayerId()`'s failure to clear the in-flight `selfPlayerIdRequest` dedup cache, which could let a new-generation caller receive a superseded generation's in-flight response (a stale valid id, or `skillars-deferred-45` AC1's new unconditional throw firing for the wrong caller). AC2 closes `session.store.js`'s `fetchDrills()`/`searchDrills()` verbatim-duplicated 3-point sequencing guard by extracting it into one shared `runSequencedDrillsRequest(apiCall)` helper, a purely mechanical, behavior-preserving refactor. Unlike `skillars-deferred-43`/`-44`/`-45`, no stale/already-resolved items were found during this pass's re-mine, so this story carries no hygiene AC3 beyond tagging its own two source items. Ledger remains thin after 45 prior passes — only two substantive items cleared the real/small/decision-light bar this pass, the same count as each of the three immediately preceding stories. |
