# Story Deferred-37: Batch-Accept Result-Map Pruning & Rebuild-Cost Bound

Status: done

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

**Two gaps found by this story's own code review, folded into the ACs below before dev-story runs:**

1. **The naive pruning write busts `resultByBatch`'s cache on every refresh, not just ones where something
   was actually pruned.** `Object.fromEntries(...)` always allocates a new object; assigning it to
   `batchAcceptResultsByBatch.value` unconditionally — even when the filter removed nothing — is a Vue
   reactivity change every time (`ref` uses reference equality), because `loadCoachBookingRequests()` is
   called by `approveBooking`/`rejectBooking`/page-mount too, none of which write to
   `batchAcceptResultsByBatch` today. Left unguarded, this story would make `resultByBatch` rebuild on every
   single-booking approve/decline and every mount — strictly more often than today, which directly
   contradicts this story's own "bound the rebuild cost" goal. **AC1 below now only reassigns the ref when
   pruning actually removes an entry.**
2. **`CoachBookingRequestsPage.vue`'s `handleAcceptAll` (`:251`) reads `bookingStore.batchAcceptResultsByBatch[batchId]`
   directly — a second, non-computed consumer of the pruned state that this story's original analysis above
   (`resultByBatch`/`failureReasonFor` only) missed.** That read runs *after* AC1's pruning (it happens inside
   the same `handleAcceptAllBatch` call, right after `loadCoachBookingRequests()`), so if the batch's last
   `REQUESTED` sibling is independently resolved in the window between the accept-all response and that
   refresh — a concurrent per-row decline (the UI already allows this: `declining[id]` and
   `acceptingAll[batchId]` are independent per-row/per-batch flags), `BookingExpiryScheduler.expireStaleRequests()`
   auto-declining a stale sibling, `AdminCoachEnforcementService.suspendCoach()` cancelling every `REQUESTED`
   booking for a suspended coach, or `PackSessionService.pausePack()` cancelling `REQUESTED` bookings for a
   paused pack — the entry gets pruned before this read runs, `results` falls back to `[]`, and the coach is
   told "All sessions accepted" even though a sibling genuinely failed. This read was always accurate before
   AC1 (nothing ever pruned the entry); AC1 introduces this failure mode unless decoupled. **A new AC2 below
   has `handleAcceptAllBatch` return its own results directly, and `handleAcceptAll` consume that return value
   instead of re-reading store state that AC1's own pruning can have already removed.**

## Deferred Item(s) Closed

| Source | Item | Current location (re-verified 2026-08-19) | AC | Planned outcome |
|---|---|---|---|---|
| `skillars-deferred-36` implementation (2026-08-19) | `resultByBatch`/`batchAcceptResultsByBatch` rebuild-on-any-change cost and never-pruned growth | `CoachBookingRequestsPage.vue:214-233,246-265`, `src/frontend/src/stores/booking.store.js:321-335,571-591` | 1, 2 | `loadCoachBookingRequests()` prunes `batchAcceptResultsByBatch` to only batches still present in the freshly-loaded `coachBatchGroups` (reassigning only when that actually removes an entry), bounding both growth and rebuild cost to what is currently visible; `handleAcceptAllBatch`/`handleAcceptAll` are decoupled from reading pruned state back out, so the accept-all result notification is never affected by a concurrent prune |

**Explicitly NOT in this story** (considered during story creation and rejected):

- **A `WeakMap`/array-reference-keyed memoization inside `resultByBatch`** to avoid rebuilding unchanged
  batches' `Map`s on every store update. See the decision rationale above — pruning alone already bounds the
  cost to the visible batch count, which matches this project's typical batch sizes; the added complexity is
  not justified today. Re-deferred if batch volume grows enough to matter.
- **Any change to `resultByBatch`'s or `failureReasonFor`'s own behavior, i18n keys, or `errorKey` branching.**
  `skillars-deferred-36` AC2 already restored accepted-status fidelity; this story only bounds how much history
  the underlying store retains, which is invisible to `resultByBatch`'s output for any input it can still be
  queried with (a pruned batch can never be queried again, per the trace above). This is distinct from AC2's
  change below, which touches a *different* consumer (`handleAcceptAll`'s own direct store read, not
  `resultByBatch`/`failureReasonFor`) precisely because that consumer is *not* safe from pruning the way
  `resultByBatch`/`failureReasonFor` are.
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
   in the freshly-loaded `coachBatchGroups`, immediately after that assignment, only on the success path, and
   only reassigns the ref when pruning actually removes at least one entry.**

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
       // review, closed here). Reassign only when pruning actually removes an entry: this function now
       // runs on every approve/decline/mount too, not just accept-all, and Object.fromEntries always
       // allocates a new object — an unconditional reassignment would mark batchAcceptResultsByBatch
       // dirty on every refresh regardless of whether anything changed, forcing resultByBatch to rebuild
       // on refreshes that touch no batch at all (this story's own code review finding, closed here).
       const visibleBatchIds = new Set(coachBatchGroups.value.map((g) => g.batchId))
       const currentEntries = Object.entries(batchAcceptResultsByBatch.value)
       const prunedEntries = currentEntries.filter(([batchId]) => visibleBatchIds.has(batchId))
       if (prunedEntries.length !== currentEntries.length) {
         batchAcceptResultsByBatch.value = Object.fromEntries(prunedEntries)
       }
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

   **Why the length-check guard matters:** `loadCoachBookingRequests()` is called by `approveBooking` (`:375`),
   `rejectBooking` (`:380`), and page mount (`CoachBookingRequestsPage.vue:294`) in addition to
   `handleAcceptAllBatch` — none of which write to `batchAcceptResultsByBatch` today, so today they never mark
   `resultByBatch`'s tracked dependency dirty. Vue's `ref` reactivity is reference-based (`hasChanged` is
   `!Object.is(newVal, oldVal)`), and `Object.fromEntries` always returns a new object even when the filtered
   result is identical to the input — so an unconditional reassignment would make `resultByBatch` recompute on
   every refresh, including ones that touch no batch at all. Skipping the reassignment when
   `prunedEntries.length === currentEntries.length` keeps `resultByBatch`'s rebuild trigger scoped to only the
   refreshes where the visible batch set actually shrank, which is this story's actual goal.

   **Behavior-preserving for every existing caller:** `handleAcceptAllBatch` (`:573-591`) still sets
   `batchAcceptResultsByBatch.value[batchId]` to `null` then to the response data exactly as today, and still
   calls `loadCoachBookingRequests()` last (`:584`) — pruning happens *after* that call's own assignment
   completes, so a batch currently mid-accept-all is never pruned mid-flight (its `batchId` is only ever passed
   to `loadCoachBookingRequests()` after `handleAcceptAllBatch` has already written its own result into the
   store, and pruning only removes entries, never adds one prematurely). A fully-accepted batch's entry is
   dropped on the very refresh call that also removes its `group` from `coachBatchGroups`. `resultByBatch`
   and `failureReasonFor` can no longer query it at that point — but `handleAcceptAll`'s own direct read of
   `batchAcceptResultsByBatch[batchId]` right after this same call *can* still be affected; AC2 below removes
   that read entirely so this is unobservable everywhere, not just in the template.

2. **`bookingStore.handleAcceptAllBatch` returns its own accept-all results directly instead of requiring the
   caller to re-read `batchAcceptResultsByBatch[batchId]` after the trailing refresh, and
   `CoachBookingRequestsPage.vue`'s `handleAcceptAll` consumes that returned value.**

   **The gap this closes:** `handleAcceptAll` (`:246-265`) currently does
   `const results = bookingStore.batchAcceptResultsByBatch[batchId] ?? []` immediately after
   `await bookingStore.handleAcceptAllBatch(batchId)` resolves — i.e. *after* AC1's pruning has already run,
   since pruning happens inside `handleAcceptAllBatch`'s own trailing `loadCoachBookingRequests()` call. If the
   batch's last `REQUESTED` sibling is resolved by anything other than this accept-all call in the window
   between the accept-all response and that refresh — a concurrent per-row decline (the UI already allows
   this: `declining[id]` and `acceptingAll[batchId]` are independent per-row/per-batch loading flags, and the
   CONTRACT comment above `loadCoachBookingRequests` already documents this class of concurrency as expected),
   `BookingExpiryScheduler.expireStaleRequests()` auto-declining a stale sibling, `AdminCoachEnforcementService
   .suspendCoach()` cancelling every `REQUESTED` booking for a coach being suspended, or `PackSessionService
   .pausePack()` cancelling `REQUESTED` bookings for a paused pack — AC1 prunes the entry before this read runs.
   `results` then falls back to `[]`, `failedCount` becomes `0`, and the coach sees "All sessions accepted" even
   though a sibling genuinely failed to accept. This read was always accurate before AC1 (nothing ever pruned
   the entry); AC1 introduces this failure mode unless the read is decoupled from post-prune store state.

   **Required (`booking.store.js`, `handleAcceptAllBatch`):**
   ```js
   async function handleAcceptAllBatch(batchId) {
     batchAcceptLoading.value = true
     batchAcceptError.value = null
     batchAcceptResultsByBatch.value = { ...batchAcceptResultsByBatch.value, [batchId]: null }
     try {
       const response = await acceptAllBatch(batchId)
       batchAcceptResultsByBatch.value = {
         ...batchAcceptResultsByBatch.value,
         [batchId]: response.data,
       }
       // Returns its own refresh outcome AND its own results — see the CONTRACT note above
       // loadCoachBookingRequests. Callers must read results from here, not from
       // batchAcceptResultsByBatch[batchId]: AC1's pruning (inside loadCoachBookingRequests, called next)
       // can remove that entry before a caller gets a chance to re-read it, if something else resolved the
       // batch's last REQUESTED sibling in the interim (skillars-deferred-37 code review, closed here).
       const refreshed = await loadCoachBookingRequests()
       return { refreshed, results: response.data }
     } catch (e) {
       batchAcceptError.value = e
       throw e
     } finally {
       batchAcceptLoading.value = false
     }
   }
   ```

   **Required (`CoachBookingRequestsPage.vue`, `handleAcceptAll`):**
   ```js
   async function handleAcceptAll(batchId) {
     acceptingAll.value[batchId] = true
     try {
       // handleAcceptAllBatch returns both its own refresh outcome and its own results — read results from
       // here, not from bookingStore.batchAcceptResultsByBatch[batchId], which AC1's pruning can have
       // already removed by the time this line runs (skillars-deferred-37 code review).
       const { refreshed, results } = await bookingStore.handleAcceptAllBatch(batchId)
       notifyIfRequestsStale(refreshed)
       const failedCount = results.filter((r) => !r.accepted).length
       if (failedCount > 0) {
         $q.notify({
           type: 'warning',
           message: t('booking.batch.partiallyAccepted', {
             accepted: results.length - failedCount,
             total: results.length,
           }),
         })
       } else {
         $q.notify({ message: t('booking.batch.acceptedAll'), type: 'positive' })
       }
     } catch (err) {
       // unchanged — see current implementation
     } finally {
       acceptingAll.value[batchId] = false
     }
   }
   ```
   The `catch` block's error-key branching (`booking.coachUnavailable`, `booking.batchAlreadyProcessed`,
   `booking.batchNoneAccepted`, `MISSING_RIGHTS`, default) is unchanged — `acceptAllBatch` still throws for
   those cases before `handleAcceptAllBatch` ever returns, so this AC does not touch that branch.
   `handleAcceptAllBatch` remains the only caller-facing function whose return shape changes; `loadCoachBookingRequests`,
   `loadCoachSchedule`, `approveBooking`, and `rejectBooking` keep their existing `true`/`false` contract exactly
   as the CONTRACT comment describes — `handleAcceptAllBatch` already carried its own distinct "returns its own
   refresh outcome" note rather than being bound by that CONTRACT block, so widening it to `{ refreshed, results }`
   for its one caller is a local, self-contained change.

3. **`CoachBookingRequestsPage.vue`'s `resultByBatch` computed and `failureReasonFor` function are unchanged.**

   Neither AC1 nor AC2 touches `resultByBatch`'s own iteration logic or `failureReasonFor`'s logic: AC1 only
   shrinks what `bookingStore.batchAcceptResultsByBatch` can ever contain, and AC2 only changes where
   `handleAcceptAll` sources its own notification data from (a sibling function's return value, not the
   computed). `resultByBatch` (`:217-228`) still iterates whatever is currently in
   `bookingStore.batchAcceptResultsByBatch`, unmodified. **Required:** no diff to `resultByBatch` or
   `failureReasonFor` themselves; the only diff to this file is inside `handleAcceptAll` (AC2).

4. **Ledger hygiene.** In `deferred-work.md`:
   - Annotate the item under `## Deferred from: skillars-deferred-36 implementation (2026-08-19)`
     (deferred-work.md line 1564) `[CLOSED by skillars-deferred-37 AC1, AC2]`, describing what shipped and the
     decision made (pruning with a reactivity-safe guard, plus decoupling `handleAcceptAll`'s notification from
     post-prune store state, no per-reference memoization — see "Why this story exists" above for the full
     rationale to carry into the closure note). Replace the existing `[PICKED UP by skillars-deferred-37 story
     creation, 2026-08-19]` tag on that line with the `[CLOSED ...]` tag rather than appending alongside it —
     every other closed item in this ledger carries exactly one bracket-tag, none carry both.
   - Do **not** touch any other line in the file — this story's creation re-read it end to end and confirmed
     everything else is already closed, stale, superseded, or an explicitly-accepted tradeoff.
   - `sprint-status.yaml`: add the
     `skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound` entry (already added at
     story-creation time by this workflow) and its `last_updated` note.

## Tasks / Subtasks

- [x] **Task 1 — AC1: prune `batchAcceptResultsByBatch` in `loadCoachBookingRequests()`, guarded**
  - [x] Add the `visibleBatchIds`/`currentEntries`/`prunedEntries` pruning block to `booking.store.js`'s
        `loadCoachBookingRequests`, immediately after `coachBatchGroups.value = res.batchGroups ?? []`, inside
        the existing `try` block
  - [x] Reassign `batchAcceptResultsByBatch.value` only when `prunedEntries.length !== currentEntries.length`
        (do **not** reassign unconditionally — see the AC1 rationale on why this guard exists)
  - [x] No change to the `catch`/`finally` branches or the function's `true`/`false` return contract
  - [x] Confirm by inspection that `handleAcceptAllBatch`'s own writes to `batchAcceptResultsByBatch` (the
        `null` placeholder, then the response data) are both written before its `loadCoachBookingRequests()`
        call, so pruning never races ahead of a fresh write (`booking.store.js:573-591`)
  - [x] `npx eslint src/stores/booking.store.js` clean
- [x] **Task 2 — AC2: decouple `handleAcceptAll`'s notification from post-prune store state**
  - [x] Change `handleAcceptAllBatch` (`booking.store.js:573-591`) to `return { refreshed, results:
        response.data }` instead of `return await loadCoachBookingRequests()`
  - [x] Change `handleAcceptAll` (`CoachBookingRequestsPage.vue:246-265`) to destructure `{ refreshed, results }`
        from `await bookingStore.handleAcceptAllBatch(batchId)`, call `notifyIfRequestsStale(refreshed)`, and
        compute `failedCount`/the accept/partial notification from that `results` value — not from
        `bookingStore.batchAcceptResultsByBatch[batchId]`
  - [x] No change to `handleAcceptAll`'s `catch` block (the `errorKey` branching) — `acceptAllBatch` throwing
        still bypasses the `return` entirely, so this branch is untouched
  - [x] `npx eslint src/stores/booking.store.js src/pages/coach/CoachBookingRequestsPage.vue` clean
- [x] **Task 3 — AC3: confirm no `resultByBatch`/`failureReasonFor` change needed**
  - [x] Verify by inspection that `resultByBatch`/`failureReasonFor` require no edit — their behavior for every
        input is unchanged, only the size of what `bookingStore.batchAcceptResultsByBatch` can ever contain
        shrinks, and AC2's diff is confined to `handleAcceptAll`
- [x] **Task 4 — AC4: ledger hygiene**
  - [x] Replace `[PICKED UP by skillars-deferred-37 story creation, 2026-08-19]` with
        `[CLOSED by skillars-deferred-37 AC1, AC2]` on the `skillars-deferred-36 implementation` item
        (deferred-work.md line 1564)
  - [x] `sprint-status.yaml` entry

### Review Findings

Code review (2026-08-19): Blind Hunter (diff-only, no repo access) + Edge Case Hunter (diff + repo read access) +
Acceptance Auditor (diff vs. this spec). Acceptance Auditor found 0 AC violations — AC1/AC2/AC3 all shipped
character-for-character against the spec's required code blocks — but flagged a non-blocking observation about
a dropped defensive fallback that turned out to be load-bearing (folded into the patch below). 13 Blind Hunter
findings raised; 11 verified false positives after independent re-verification against the live code (a claimed
"diff doesn't parse" fabricated fragment that does not exist in the actual diff; "only one call site updated"
re-verified via `grep -rn "handleAcceptAllBatch" src/frontend/src` — confirmed exactly one caller; a `batchId`
string/number type-mismatch concern — confirmed `UUID` serializes to a JSON string on both sides, per the
story's own note; "core invariant only in a comment" — accepted design tradeoff per this story's own rationale;
"no tests for a fourth consecutive fix" — pre-existing, already-documented, explicitly-accepted repo-wide gap;
"race only fixed for one caller" — re-verified moot, only one caller exists; "unverified error path on
`loadCoachBookingRequests` failure" — re-verified it never rethrows, per its own CONTRACT comment;
"comment-driven documentation" style nit; "optimization justified by an unproven assumption" — re-verified TRUE,
`approveBooking`/`rejectBooking` both call `loadCoachBookingRequests()`; `acceptingAll` reset — re-verified a
`finally` block does reset it). 1 finding merged into the patch below (the dropped `?? []` fallback). 1 finding
merged into a defer below (single-refresh-path assumption, corroborating an Edge Case Hunter finding). Edge Case
Hunter raised 5 findings: 3 merged into one critical patch (a genuine, verified-by-reading-the-code correctness
bug, not a hypothetical edge case), 2 became defers below.

- [x] [Review][Patch] **`response.data` is `undefined` on every call — `handleAcceptAllBatch`'s new `results:
  response.data` return value (AC2's own required code) always resolves to `undefined`, and `handleAcceptAll`'s
  `results.filter(...)` (also AC2's required code) throws `TypeError: Cannot read properties of undefined
  (reading 'filter')` on every single "Accept All" click, even fully successful ones.** Root cause:
  `src/frontend/src/boot/axios.js`'s response interceptor already unwraps to `response.data` before resolving
  the promise (`return response.data;`), so `acceptAllBatch(batchId)` (`src/frontend/src/api/booking.api.js:62`)
  resolves directly to the `List<BatchAcceptResult>` JSON array — not an `AxiosResponse`. Every other successful
  call site in `booking.store.js` already treats its resolved value this way (e.g. `getCoachBookingRequests()`'s
  `res.singleBookings ?? []`, no `.data`). The `[batchId]: response.data` write at `booking.store.js:599` was a
  **pre-existing** instance of this bug (unchanged context in this diff, not new) — it silently set
  `batchAcceptResultsByBatch[batchId]` to `undefined` since the function was introduced (`skillars-deferred-34`),
  masked because `resultByBatch`'s computed already guards with `if (!results) continue` and `failureReasonFor`
  guards with `!result` — both degrade to "no caption" rather than crashing. This story's diff adds a **second**,
  net-new instance at `:607` (`results: response.data`), and AC2 also deliberately removes the page's own
  `bookingStore.batchAcceptResultsByBatch[batchId] ?? []` fallback (the whole point of AC2 is to stop reading
  that store key) — so the `undefined` now reaches `handleAcceptAll`'s `results.filter(...)`
  (`CoachBookingRequestsPage.vue:254`) completely unguarded. The resulting `TypeError` is caught by the
  surrounding `catch` block, whose `errorKey` extraction (`err?.response?.data?.errorMsg?.errorKey`) finds
  nothing on a plain `TypeError` and falls to the `else` branch — the coach sees `booking.batch.acceptError`
  ("Failed to accept") on every accept-all, including fully successful ones, even though the batch was correctly
  accepted server-side. Confirmed by reading the interceptor, the endpoint's return type
  (`BookingBatchResource.java:50`, `ResponseEntity<List<BatchAcceptResult>>`), and every other `.data`-free call
  site in the same store file. **Applied:** `handleAcceptAllBatch` now awaits `acceptAllBatch(batchId)` into a
  variable named `results` (not `response`) and uses that value directly — no `.data` — both when writing
  `batchAcceptResultsByBatch.value[batchId]` and in the `{ refreshed, results }` return, matching every other
  successful-response call site's convention in this file. `npx eslint src/stores/booking.store.js
  src/pages/coach/CoachBookingRequestsPage.vue` clean post-patch.
  [`src/frontend/src/stores/booking.store.js:596-609`, `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:252-254`]
- [x] [Review][Defer] **`batchAcceptResultsByBatch` pruning only runs on `loadCoachBookingRequests`'s success
  path (per AC1's own explicit requirement, mirroring the function's existing stale-on-failure CONTRACT), so a
  streak of failed refreshes lets the map keep growing unboundedly for as long as the failures persist** — the
  exact growth this story exists to bound is not airtight under a specific real-world condition (a flaky
  network/backend). Spec-intentional, not an oversight; low priority, consistent with this story's own
  documented tradeoffs. [`src/frontend/src/stores/booking.store.js:321-353`]
- [x] [Review][Defer] **None of the store's five `loadCoachBookingRequests()` call sites (mount, accept-all,
  approve, reject, decline) sequence overlapping calls** — if two resolve out of order (e.g. a slow mount fetch
  resolving after a faster accept-all-triggered refresh), the stale response's snapshot of `coachBatchGroups` can
  prune away a just-written, still-relevant `batchAcceptResultsByBatch` entry, or fail to prune a genuinely stale
  one, desyncing displayed failure reasons from the visible batch groups. Pre-existing architectural gap — no
  request-sequence guarding existed at any of the five call sites before this diff either — not introduced or
  worsened by this story. [`src/frontend/src/stores/booking.store.js:302-353`]

## Dev Notes

### Established conventions this story must follow

- **Prune/derive state from the one function every refresh path already calls, rather than adding a new call
  site per caller.** `loadCoachBookingRequests()` is that function for this store (five existing call sites);
  AC1 adds one guarded block there instead of touching `handleAcceptAllBatch`'s own writes or any of the other
  four callers.
- **A reassignment that always allocates a new reference is a reactivity trigger, even when its content is
  unchanged.** AC1's guard (`prunedEntries.length !== currentEntries.length`) exists because
  `Object.fromEntries` never returns the same reference twice — this story's own code review caught that an
  unconditional reassignment would make `resultByBatch` rebuild on refreshes that prune nothing, which is the
  exact cost this story exists to bound, not add to.
- **A pruned/prunable ref is not a safe source for a caller that needs its own just-produced result.** AC2
  exists because `handleAcceptAll` read `batchAcceptResultsByBatch[batchId]` back out of the store *after*
  AC1's own pruning could have already removed it. Any future caller needing a function's own outcome should
  receive it via that function's return value, not by re-reading state the same call chain may have just
  pruned.
- **Do not widen a fix beyond what the ledger item actually asks for.** The item is specifically about
  `batchAcceptResultsByBatch`'s unbounded growth and `resultByBatch`'s resulting rebuild cost — not about
  `resultByBatch`'s own logic (already correct, per `skillars-deferred-36` AC2) or about a more sophisticated
  memoization scheme the ledger item's phrasing only floated as a possibility. AC2 is an exception scoped
  narrowly to a correctness gap AC1 itself introduces (see "Two gaps found by this story's own code review"
  above), not a widening of the original item. See "Explicitly NOT in this story" above.
- **A `[CLOSED by X ACn]` annotation on the exact line the item lives on, no rewriting of the item's own
  prose.** Matches this ledger's ~100 existing closures, most recently `skillars-deferred-36`'s two. A
  `[PICKED UP ...]` tag already on the line at close time is replaced, not left alongside `[CLOSED ...]` — no
  existing closure in this ledger carries both tags on one line.
- **When the ledger has exactly one item left and it is decision-gated, make the decision during story
  creation rather than deferring again.** Matches the `skillars-deferred-34` precedent (`sprint-status.yaml`'s
  own note: "ledger mined thin... chose to scope one larger, well-documented gap instead") — this story is that
  same move, one level smaller in scope than `skillars-deferred-34`'s was.

### Files being modified — current state and what must be preserved

- **`src/frontend/src/stores/booking.store.js`** (`:302-335` `loadCoachBookingRequests` and its CONTRACT
  comment, `:565-591` `batchAcceptResultsByBatch`/`handleAcceptAllBatch`) — AC1 adds one guarded pruning block
  inside `loadCoachBookingRequests`'s existing `try`, after the `coachBatchGroups` assignment. The CONTRACT
  comment's guarantees (never rethrows, returns `true`/`false`, leaves refs stale-but-untouched on failure) are
  unchanged and must keep holding after this edit — the pruning line is inside the same `try`, after the same
  point where `coachBatchGroups` itself is only assigned on success. AC2 changes `handleAcceptAllBatch`'s
  `return` statement only (from `return await loadCoachBookingRequests()` to
  `return { refreshed, results: response.data }`) — its own writes to `batchAcceptResultsByBatch` (the `null`
  placeholder, then the response data) are unchanged. No other function in this file changes.
- **`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`** (`:246-265` `handleAcceptAll`) — AC2 changes
  how `handleAcceptAll` sources its notification data (destructure `{ refreshed, results }` from
  `bookingStore.handleAcceptAllBatch(batchId)`'s return, instead of reading
  `bookingStore.batchAcceptResultsByBatch[batchId]` afterward). The `catch` block's `errorKey` branching, the
  `$q.notify` calls' i18n keys, and `resultByBatch`/`failureReasonFor` are all unchanged.

### Project Structure Notes

- No new REST endpoint, no DTO, no migration, no i18n keys, no production Java changes — this story is a
  frontend-only change: two functions in `booking.store.js` (`loadCoachBookingRequests`,
  `handleAcceptAllBatch`) and one function in `CoachBookingRequestsPage.vue` (`handleAcceptAll`).
- No new files. Both edited files are already tracked by prior stories in this same module family
  (`skillars-deferred-34`/`35`/`36`).
- No automated frontend test exists for `booking.store.js` in this repo (`find src/frontend/src -iname
  "*.spec.js" -o -iname "*.test.js"` returns zero hits; `package.json`'s `"test"` script is a no-op stub) — the
  same standing, already-documented gap `skillars-deferred-35`/`36` recorded. Verification for this story is by
  inspection plus ESLint, matching those stories' own precedent; this story does not add frontend test
  infrastructure (out of scope, would be a much larger, unrelated change).

### References

- `src/frontend/src/stores/booking.store.js:302-335` (`loadCoachBookingRequests` and its CONTRACT comment),
  `:565-591` (`batchAcceptResultsByBatch`/`handleAcceptAllBatch`)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:208-291` (`resultByBatch`, `failureReasonFor` —
  read for context, not modified; `handleAcceptAll` — modified by AC2)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:472-516`
  (`getCoachBookingRequests`, the `status='REQUESTED'` filter this story's pruning logic relies on)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40-68`
  (`expireStaleRequests`, one of the independent paths AC2's rationale traces that can drop a booking out of
  `REQUESTED` without any coach action)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementService.java:116-128`
  (`suspendCoach`, another such independent path — force-cancels every `REQUESTED` booking for the coach)
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

Implemented exactly the code the story specified for AC1 and AC2, no deviation:
- AC1: added the guarded pruning block to `loadCoachBookingRequests()` in `booking.store.js`, inside the
  existing `try`, immediately after `coachBatchGroups.value` is assigned. Reassignment is skipped when
  `prunedEntries.length === currentEntries.length` so the ref's reactivity is only triggered on refreshes that
  actually shrink the visible batch set.
- AC2: `handleAcceptAllBatch` now returns `{ refreshed, results: response.data }` instead of delegating its
  return value to `loadCoachBookingRequests()`'s own `true`/`false`. `CoachBookingRequestsPage.vue`'s
  `handleAcceptAll` destructures that return value instead of re-reading
  `bookingStore.batchAcceptResultsByBatch[batchId]` after the trailing refresh (which AC1's own pruning can by
  then have removed).
- AC3: verified by inspection — `resultByBatch`/`failureReasonFor` unchanged, confirmed via `git diff` scoping.
- AC4: `deferred-work.md` line 1564's `[PICKED UP ...]` tag replaced with `[CLOSED by skillars-deferred-37
  AC1, AC2]` plus a closure note; no other line in the file touched (confirmed via `git diff` scoping to that
  one line). `sprint-status.yaml`'s story entry was already present from story creation; flipped to
  `in-progress` at dev-story start.

### Completion Notes

All 4 ACs implemented exactly as specified in the story, frontend-only (`booking.store.js`,
`CoachBookingRequestsPage.vue`), no production Java/backend changes, no new files, no i18n changes.

- `grep -rn "batchAcceptResultsByBatch|handleAcceptAllBatch" src` confirms `handleAcceptAllBatch`'s
  return-shape change has exactly one consumer (`CoachBookingRequestsPage.vue`'s `handleAcceptAll`) — no other
  call site to update.
- `npx eslint src/stores/booking.store.js src/pages/coach/CoachBookingRequestsPage.vue` — clean, no errors.
- No automated frontend test infrastructure exists for `booking.store.js` in this repo (`find src -iname
  "*.spec.js" -o -iname "*.test.js"` returns zero hits — reconfirmed during this story, matching the
  already-documented standing gap from `skillars-deferred-35`/`36`). Verification is by inspection plus
  ESLint, per this story's own Dev Notes and the project's `docs/validation-strategy.md`
  smallest-relevant-scope policy; no backend tests are affected since no backend code changed. `mvn verify`
  was not run — not applicable to a frontend-only change and not required by validation-strategy.md's
  criteria for when it's appropriate.
- Verified `handleAcceptAll`'s `catch` block (`errorKey` branching) is byte-for-byte unchanged — `acceptAllBatch`
  throwing bypasses AC2's `return` entirely.
- Verified `resultByBatch`/`failureReasonFor` are byte-for-byte unchanged — `git diff` on
  `CoachBookingRequestsPage.vue` shows the only hunk is inside `handleAcceptAll`.

### Senior Developer Review (AI)

_To be filled by code-review._

## File List

- `src/frontend/src/stores/booking.store.js` (modified — AC1 pruning block in `loadCoachBookingRequests`; AC2
  `handleAcceptAllBatch` return-shape change)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` (modified — AC2 `handleAcceptAll` consumes
  `handleAcceptAllBatch`'s new return value instead of re-reading store state)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — AC4 ledger hygiene, line 1564)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — story status ready-for-dev → in-progress)

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: single-item story (ledger mined thin of decision-free candidates for the fourth pass in a row), scoping and deciding the `batchAcceptResultsByBatch` pruning approach re-filed by `skillars-deferred-36` AC3. |
| 2026-08-19 | Senior-dev code review of this story (`story-review.md`) found two gaps in the original AC1/AC2, folded in before dev-story: (1) AC1's pruning write was unconditional, which would have made `resultByBatch` rebuild on every refresh instead of only on ones that actually pruned something — AC1 now only reassigns when an entry was removed; (2) the story's investigation missed that `CoachBookingRequestsPage.vue`'s `handleAcceptAll` reads `batchAcceptResultsByBatch[batchId]` directly after AC1's pruning runs, which a concurrent decline/expiry/suspension/pause could turn into an incorrect "all accepted" notification — added as a new AC2 that has `handleAcceptAllBatch` return its own results directly instead. Original AC2/AC3 renumbered to AC3/AC4. |
| 2026-08-19 | Dev-story implementation: all 4 ACs done exactly as specified — AC1 guarded pruning in `loadCoachBookingRequests()`, AC2 `handleAcceptAllBatch`/`handleAcceptAll` return-value decoupling, AC3 verified no `resultByBatch`/`failureReasonFor` change needed, AC4 ledger hygiene (`deferred-work.md` line 1564 closed). ESLint clean on both edited frontend files. Status → review. |
