# Story Deferred-37: Batch-Accept Result-Map Pruning & Rebuild-Cost Bound

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want `bookingStore.batchAcceptResultsByBatch` pruned to only the batches still visible in the coach's
booking-requests inbox, instead of accumulating every batch's accept-all results for the lifetime of the page,
so that `CoachBookingRequestsPage.vue`'s `resultByBatch` computed rebuild cost and the store's memory footprint
stay bounded by what is actually on screen rather than growing across an entire coach session.

### Why this story exists

Drawn from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: skillars-deferred-36 implementation (2026-08-19)` section (line 1564) — the one remaining
open item, re-filed by `skillars-deferred-36` AC3 when it closed only the accepted-status-fidelity half of the
finding it inherited from `skillars-deferred-35`'s own code review.

This story's creation re-read `deferred-work.md` end to end (all 1564 lines) a fourth time. `grep -c
"\[PICKED UP"` returns 0 and `grep -c "\[CLOSED"` returns 103 — every previously-filed item is either shipped,
stale, superseded, or explicitly accepted as a low-priority tradeoff with its own reasoning already on record.
`git diff 36abf0c..HEAD -- _bmad-output/implementation-artifacts/deferred-work.md` (the `skillars-deferred-35`
→ `skillars-deferred-36` span, the last point a full read-through was recorded) confirms the only change since
is `skillars-deferred-36`'s own two `[CLOSED ...]` annotations plus this one re-filed item — nothing else in
the file is new. The ledger is mined thin of unrelated small items for the fourth story in a row
(`skillars-deferred-34`, `35`, and `36` each recorded the same finding); unlike those three, this time there is
only one item left, and it is the one the last two stories deliberately left open because it needs a design
decision. Per the `skillars-deferred-34` precedent — "ledger mined thin... chose to scope one larger,
well-documented gap instead" — this story takes that one remaining item and makes the decision it needs, rather
than waiting for a fifth pass to find nothing new again.

**The item, and the decision made during this story's creation:**

- **`resultByBatch` (`CoachBookingRequestsPage.vue:217-228`) rebuilds a fresh `Map` for every batch in
  `bookingStore.batchAcceptResultsByBatch` whenever *any* batch's results change, and
  `batchAcceptResultsByBatch` (`booking.store.js:571`) is never pruned within a page session — it only ever
  grows, once per `handleAcceptAllBatch` call, for the life of the page.**

  **Re-verified against current code during this story's creation.** `getCoachBookingRequests`
  (`BookingService.java:472-516`) queries `bookingRepository.findByCoachIdAndStatusOrderByRequestedStartTimeAsc(
  coach.getId(), "REQUESTED")` (`:476-477`) — bookings that have left `REQUESTED` (accepted or declined, by any
  path) are excluded from both `singles` and every batch group's `bookings` list. Two direct consequences,
  confirmed by tracing both call sites:
  1. **A fully-accepted batch (every sibling succeeded) vanishes entirely from `coachBatchGroups` on the very
     next refresh** — `handleAcceptAllBatch` (`booking.store.js:573-591`) always calls
     `loadCoachBookingRequests()` as its last step (`:584`), so by the time `CoachBookingRequestsPage.vue`
     re-renders, that batch's `group` no longer exists in the `v-for="group in bookingStore.coachBatchGroups"`
     loop (`CoachBookingRequestsPage.vue:26`) — nothing can call `failureReasonFor` for that `batchId` again.
  2. **A partially-accepted batch's `group.bookings` array, after the same refresh, contains only the bookings
     that are still `REQUESTED`** (the failed ones) — the accepted siblings are gone from the render loop too,
     even though the batch group itself remains visible.
  Both mean `batchAcceptResultsByBatch[batchId]` becomes permanently unreachable from the template the moment
  `batchId` drops out of `coachBatchGroups` — yet nothing removes it from the store. Every accept-all click for
  the rest of the coach's session adds one more entry that is retained forever: `resultByBatch`'s `computed`
  (`CoachBookingRequestsPage.vue:217-228`) reruns its full `Object.entries(...)` loop over the *entire*
  accumulated history every time `bookingStore.batchAcceptResultsByBatch` changes (Vue's `computed` reruns
  whenever its tracked dependency changes, and `handleAcceptAllBatch` always replaces the whole object via
  spread, `:579-582`), even though only the batches still on screen can ever be queried.

  **Decision made during this story's creation:** prune `batchAcceptResultsByBatch` inside
  `loadCoachBookingRequests()` itself, immediately after it loads the fresh `coachBatchGroups`, dropping any
  entry whose `batchId` is no longer present. This is the single point every refresh (mount, accept-all,
  approve, reject, decline) already passes through, so it needs no new call sites and no new state. It fully
  resolves both halves of the ledger item without a more complex fix (e.g. a `WeakMap`-based
  per-batch-array-reference memoization inside the computed, which the ledger item's own phrasing floated as
  one option): once pruned, `batchAcceptResultsByBatch`'s size is bounded by the number of batches currently
  visible in the inbox — the same bound `coachBatchGroups` itself already has — so the computed's rebuild cost
  can no longer grow across a session; it can only ever be as large as what is already being rendered. A
  per-reference memoization would additionally avoid rebuilding *unchanged* batches' `Map`s on every store
  update, but at today's batch sizes (a handful of pending requests, per the existing `Low impact today given
  typical batch sizes and session lengths` framing this ledger item itself carries forward from
  `skillars-deferred-35`) that marginal saving is not worth the added complexity; pruning alone converts an
  unbounded-growth concern into a bounded, already-small one. **Not adopted:** a `WeakMap`/reference-based
  memoization scheme — deferred until batch volume or result-map size actually demonstrates the pruned
  computed's remaining cost matters, per this ledger's own established "revisit if it grows" convention (see
  `skillars-deferred-35`'s and `skillars-deferred-36`'s own closure notes on this same item for precedent).

## Deferred Item(s) Closed

| Source | Item | Current location (re-verified 2026-08-19) | AC | Planned outcome |
|---|---|---|---|---|
| `skillars-deferred-36` implementation (2026-08-19) | `resultByBatch`/`batchAcceptResultsByBatch` rebuild-on-any-change cost and never-pruned growth | `CoachBookingRequestsPage.vue:214-233`, `src/frontend/src/stores/booking.store.js:571` | 1 | `loadCoachBookingRequests()` prunes `batchAcceptResultsByBatch` to only batches still present in the freshly-loaded `coachBatchGroups`, bounding both growth and rebuild cost to what is currently visible |

**Explicitly NOT in this story** (considered during story creation and rejected):

- **A `WeakMap`/array-reference-keyed memoization inside `resultByBatch`** to avoid rebuilding unchanged
  batches' `Map`s on every store update. See the decision rationale above — pruning alone already bounds the
  cost to the visible batch count, which matches this project's typical batch sizes; the added complexity is
  not justified today. Re-deferred if batch volume grows enough to matter.
- **Any change to `resultByBatch`'s or `failureReasonFor`'s behavior, i18n keys, or `errorKey` branching.**
  `skillars-deferred-36` AC2 already restored accepted-status fidelity; this story only bounds how much history
  the underlying store retains, which is invisible to `resultByBatch`'s output for any input it can still be
  queried with (a pruned batch can never be queried again, per the trace above).
- **Pruning based on anything other than presence in the freshly-loaded `coachBatchGroups`.** A time-based or
  count-based (LRU) pruning policy was considered and rejected: `coachBatchGroups` is already the exact,
  authoritative "what can still be queried" set — reusing it needs no new heuristic, no extra state, and cannot
  drift out of sync with what the template renders, unlike a time/count-based policy which would.
- **Pruning inside `handleAcceptAllBatch` directly, instead of inside `loadCoachBookingRequests()`.** Rejected
  because `loadCoachBookingRequests()` is the one function every refresh path already calls (mount, accept-all,
  approve, reject, decline — five call sites per `grep -n "loadCoachBookingRequests()" booking.store.js`), so
  pruning there covers a batch dropping out of view via *any* path (e.g. a coach individually declining the
  last `REQUESTED` sibling of a previously-partially-accepted batch), not only the accept-all path.

## Acceptance Criteria

1. **`bookingStore.loadCoachBookingRequests()` prunes `batchAcceptResultsByBatch` to only the `batchId`s present
   in the freshly-loaded `coachBatchGroups`, immediately after that assignment, and only on the success path.**

   Verified current state (`booking.store.js:321-335`):
   ```js
   async function loadCoachBookingRequests() {
     coachRequestsLoading.value = true
     coachRequestsError.value = null
     try {
       const res = await getCoachBookingRequests()
       coachBookingRequests.value = res.singleBookings ?? []
       coachBatchGroups.value = res.batchGroups ?? []
       return true
     } catch (e) {
       coachRequestsError.value = e
       return false
     } finally {
       coachRequestsLoading.value = false
     }
   }
   ```
   `getCoachBookingRequests` (`BookingService.java:472-516`) filters bookings to `status='REQUESTED'`
   (`:476-477`) before grouping into `singles`/`batchGroups`, so a batch whose every booking has left
   `REQUESTED` (fully accepted, or all siblings otherwise resolved) is entirely absent from the response's
   `batchGroups` array. Per the CONTRACT comment immediately above this function (`:302-320`), the `catch`
   branch deliberately leaves `coachBookingRequests`/`coachBatchGroups` stale on failure rather than blanking
   them — pruning must follow that same rule and run only inside the `try`, after the fresh assignment, so a
   failed refresh leaves `batchAcceptResultsByBatch` exactly as stale-consistent as the rest of the page's data,
   never more aggressively pruned than what's actually on screen.

   **Required:**
   ```js
   async function loadCoachBookingRequests() {
     coachRequestsLoading.value = true
     coachRequestsError.value = null
     try {
       const res = await getCoachBookingRequests()
       coachBookingRequests.value = res.singleBookings ?? []
       coachBatchGroups.value = res.batchGroups ?? []
       // skillars-deferred-37: batchAcceptResultsByBatch accumulates one entry per handleAcceptAllBatch
       // call for the life of the page and is never otherwise cleared. A batch's entry becomes
       // unreachable from the template the moment its batchId drops out of coachBatchGroups (the
       // backend only returns batches with at least one booking still REQUESTED — BookingService.java
       // getCoachBookingRequests), so prune it here, the one place every refresh path already passes
       // through, keeping the store's size (and resultByBatch's per-refresh rebuild cost) bounded by
       // what is currently visible instead of growing across the session (skillars-deferred-35/36 code
       // review, closed here).
       const visibleBatchIds = new Set(coachBatchGroups.value.map((g) => g.batchId))
       batchAcceptResultsByBatch.value = Object.fromEntries(
         Object.entries(batchAcceptResultsByBatch.value).filter(([batchId]) => visibleBatchIds.has(batchId))
       )
       return true
     } catch (e) {
       coachRequestsError.value = e
       return false
     } finally {
       coachRequestsLoading.value = false
     }
   }
   ```
   No change to the `catch`/`finally` branches, the function's return contract (`true`/`false`, never rethrows —
   see the CONTRACT comment), or any other function in the store. `batchId` keys in
   `batchAcceptResultsByBatch` and `group.batchId` values in `coachBatchGroups` are both plain strings (the
   backend serializes `UUID` to JSON as a string; `Object.entries`/`Object.fromEntries` keys are always strings
   regardless), so no type coercion is needed for the `Set.has` comparison.

   **Behavior-preserving for every existing caller:** `handleAcceptAllBatch` (`:573-591`) still sets
   `batchAcceptResultsByBatch.value[batchId]` to `null` then to the response data exactly as today, and still
   calls `loadCoachBookingRequests()` last (`:584`) — pruning happens *after* that call's own assignment
   completes, so a batch currently mid-accept-all is never pruned mid-flight (its `batchId` is only ever passed
   to `loadCoachBookingRequests()` after `handleAcceptAllBatch` has already written its own result into the
   store, and pruning only removes entries, never adds one prematurely). A fully-accepted batch's entry is
   dropped on the very refresh call that also removes its `group` from `coachBatchGroups` — but by then nothing
   in the template can query it (see the trace in "Why this story exists" above), so this is unobservable.

2. **`CoachBookingRequestsPage.vue`'s `resultByBatch` computed and `failureReasonFor` function are unchanged.**

   AC1 alone is sufficient: `resultByBatch` (`:217-228`) already only iterates whatever is currently in
   `bookingStore.batchAcceptResultsByBatch` — pruning the store's contents automatically shrinks what this
   computed iterates, with no change to the computed's own logic, `failureReasonFor`'s logic, or any i18n key.
   **Required:** no diff to `CoachBookingRequestsPage.vue` in this story.

3. **Ledger hygiene.** In `deferred-work.md`:
   - Annotate the item under `## Deferred from: skillars-deferred-36 implementation (2026-08-19)`
     (deferred-work.md line 1564) `[CLOSED by skillars-deferred-37 AC1]`, describing what shipped and the
     decision made (pruning-only, no per-reference memoization — see "Why this story exists" above for the full
     rationale to carry into the closure note).
   - Do **not** touch any other line in the file — this story's creation re-read it end to end and confirmed
     everything else is already closed, stale, superseded, or an explicitly-accepted tradeoff.
   - `sprint-status.yaml`: add the
     `skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound` entry (already added at
     story-creation time by this workflow) and its `last_updated` note.

## Tasks / Subtasks

- [ ] **Task 1 — AC1: prune `batchAcceptResultsByBatch` in `loadCoachBookingRequests()`**
  - [ ] Add the `visibleBatchIds`/`Object.fromEntries` pruning block to `booking.store.js`'s
        `loadCoachBookingRequests`, immediately after `coachBatchGroups.value = res.batchGroups ?? []`, inside
        the existing `try` block
  - [ ] No change to the `catch`/`finally` branches or the function's `true`/`false` return contract
  - [ ] Confirm by inspection that `handleAcceptAllBatch`'s own writes to `batchAcceptResultsByBatch` (the
        `null` placeholder, then the response data) are both written before its `loadCoachBookingRequests()`
        call, so pruning never races ahead of a fresh write (`booking.store.js:573-591`)
  - [ ] `npx eslint src/stores/booking.store.js` clean
- [ ] **Task 2 — AC2: confirm no `CoachBookingRequestsPage.vue` change needed**
  - [ ] Verify by inspection that `resultByBatch`/`failureReasonFor` require no edit — their behavior for every
        input is unchanged, only the size of what `bookingStore.batchAcceptResultsByBatch` can ever contain
        shrinks
- [ ] **Task 3 — AC3: ledger hygiene**
  - [ ] `[CLOSED by skillars-deferred-37 AC1]` on the `skillars-deferred-36 implementation` item
        (deferred-work.md line 1564)
  - [ ] `sprint-status.yaml` entry

## Dev Notes

### Established conventions this story must follow

- **Prune/derive state from the one function every refresh path already calls, rather than adding a new call
  site per caller.** `loadCoachBookingRequests()` is that function for this store (five existing call sites);
  AC1 adds one block there instead of touching `handleAcceptAllBatch` or any of the other four callers.
- **Do not widen a fix beyond what the ledger item actually asks for.** The item is specifically about
  `batchAcceptResultsByBatch`'s unbounded growth and `resultByBatch`'s resulting rebuild cost — not about
  `resultByBatch`'s own logic (already correct, per `skillars-deferred-36` AC2) or about a more sophisticated
  memoization scheme the ledger item's phrasing only floated as a possibility. See "Explicitly NOT in this
  story" above.
- **A `[CLOSED by X ACn]` annotation on the exact line the item lives on, no rewriting of the item's own
  prose.** Matches this ledger's ~100 existing closures, most recently `skillars-deferred-36`'s two.
- **When the ledger has exactly one item left and it is decision-gated, make the decision during story
  creation rather than deferring again.** Matches the `skillars-deferred-34` precedent (`sprint-status.yaml`'s
  own note: "ledger mined thin... chose to scope one larger, well-documented gap instead") — this story is that
  same move, one level smaller in scope than `skillars-deferred-34`'s was.

### Files being modified — current state and what must be preserved

- **`src/frontend/src/stores/booking.store.js`** (`:302-335` `loadCoachBookingRequests` and its CONTRACT
  comment, `:565-591` `batchAcceptResultsByBatch`/`handleAcceptAllBatch`) — AC1 adds one pruning block inside
  `loadCoachBookingRequests`'s existing `try`, after the `coachBatchGroups` assignment. The CONTRACT comment's
  guarantees (never rethrows, returns `true`/`false`, leaves refs stale-but-untouched on failure) are
  unchanged and must keep holding after this edit — the pruning line is inside the same `try`, after the same
  point where `coachBatchGroups` itself is only assigned on success. No other function in this file changes.
- **`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`** — not modified by this story (AC2 is a
  verification-only task confirming no edit is needed here).

### Project Structure Notes

- No new REST endpoint, no DTO, no migration, no i18n keys, no production Java changes — this story is a
  frontend store change only (one function, one added block).
- No new files. The one edit is to a file already tracked by prior stories in this same module family
  (`skillars-deferred-34`/`35`/`36`).
- No automated frontend test exists for `booking.store.js` in this repo (`find src/frontend/src -iname
  "*.spec.js" -o -iname "*.test.js"` returns zero hits; `package.json`'s `"test"` script is a no-op stub) — the
  same standing, already-documented gap `skillars-deferred-35`/`36` recorded. Verification for this story is by
  inspection plus ESLint, matching those stories' own precedent; this story does not add frontend test
  infrastructure (out of scope, would be a much larger, unrelated change).

### References

- `src/frontend/src/stores/booking.store.js:302-335` (`loadCoachBookingRequests` and its CONTRACT comment),
  `:565-591` (`batchAcceptResultsByBatch`/`handleAcceptAllBatch`)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:208-291` (`resultByBatch`, `failureReasonFor`,
  `handleAcceptAll` — read for context, not modified)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:472-516`
  (`getCoachBookingRequests`, the `status='REQUESTED'` filter this story's pruning logic relies on)
- `_bmad-output/implementation-artifacts/deferred-work.md` (`## Deferred from: skillars-deferred-36
  implementation (2026-08-19)`, line 1564)
- `_bmad-output/implementation-artifacts/skillars-deferred-36-batch-none-accepted-log-coverage-and-result-map-fidelity.md`
  (the item's origin and closure-format precedent)
- `_bmad-output/implementation-artifacts/skillars-deferred-34-batch-accept-per-booking-outcome-reporting.md`
  (the "ledger mined thin, scope one larger well-documented gap" precedent this story follows)
- `_bmad-output/project-context.md`
- `docs/validation-strategy.md` (smallest-relevant-scope test policy)

## Dev Agent Record

### Implementation Plan

_To be filled by dev-story._

### Completion Notes

_To be filled by dev-story._

### Senior Developer Review (AI)

_To be filled by code-review._

## File List

_To be filled by dev-story._

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: single-item story (ledger mined thin of decision-free candidates for the fourth pass in a row), scoping and deciding the `batchAcceptResultsByBatch` pruning approach re-filed by `skillars-deferred-36` AC3. |
