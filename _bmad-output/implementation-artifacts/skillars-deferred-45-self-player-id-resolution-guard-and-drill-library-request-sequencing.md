# Story Deferred-45: Self-Player-Id Resolution Guard & Drill-Library Request Sequencing

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want two independently-real, independently-small, decision-light hygiene gaps found by re-mining the
full `deferred-work.md` ledger closed in one pass — `playerStore.fetchSelfPlayerId()` resolving
successfully with a null/undefined id and silently producing a broken `/player/locker-room/undefined`
navigation (found by `skillars-deferred-44`'s own code review, the immediately preceding story), and
`DrillLibraryPage.vue`/`session.store.js`'s unguarded concurrent-fetch race between `applyFilters` and
`onTabChange` (open since `skillars-4-2`'s code review, 2026-06-17) —
so that these gaps stop accumulating as separate single-item stories, matching the bundling convention every
prior `skillars-deferred-*` pass has followed.

### Why this story exists

This story's creation was explicitly instructed to **bundle several small, unrelated, decision-light
items into one story rather than create another narrow 1-2 AC story** — the pattern every prior
`skillars-deferred-*` pass has followed, most recently `skillars-deferred-44`.

`_bmad-output/implementation-artifacts/deferred-work.md` (1616 lines as of this story's creation) was
re-mined end to end, section by section, following the file's own documented protocol near its top: an item
is non-actionable (skipped) if it already carries `[CLOSED ...]`, `[PICKED UP ...]`, `[STALE ...]`,
`[DISMISSED ...]`, `[WITHDRAWN ...]`, `[SUPERSEDED ...]`, `[MITIGATED ...]`, `[OWNED BY ...]`, `[AUDIT ...]`,
or prose framing it as an accepted tradeoff, a by-design decision, or something needing a product/design
call before any fix is possible. The remaining untagged bullets were triaged section by section, and every
genuine candidate was re-verified against the **live code** — not trusted from the ledger's own prose —
before being used, including three items discovered during that re-verification to already be resolved by
earlier, unannotated stories (bundled as this story's own hygiene AC, mirroring `skillars-deferred-42` AC4's,
`skillars-deferred-43` AC3's and `skillars-deferred-44` AC3's pattern).

**Two genuinely open, decision-light items were found and are bundled here:**

- **`playerStore.fetchSelfPlayerId()` can resolve successfully with a null/undefined id, producing a
  broken `/player/locker-room/undefined` navigation.** `## Deferred from: code review of
  skillars-deferred-44-video-approval-observability-granularity-and-player-redirect-error-differentiation
  (2026-08-20)`: "`playerStore.js:39`'s `fetchSelfPlayerId()` returns `profile?.id` with no null-check on
  the success path — if the API responds 200 with a profile whose `id` is null/undefined, no error is
  thrown, so none of the three call sites' `catch` blocks fire." Re-verified today by direct read of
  `playerStore.js:26-46`: `fetchSelfPlayerId()`'s `.then((profile) => { ...; return profile?.id })` still
  resolves with `undefined` whenever `profile.id` is missing — no throw, no rejection. All three call sites
  confirmed still vulnerable by direct read: `PlayerHomeRedirectPage.vue:35` does
  `router.replace(\`/player/locker-room/${id}\`)` with no id check, and `CoachPublicProfilePage.vue:309` /
  `BookingRequestPage.vue:599` both do `selfPlayerId.value = await playerStore.fetchSelfPlayerId()` with no
  check either — the resolved `undefined` is assigned straight through. This is a real, still-open,
  decision-light gap: the fix is contained entirely to the shared store (no page-level change needed, since
  all three pages already initialize `selfPlayerId` to `null` and their existing 404-vs-other-error `catch`
  branches, built by `skillars-deferred-43`/`-44`, already handle a thrown error correctly). **Candidate for
  this story (AC1).**

- **`DrillLibraryPage.vue`/`session.store.js` have an unguarded concurrent-fetch race between
  `applyFilters`/`clearFilters`/`clearSearch`/the debounced search and `onTabChange`.** `## Deferred from:
  code review of skillars-4-2-drill-card-operations (2026-06-17)` W1: "Concurrent fetch race between
  applyFilters and onTabChange — two in-flight API calls (searchDrills + fetchDrills) can overwrite each
  other's results; last response wins; address with request ID or AbortController in a UX hardening pass."
  Re-verified today: `session.store.js:22-58`'s `fetchDrills()` and `searchDrills()` both write the same
  `drills`/`loading`/`error` refs with **zero** sequencing guard between them — confirmed by direct read,
  no request-id/generation counter of any kind exists in the file. `DrillLibraryPage.vue:206-250` confirms
  the race is live and easy to trigger: `onTabChange` calls `sessionStore.fetchDrills(library)`,
  `applyFilters`/`clearSearch`/`clearFilters` all call `sessionStore.searchDrills(...)`, and the 300ms
  `debouncedSearch` (`:206-209`, a local `useDebounce` that only delays *invocation*, never aborts an
  in-flight request) can still resolve after a newer tab-change or filter-apply call, silently overwriting
  the newer response's `drills` list with a stale one. This is a real, still-open, decision-light gap with
  an already-shipped, directly-mirrorable fix pattern one file over: `booking.store.js:121-124,336-374`
  (`skillars-deferred-38` AC1) already solved the identical class of problem —
  `loadCoachBookingRequests()`'s own `coachRequestsSequence` monotonic counter, captured per call and
  checked before every state-committing point — for the exact same "multiple functions/call-sites racing
  to write shared store state" shape this item describes. **Candidate for this story (AC2).**

**Three additional items were found to be stale during this research — not story material, but corrected
here as a hygiene by-product (AC3):**

- **`## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)`, never tagged
  before now — found already resolved.** "`aggregateRating`/`reviewCount` hardcoded to `0.0`/`0` — wire to
  reviews aggregate in Epic 9." Re-verified today: `CoachProfileService.getPublicProfile`
  (`CoachProfileService.java:334-341`) already returns `profile.getAverageRating()` (falling back to `0.0`
  only when the column is null) and `profile.getReviewCount()` read straight off the `CoachProfile` entity —
  not hardcoded literals. `CoachProfileRepository.java:54`'s `UPDATE CoachProfile p SET p.averageRating =
  :avgRating, p.reviewCount = :reviewCount ...` query is called from `platform.reviews.service`'s
  `CoachRatingService`, `ReviewFlagService` and `ReviewSubmissionService` — Epic 9 wired this exactly as the
  item's own text predicted it would, but the ledger entry was never tagged closed.
- **`## Deferred from: code review of skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)` D1,
  never audited before now — found already resolved.** "DB connection held during S3 upload —
  `GdprExportService.buildExport()` annotated `@Transactional`... Resolved if Patch 1 (remove
  `@Transactional`) is applied; defer this entry only if Patch 1 is skipped." Re-verified today by direct
  read and grep of `GdprExportService.java`: zero `@Transactional` annotations exist anywhere in the file —
  not on the class, not on `buildExport()`, not on any other method — so Patch 1, the item's own stated
  condition for closing it, was applied by an earlier, unannotated story.
- **`## Deferred from: code review of skillars-deferred-18-availability-slot-timezone-integrity
  (2026-08-07)` D5, never tagged before now — found already resolved.** "the profile builder hard-400s on
  any zone the JVM's tzdb doesn't know... Fix options: an explicit zone picker in the profile builder..."
  Re-verified today: `src/frontend/src/components/profileBuilder/TimezoneSelect.vue` exists and is imported
  by both `ProfileBuilderStep1.vue` and `ProfileBuilderStep4.vue` — the exact "explicit zone picker" fix
  option this item named, shipped by `skillars-uat-1` AC4 (its own `sprint-status.yaml` entry records
  "Fixed by a server-validated zone picker"). That closure was never tagged onto this original bullet.

**Decision made during this story's creation — why these two and not others:** the ledger was triaged in
full (every section across all 1616 lines, not just the tail); the overwhelming majority of untagged
bullets either (a) already carry their own "examined and deliberately left alone" / "accepted tradeoff" /
"by design" / "spec-intentional" reasoning on record (e.g. the standing platform-wide
`AFTER_COMMIT`-listener-reliability gap left alone since `skillars-10-2`, the several `V94`/`V97`-class
`ACCESS EXCLUSIVE`-lock migration concerns explicitly deferred as "worth revisiting at production scale,
not now"), (b) explicitly need a product or design decision before any fix is possible (e.g. the
`ReviewSubmissionService`/`ReviewModerationService` stale-Gemini-verdict item, explicitly kept open as a
*design* limitation rather than a live defect; the `DisputeService` `FROZEN`-payment-status gap left open by
`skillars-deferred-41`'s own review, needing a coordinated design call; the `lock.timeout` hint's
zero-Postgres-effect gap, explicitly "a real fix needs a decision, not a patch" per `skillars-deferred-23`'s
own text and referenced again twice since), or (c) are restatements of the standing, repeatedly-declined
frontend-test-infrastructure investment (every `skillars-deferred-*` pass since `-17` has left this alone
for the same reason, most recently `skillars-deferred-44`'s own AC1 Dev Notes, and this pass leaves it alone
too). AC1 and AC2 are the only two items found that are simultaneously real, small, decision-light, and (for
AC2) directly mirror an already-shipped pattern in this same codebase — bundled here purely because both
clear that bar and this pass was asked to bundle rather than defer them a further time. As with
`skillars-deferred-43`/`-44`, the ledger continues to run thin after 44 prior passes — only two substantive
items cleared the bar this time, the same count as each of the two immediately preceding stories.

## Acceptance Criteria

1. **AC1 — `playerStore.fetchSelfPlayerId()` throws instead of silently resolving when the fetched profile
   has no usable id, so every existing call-site `catch` block (which already differentiates a 404 from any
   other failure) handles the malformed-profile case the same way it already handles a genuine
   network/500 failure.** In `src/frontend/src/stores/playerStore.js`, inside `fetchSelfPlayerId()`'s
   `.then((profile) => { ... })` callback (`:31-40`):
   - Keep the existing generation-guarded cache write (`if (requestGeneration === selfPlayerIdGeneration &&
     profile?.id != null) { selfPlayerId.value = profile.id }`) unchanged.
   - After that block, add: if `profile?.id == null`, `throw new Error('Player profile response has no id')`
     — a plain JS `Error`, not a fabricated Axios-shaped object. **Do not** invent a `.response.status`
     field on it: every one of the three call sites' `catch` blocks already checks `err.response?.status
     !== 404`, and a plain `Error` has no `.response`, so `undefined !== 404` is `true` — the existing
     "surface this as a genuine failure" branch fires automatically, exactly as it already does for a real
     network/500 error. This is the intended behavior, not a gap to patch: a 200 response with no usable id
     is not the expected "no profile yet" (404) case, so it must not be silently treated as one.
   - Change the success path's `return profile?.id` to `return profile.id` (safe once the throw above
     guarantees `profile.id` is non-null at this point).
   - **No changes to any of the three call sites** (`PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`,
     `BookingRequestPage.vue`) are needed or wanted. All three already initialize their local `selfPlayerId`
     ref to `null` and already branch their `catch` block on `err.response?.status !== 404` — the exact
     shape a thrown plain `Error` triggers. Verified by direct read of all three: `PlayerHomeRedirectPage.vue`
     falls into its existing `catch (err) { if (err.response?.status !== 404) { $q.notify(...) };
     router.replace('/player/profile-builder') }`, landing on the same safe redirect it already uses for any
     non-404 error. `CoachPublicProfilePage.vue`/`BookingRequestPage.vue` leave `selfPlayerId.value` at its
     initial `null` (their `catch` blocks only notify, they never set the ref), which their own downstream
     code already handles gracefully (`if (selfPlayerId.value) params.set(...)`, and the `playerId` computed
     / `canSubmit` guard blocking submission on a falsy id) — identical to how a 404 is already handled today.
   - No new i18n key is needed — no new user-facing string is introduced; the existing `common.errorGeneric`
     notify path (already wired by `skillars-deferred-43`/`-44`) is what fires.

2. **AC2 — `session.store.js`'s `fetchDrills()` and `searchDrills()` gain a shared monotonic
   request-sequencing guard, mirroring `booking.store.js`'s already-shipped `coachRequestsSequence` pattern
   (`skillars-deferred-38` AC1), so a slower, superseded response can never overwrite a faster, newer one.**
   In `src/frontend/src/stores/session.store.js`:
   - Add one new module-scoped, non-reactive counter, declared alongside the store's existing `ref`
     declarations (top of `useSessionStore`'s setup function): `let drillsRequestSequence = 0`, with a
     comment mirroring `booking.store.js:121-123`'s own ("Non-reactive by design — this is an internal
     ordering token for `fetchDrills`/`searchDrills` below, not page-visible state. Matches
     `booking.store.js`'s identical `coachRequestsSequence` pattern, `skillars-deferred-38`.").
   - **The counter is shared across both `fetchDrills()` and `searchDrills()`**, not one per function — both
     write the same `drills`/`loading`/`error` refs and represent the same logical "current drill list"
     concept, and the race this AC closes is explicitly cross-function (a debounced `searchDrills()` call
     resolving after a newer `fetchDrills()` triggered by a tab change, or vice versa), so a per-function
     counter would not close it.
   - In **both** `fetchDrills(library)` and `searchDrills(library)`:
     - At the top of the function (before the existing `loading.value = true`), capture `const requestId =
       ++drillsRequestSequence`.
     - Immediately after the `await sessionApi.getDrills(...)` call resolves (inside the `try`, before the
       `drills.value = response` assignment), add `if (requestId !== drillsRequestSequence) return` — a
       superseded call's response is discarded without touching `drills`, mirroring
       `loadCoachBookingRequests`'s `if (requestId !== coachRequestsSequence) return true` shape (adapted:
       these two functions have no meaningful boolean return value today and this AC does not invent one —
       a bare `return` is sufficient and preserves the existing "no return value" contract).
     - In the `catch (err)` block, add the same check before `error.value = err`: `if (requestId !==
       drillsRequestSequence) { console.warn('Discarding failure from a superseded drill-list request:',
       err?.message || err); return }` — mirroring `booking.store.js:365-368`'s identical discard-and-log
       shape (including its `err?.message || err` truthy-safe formatting, matching the `skillars-deferred-40`
       AC2 fix to the same convention elsewhere in this codebase) so a discarded failure leaves a diagnostic
       trace instead of vanishing silently.
     - In the `finally` block, guard the existing `loading.value = false` with `if (requestId ===
       drillsRequestSequence) loading.value = false` — mirroring `loadCoachBookingRequests`'s finally-guard
       shape, so a superseded call's `finally` cannot clear the loading flag out from under a still-in-flight
       newer call.
   - **Behavior-preserving in the non-concurrent case** (the guard is a no-op when only one call is ever in
     flight, which is the common case) — matches `skillars-deferred-38`'s own documented framing of the
     identical pattern.
   - No changes to `DrillLibraryPage.vue` are needed — `onTabChange`, `applyFilters`, `clearSearch`,
     `clearFilters` and `debouncedSearch` all already call `sessionStore.fetchDrills(...)` /
     `sessionStore.searchDrills(...)` with no return-value handling today, and none of them need to change to
     benefit from the guard living entirely inside the store.
   - **`DrillLibraryPage.vue` is not `session.store.js`'s only caller.** `src/frontend/src/pages/coach/
     SessionBuilderPage.vue`'s local `fetchDrills()` wrapper (`:277-289`) also calls
     `sessionStore.searchDrills(selectedLibrary.value)`, wired to both the library `q-tabs`'
     `@update:model-value` (`:60`) and the search `q-input`'s `@update:model-value` (`:84`) — with **no
     debounce at all**, unlike `DrillLibraryPage.vue`'s 300ms `useDebounce`, so every keystroke fires a new
     `searchDrills()` call directly. This page reads the same shared `sessionStore.drills`/`sessionStore.loading`
     state and is exposed to the identical race, arguably a more easily triggered instance of it since there's
     no debounce reducing call frequency. Because the sequencing guard lives inside `session.store.js` itself
     (shared across both functions), it transparently protects `SessionBuilderPage.vue`'s calls too — **no
     change to `SessionBuilderPage.vue` is needed either**, same as `DrillLibraryPage.vue`. (Story-review
     Finding 1.)
   - `AbortController`-based cancellation was considered and explicitly **not** adopted — the identical
     alternative `skillars-deferred-38`'s own story evaluated and rejected for the equivalent booking-store
     race, on the same grounds (a sequence counter is simpler, requires no changes to `boot/axios.js`, and
     the redundant-bandwidth cost of a discarded response is not a concern this AC needs to solve).

3. **AC3 — Ledger hygiene.** In `deferred-work.md`:
   - Tag the `## Deferred from: code review of
     skillars-deferred-44-video-approval-observability-granularity-and-player-redirect-error-differentiation
     (2026-08-20)` `playerStore.fetchSelfPlayerId()` null/undefined-id item with
     `` `[PICKED UP by skillars-deferred-45 AC1]` ``.
   - Tag the `## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)` W1 item
     (concurrent fetch race between `applyFilters` and `onTabChange`) with
     `` `[PICKED UP by skillars-deferred-45 AC2]` ``.
   - Tag the `## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)`
     `aggregateRating`/`reviewCount` hardcoded item with `` `[STALE — verified against current code by
     skillars-deferred-45 story creation, 2026-08-20: already fixed. CoachProfileService.getPublicProfile
     (CoachProfileService.java:334-341) already returns profile.getAverageRating() (falling back to 0.0 only
     when null) and profile.getReviewCount() read off the CoachProfile entity, not hardcoded literals.
     CoachProfileRepository.java:54's UPDATE CoachProfile p SET p.averageRating = :avgRating, p.reviewCount =
     :reviewCount query is called from platform.reviews.service.CoachRatingService/ReviewFlagService/
     ReviewSubmissionService — Epic 9 wired this exactly as the item predicted it would. Added by an earlier
     story, unannotated in this ledger.]` `` — do not delete the item, per this file's own "delete only once
     genuinely implemented, not once merely annotated" convention.
   - Tag the `## Deferred from: code review of skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)`
     D1 item (`GdprExportService.buildExport()` `@Transactional` DB-connection-held-during-S3-upload) with
     `` `[STALE — verified against current code by skillars-deferred-45 story creation, 2026-08-20: already
     fixed. GdprExportService.java carries no @Transactional annotation anywhere — not on the class, not on
     buildExport(), not on any other method (grep confirms zero hits) — so Patch 1, the condition this item's
     own text names for closing it, was applied. Added by an earlier story, unannotated in this ledger.]` ``.
   - Tag the `## Deferred from: code review of skillars-deferred-18-availability-slot-timezone-integrity
     (2026-08-07)` D5 item (profile builder hard-400s on tzdb-lag zones) with `` `[STALE — verified against
     current code by skillars-deferred-45 story creation, 2026-08-20: already fixed.
     src/frontend/src/components/profileBuilder/TimezoneSelect.vue exists and is imported by both
     ProfileBuilderStep1.vue and ProfileBuilderStep4.vue — the server-validated zone picker this item's own
     "fix options" list named, shipped by skillars-uat-1 AC4 ("Fixed by a server-validated zone picker" per
     sprint-status.yaml). That closure was never tagged onto this original bullet. Added by an earlier story,
     unannotated in this ledger.]` ``.

### Review Findings

`story-review.md` (2026-08-20) filed 1 finding against the draft, confirmed and fixed in this revision:

- **Finding 1 (Medium, confirmed):** AC2 framed the concurrent-fetch race as living entirely in
  `DrillLibraryPage.vue`, but `SessionBuilderPage.vue` is a second, independent caller of
  `sessionStore.searchDrills()` — un-debounced, so arguably an easier trigger of the same race. This did not
  break AC2's fix (the guard lives in the shared store and protects both callers automatically), but the
  story's own verification/documentation didn't say so. Fixed: AC2, Task 2, Dev Notes, and Project Structure
  Notes all now name `SessionBuilderPage.vue` alongside `DrillLibraryPage.vue` as a verified-no-change-needed
  caller, and Task 2.5 directs manual regression testing at `SessionBuilderPage.vue` specifically since it
  triggers the race more easily.

No code scope was added — this was a verification/documentation gap, not a defect in the proposed fix.

## Tasks / Subtasks

- [x] Task 1: `playerStore.fetchSelfPlayerId()` null-id guard (AC: #1)
  - [x] 1.1 Add the `if (profile?.id == null) throw new Error('Player profile response has no id')` check
    inside the `.then()` callback, after the existing generation-guarded cache write.
  - [x] 1.2 Change `return profile?.id` to `return profile.id`.
  - [x] 1.3 Confirm all three call sites' existing `catch` blocks require no change (verify by reading, not
    editing, `PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`, `BookingRequestPage.vue`).
  - [x] 1.4 Run `npx eslint` on the one touched file and confirm clean.
- [x] Task 2: `session.store.js` drill-request sequencing guard (AC: #2)
  - [x] 2.1 Add the `drillsRequestSequence` counter declaration with its mirroring comment.
  - [x] 2.2 Add the `requestId` capture, post-await supersession check, catch-block supersession check +
    `console.warn`, and finally-block supersession guard to `fetchDrills()`.
  - [x] 2.3 Apply the identical changes to `searchDrills()`.
  - [x] 2.4 Confirm `DrillLibraryPage.vue` requires no change (verify by reading, not editing).
  - [x] 2.5 Confirm `SessionBuilderPage.vue` — `session.store.js`'s other caller of `searchDrills()`, wired
    to its un-debounced tab/search inputs — also requires no change (verify by reading, not editing); manually
    exercise its rapid tab-change + rapid-keystroke search as the primary regression check for this AC, since
    it triggers the race more easily than `DrillLibraryPage.vue`'s debounced search.
  - [x] 2.6 Run `npx eslint` on the one touched file and confirm clean.
- [x] Task 3: Ledger hygiene (AC: #3) — apply the two `[PICKED UP]` tags and three `[STALE]` annotations
  specified in AC3 above.

### Review Findings (code review of the implementation diff, 2026-08-20)

- [x] [Review][Defer] `resetSelfPlayerId()` doesn't clear the in-flight `selfPlayerIdRequest` dedup cache, so a request from a superseded generation can still settle for a new caller [`src/frontend/src/stores/playerStore.js:11,26-30,48-51`] — deferred, pre-existing
- [x] [Review][Defer] `fetchDrills()`/`searchDrills()` duplicate the identical 3-point sequencing guard verbatim instead of sharing a helper [`src/frontend/src/stores/session.store.js:22-58`] — deferred, pre-existing/spec-mandated shape

## Dev Notes

- **This story bundles two unrelated frontend-store fixes (player self-identity resolution, drill-library
  fetch sequencing) by explicit instruction — do not look for a unifying theme beyond "small, real,
  decision-light, and this pass was asked to bundle."**
- **AC1's fix is entirely contained to `playerStore.js`.** Do not touch any of the three call-site pages —
  their existing `catch`-block error differentiation (built by `skillars-deferred-43`/`-44`) already does
  the right thing once `fetchSelfPlayerId()` throws instead of silently resolving with `undefined`. Adding a
  new, page-specific handling branch for "profile had no id" anywhere would be unscoped — the point of this
  AC is that the existing generic-error handling already covers it.
- **AC1's thrown `Error` is deliberately a plain JS `Error`, not a synthetic Axios-error shape.** Every call
  site's `catch` checks `err.response?.status !== 404`; a plain `Error` has `err.response === undefined`, so
  the check evaluates `true` (not-404) and the existing "surface as genuine failure" branch fires — exactly
  the desired behavior. Do not add a fake `.response` object to make the error "look like" a 404 or a 500;
  that would be over-engineering a distinction nothing downstream reads.
- **AC2's guard is a direct, mechanical port of `booking.store.js`'s `coachRequestsSequence` pattern
  (`skillars-deferred-38` AC1) — do not invent a different mechanism** (no `AbortController`, no debounce
  changes, no request cancellation). The one structural difference from the `booking.store.js` precedent is
  that this AC's counter is shared across **two** functions (`fetchDrills`/`searchDrills`), not scoped to
  one — because both functions write the same shared state and the race is explicitly cross-function. Do
  not give each function its own counter; that would not close the race the ledger item describes (a
  `searchDrills()` call resolving after a newer `fetchDrills()` call, or vice versa).
- **AC2 needs no new automated test.** This codebase has no frontend test suite (`skillars-deferred-38`'s
  own equivalent fix shipped with the same reasoning: "standing repo-wide gap for `booking.store.js`") —
  verify by inspection against the `booking.store.js` precedent's own already-proven shape, not by writing a
  new Vitest/Vue-Test-Utils harness this story does not introduce. Manually exercise `SessionBuilderPage.vue`'s
  rapid tab-change + rapid-keystroke search (no debounce there, unlike `DrillLibraryPage.vue`) as the best
  available manual regression check — it triggers the race far more easily than `DrillLibraryPage.vue`'s
  own debounced search.
- **`session.store.js` has two independent callers, not one — both require verification, neither requires a
  code change.** `DrillLibraryPage.vue` and `SessionBuilderPage.vue` both call `fetchDrills()`/`searchDrills()`
  and both are automatically protected by the shared store-level guard. Do not assume `DrillLibraryPage.vue`
  is the only consumer relying on the new guard's correctness.
- **AC3's ledger hygiene (Task 3) should be applied as part of this story's own creation, per the
  established `skillars-deferred-43`/`-44` precedent** (each story's own Dev Notes record its tags as
  "already present in `deferred-work.md` as committed alongside this story file"). Confirm the two
  `[PICKED UP]` tags and three `[STALE]` annotations are present verbatim in `deferred-work.md` before
  marking Task 3 complete, rather than re-applying them if `dev-story` finds them already there.
- Per `docs/validation-strategy.md`, run targeted verification only (`npx eslint` on the two touched frontend
  files) — do not run `mvn verify` (no backend files are touched by this story) and do not run a full
  frontend build unless targeted verification proves insufficient.

### Project Structure Notes

- `src/frontend/src/stores/playerStore.js` — `fetchSelfPlayerId()`'s `.then()` callback gains a
  null/undefined-id throw guard; success-path return narrowed from `profile?.id` to `profile.id` (AC1). No
  other line changes.
- `src/frontend/src/stores/session.store.js` — one new module-scoped `let drillsRequestSequence = 0`
  counter; `fetchDrills()` and `searchDrills()` each gain a captured `requestId`, a post-await supersession
  check, a catch-block supersession check with a `console.warn`, and a finally-block supersession guard
  (AC2).
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `[PICKED UP]` tags + three `[STALE]`
  corrections (AC3).
- No new backend or frontend files. No changes to `PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`,
  `BookingRequestPage.vue`, `DrillLibraryPage.vue`, `SessionBuilderPage.vue`, `booking.store.js`,
  `CoachProfileService.java`, `GdprExportService.java`, or `TimezoneSelect.vue` — all are read-only
  precedents or (for the three `[STALE]` items' source files) content this story confirms is already correct
  and does not modify.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-44-video-approval-observability-granularity-and-player-redirect-error-differentiation
  (2026-08-20)` — AC1's source]
- [Source: `src/frontend/src/stores/playerStore.js` lines 26-46 — AC1's target]
- [Source: `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue` lines 14-30 — AC1's unmodified,
  already-correct precedent]
- [Source: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` lines 265, 308-318 — AC1's
  unmodified, already-correct precedent]
- [Source: `src/frontend/src/pages/parent/BookingRequestPage.vue` lines 244-247, 596-608 — AC1's unmodified,
  already-correct precedent]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-4-2-drill-card-operations (2026-06-17)` W1 — AC2's source]
- [Source: `src/frontend/src/stores/session.store.js` lines 22-58 — AC2's target]
- [Source: `src/frontend/src/pages/coach/DrillLibraryPage.vue` lines 162-168, 206-250 — AC2's unmodified call
  sites, confirming the race is live]
- [Source: `src/frontend/src/pages/coach/SessionBuilderPage.vue` lines 60, 84, 277-289 — AC2's second,
  independent unmodified caller, added per story-review Finding 1]
- [Source: `src/frontend/src/stores/booking.store.js` lines 121-124, 306-374 — AC2's mirrored
  `coachRequestsSequence` pattern (`skillars-deferred-38` AC1)]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-2-3-coach-public-profile-page (2026-06-13)` — AC3's `aggregateRating`/`reviewCount` stale item]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
  lines 334-341 — AC3's `aggregateRating`/`reviewCount` stale-item verification]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`
  line 54 — confirms the aggregate-update query exists and is called from `platform.reviews.service`]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)` D1 — AC3's `GdprExportService` stale item]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java` — confirms
  zero `@Transactional` annotations anywhere in the file]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-18-availability-slot-timezone-integrity (2026-08-07)` D5 — AC3's tzdb-lockout stale item]
- [Source: `src/frontend/src/components/profileBuilder/TimezoneSelect.vue` — confirms the zone picker exists
  and is used by `ProfileBuilderStep1.vue`/`ProfileBuilderStep4.vue`]
- [Source: `_bmad-output/implementation-artifacts/sprint-status.yaml` — `skillars-uat-1-...` entry, confirms
  "Fixed by a server-validated zone picker" as AC4's own completion note]

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

None — no failures encountered; both changes applied cleanly on the first pass.

### Completion Notes List

- AC1: `playerStore.js` `fetchSelfPlayerId()`'s `.then()` callback now throws a plain `Error('Player profile response has no id')` when `profile?.id == null`, after the existing generation-guarded cache write; success-path return narrowed from `profile?.id` to `profile.id`. Verified by direct read that all three call sites (`PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`, `BookingRequestPage.vue`) already branch their `catch` on `err.response?.status !== 404`, which a plain `Error` (no `.response`) satisfies as `true` — routing the malformed-profile case through the existing genuine-failure handling with zero call-site changes. `npx eslint` clean.
- AC2: `session.store.js` gained a module-scoped `drillsRequestSequence` counter shared across `fetchDrills()` and `searchDrills()`, mirroring `booking.store.js`'s `coachRequestsSequence` pattern (`skillars-deferred-38` AC1) — each function captures `requestId` before the API call, discards a superseded response after `await` (bare `return`, no state write), discards a superseded failure in `catch` with a `console.warn` (matching `err?.message || err` convention), and guards the `finally` block's `loading.value = false` so a superseded call can't clear the flag out from under a newer in-flight call. Verified by direct read that neither `DrillLibraryPage.vue` nor `SessionBuilderPage.vue` (the second, independent, un-debounced caller of `searchDrills()` identified by story-review Finding 1) need any change — both are transparently protected by the shared store-level guard. `npx eslint` clean. No automated test added — standing repo-wide absence of frontend test infrastructure, consistent with every prior `skillars-deferred-*` fix to this class of race (most recently `-38`/`-39`). No interactive browser session was available in this environment to manually exercise the race per Task 2.5's guidance; verification here is by code inspection and `node --check` syntax validation against the proven `booking.store.js` precedent, matching this story's own Dev Notes fallback ("verify by inspection against the `booking.store.js` precedent's own already-proven shape").
- AC3: All five `deferred-work.md` tags (2× `[PICKED UP]`, 3× `[STALE]`) were confirmed already present verbatim, applied at story-creation time per the established `skillars-deferred-43`/`-44` precedent — no edit needed in this dev-story pass.

### File List

- `src/frontend/src/stores/playerStore.js` — modified (AC1)
- `src/frontend/src/stores/session.store.js` — modified (AC2)
- `_bmad-output/implementation-artifacts/deferred-work.md` — no change in this pass; tags already present from story creation (AC3)

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 2-item story per explicit instruction not to create another small story. Re-mined `deferred-work.md` end to end (1616 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1 closes `playerStore.fetchSelfPlayerId()`'s null/undefined-id gap (flagged by `skillars-deferred-44`'s own code review, the immediately preceding story) by making it throw instead of silently resolving, routing the malformed-profile case through the existing 404-vs-genuine-failure differentiation all three call sites already ship. AC2 closes `DrillLibraryPage.vue`/`session.store.js`'s concurrent-fetch race (open since `skillars-4-2`'s code review, 2026-06-17) by mirroring `booking.store.js`'s already-shipped `coachRequestsSequence` monotonic-counter pattern (`skillars-deferred-38` AC1), shared across `fetchDrills()`/`searchDrills()` since both write the same store state. AC3 additionally closes 3 stale ledger items found already resolved during the full re-mine — a marketplace-module `aggregateRating`/`reviewCount` item already wired by Epic 9, a GDPR-module `GdprExportService` `@Transactional` item already removed, and an availability-module tzdb-lockout item already fixed by `skillars-uat-1` AC4's zone picker — none previously tagged. Ledger remains thin after 44 prior passes — only two substantive items cleared the real/small/decision-light/directly-mirrorable bar this pass, matching each of the two immediately preceding stories' counts. |
| 2026-08-20 | `story-review.md` adjustments applied, status remains ready-for-dev. Finding 1/Medium: AC2 framed the concurrent-fetch race as living entirely in `DrillLibraryPage.vue`, missing `SessionBuilderPage.vue` — a second, independent, un-debounced caller of `session.store.js`'s `searchDrills()` exposed to the same race, arguably more easily triggered. The shared store-level guard already protects it with no code change needed; fixed by adding `SessionBuilderPage.vue` to AC2/Task 2/Dev Notes/Project Structure Notes as a verified-no-change-needed caller, and directing manual regression testing there specifically. |
| 2026-08-20 | dev-story implementation complete, status → review. AC1: `playerStore.js` `fetchSelfPlayerId()` now throws a plain `Error` on a null/undefined id instead of silently resolving `undefined`, routing the malformed-profile case through all three call sites' existing 404-vs-genuine-failure `catch` handling with zero call-site changes. AC2: `session.store.js`'s `fetchDrills()`/`searchDrills()` gained a shared `drillsRequestSequence` monotonic counter mirroring `booking.store.js`'s `coachRequestsSequence` pattern (`skillars-deferred-38` AC1), closing the concurrent-fetch race across both functions; verified by reading that neither `DrillLibraryPage.vue` nor `SessionBuilderPage.vue` need any change. AC3: all 5 ledger tags confirmed already present from story creation, no edit needed. `npx eslint` clean on both touched files; `node --check` syntax-verified; no `mvn verify` run (no backend files touched) per `docs/validation-strategy.md`. |
| 2026-08-20 | Code review of the implementation diff complete, status → done. Blind Hunter + Edge Case Hunter + Acceptance Auditor, 0 AC violations — AC1/AC2 verified to match spec exactly against the live repo, including all three `fetchSelfPlayerId()` call sites and the `booking.store.js` precedent. 0 decision-needed, 0 patch; 2 real findings deferred to `deferred-work.md` as pre-existing/out-of-scope, not introduced by this diff: `resetSelfPlayerId()` doesn't clear the in-flight `selfPlayerIdRequest` dedup cache (a logout/relogin race can hand a new caller a stale-generation response — pre-existing, AC1's throw is a new symptom of the same root cause, not a new bug), and `fetchDrills()`/`searchDrills()` duplicate the sequencing guard verbatim (matches the spec's explicit "identical changes to both" instruction, not an implementation choice to refactor unilaterally). 11 Blind Hunter findings dismissed as false positives after verification — mostly claims answered by call sites or spec constraints the blind (no-context) reviewer couldn't see (e.g. "no visible caller update", the shared counter and no-AbortController being explicitly spec-mandated, `console.warn` matching the established `booking.store.js` convention). |
