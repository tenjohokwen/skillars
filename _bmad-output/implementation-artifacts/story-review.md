# Senior Dev Review: skillars-deferred-37 (Batch-Accept Result-Map Pruning & Rebuild-Cost Bound)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound.md`
Method: every factual claim in the story was re-verified against current code (not taken on the story's word), and the two named consumers of `batchAcceptResultsByBatch` plus every path that can change a `Booking`'s status away from `REQUESTED` were traced end to end.

The story's core mechanism (AC1) is sound and its "current state" quotes match the code exactly. Two real gaps survived verification; one process nit is included for completeness.

**Status: Findings 1 and 2 fixed in the story (2026-08-19).** Both are now folded into the story document
itself: AC1 gained a length-check guard before reassigning `batchAcceptResultsByBatch`, and a new AC2 has
`handleAcceptAllBatch` return `{ refreshed, results }` directly with `handleAcceptAll` consuming that instead
of re-reading store state. Original AC2/AC3 renumbered to AC3/AC4. Finding 3 (ledger annotation ambiguity) was
also resolved — the new AC4/Task 4 explicitly says to replace the `[PICKED UP ...]` tag rather than append
alongside it.

---

## Finding 1 (Medium-High, confirmed): pruning write unconditionally busts `resultByBatch`'s cache on every refresh — the opposite of what the story sets out to fix

**Where:** `booking.store.js`, the AC1 pruning block, inside `loadCoachBookingRequests()`.

**Claim in the story:** pruning bounds `resultByBatch`'s rebuild cost to "what is currently visible" instead of letting it grow across the session.

**What actually happens:** `loadCoachBookingRequests()` is called from **five** sites — `approveBooking` (`:375`), `rejectBooking` (`:380`), `handleAcceptAllBatch`'s trailing call (`:584`), and directly from `CoachBookingRequestsPage.vue` at mount (`:294`) and inside its own approve/decline error-recovery paths (`:179`, `:200`). Verified today (pre-story), **only** `handleAcceptAllBatch` ever writes to `batchAcceptResultsByBatch` — approving/declining a single booking, and page mount, never touch it, so they never mark `resultByBatch`'s dependency dirty.

AC1's required code does:
```js
batchAcceptResultsByBatch.value = Object.fromEntries(
  Object.entries(batchAcceptResultsByBatch.value).filter(([batchId]) => visibleBatchIds.has(batchId))
)
```
`Object.fromEntries(...)` always allocates a **new object**, even when the filter removes nothing (e.g. on mount, filtering `{}` still produces a new `{}`). Vue's `ref` reactivity is reference-based (`hasChanged` = `!Object.is(newVal, oldVal)`), so this assignment is treated as a change on **every single call**, regardless of whether anything was actually pruned. Since `CoachBookingRequestsPage.vue`'s template already re-renders on every load (because `coachBatchGroups`/`coachBookingRequests` are also reassigned) and calls `failureReasonFor` per pending row, `resultByBatch.value` is read on that same render — so the now-forced-dirty computed actually re-executes its full `Object.entries`/`Map`-building body every time, not just gets marked dirty and skipped.

Net effect: after this change, **approving or declining a single unrelated booking, and every page mount, now also rebuilds `resultByBatch`** — work that never happened before this story and has nothing to do with batch results changing. The story fixes unbounded *growth* of what gets rebuilt but adds a new, previously-absent trigger that *fires the rebuild far more often*. At today's batch volumes this is cheap in absolute terms, but it works against the story's own stated goal and is easy to avoid.

**Suggested fix:** only reassign when the filter actually removed something, e.g.:
```js
const currentEntries = Object.entries(batchAcceptResultsByBatch.value)
const prunedEntries = currentEntries.filter(([batchId]) => visibleBatchIds.has(batchId))
if (prunedEntries.length !== currentEntries.length) {
  batchAcceptResultsByBatch.value = Object.fromEntries(prunedEntries)
}
```

---

## Finding 2 (Medium, confirmed): the story's investigation misses a second, non-computed consumer of `batchAcceptResultsByBatch` — pruning can make it feed an incorrect notification

**Where:** `CoachBookingRequestsPage.vue:251`, inside `handleAcceptAll`:
```js
const results = bookingStore.batchAcceptResultsByBatch[batchId] ?? []
const failedCount = results.filter((r) => !r.accepted).length
```

The story's "Why this story exists" section and AC2 both reason only about `resultByBatch` and `failureReasonFor` ("nothing in the template can query it... AC1 alone is sufficient"). `handleAcceptAll` reads `batchAcceptResultsByBatch` directly, bypassing `resultByBatch` entirely, and this read happens **after** `await bookingStore.handleAcceptAllBatch(batchId)` resolves — i.e. after AC1's pruning has already run as part of that same call.

Today this coincidentally can't misfire from the accept-all call alone: a batch is pruned iff it has zero `REQUESTED` bookings left, which — if accept-all's own results are the only thing changing booking status — is exactly the fully-succeeded case (`failedCount` should be `0` anyway). But that invariant depends on **nothing else** changing any of the batch's sibling bookings' status between the accept-all response and the refresh. That assumption doesn't hold:

- The UI itself allows it: each row has its own `handleDecline`/`declining[id]`, independent of `acceptingAll[batchId]` (`CoachBookingRequestsPage.vue:64-77`, `89`). A coach can decline a sibling row in the same batch while "Accept All" is still in flight or between its response and refresh — a concurrency pattern the store's own CONTRACT comment above `loadCoachBookingRequests` explicitly calls out as expected ("two rows accepted in quick succession... deliberately allow it").
- Independent backend paths can transition a `REQUESTED` sibling away from `REQUESTED` with no coach action at all: `BookingExpiryScheduler.expireStaleRequests()` (`BookingExpiryScheduler.java:40-68`, every 5 minutes) auto-declines stale requests; `AdminCoachEnforcementService.suspendCoach()` (`AdminCoachEnforcementService.java:117-121`) force-cancels every `REQUESTED` booking for a coach being suspended; `PackSessionService.pausePack()` → `BookingService.cancelDueToPause()` cancels `REQUESTED` bookings belonging to a paused pack. `BookingBatchService.acceptAll`'s own coach-suspended check is deliberately **unlocked** (`BookingBatchService.java:253-259`, "taking the coach lock... would make every per-booking transaction block"), so a suspension landing mid-loop is an anticipated race, not a stretch.

If any of these independently drives the batch's *last* still-`REQUESTED` sibling out of that status in the window between accept-all's response and the next `loadCoachBookingRequests()` refresh — while that sibling was recorded as a genuine failure in `response.data` — the batch gets pruned before `handleAcceptAll` reads it back. `results` falls back to `[]`, `failedCount` becomes `0`, and the coach sees "All sessions accepted" (`booking.batch.acceptedAll`) even though one sibling actually failed to accept. **Before this story, this read was always accurate** because nothing ever pruned the entry; pruning introduces this failure mode.

This is a narrow, timing-dependent case, not a routine one — but it's real, reachable through already-documented concurrency the codebase anticipates, and the story never analyzes this read site at all despite explicitly claiming to have traced every queryable consumer.

**Suggested fix:** decouple the notification from post-refresh store state — e.g. have `handleAcceptAllBatch` return `response.data` (or the failed count) directly to its caller instead of requiring `handleAcceptAll` to re-read a value that a concurrent refresh may have already pruned. If this risk is judged acceptable as-is, the story should say so explicitly rather than asserting (incorrectly) that only `resultByBatch`/`failureReasonFor` can ever observe pruning.

---

## Finding 3 (Low, process nit): AC3's ledger annotation is ambiguous about the existing `[PICKED UP]` tag

`deferred-work.md:1567` already carries `[PICKED UP by skillars-deferred-37 story creation, 2026-08-19]` (added by this story's own creation step). AC3/Task 3 says to add `[CLOSED by skillars-deferred-37 AC1]` but doesn't say whether that **replaces** the `PICKED UP` tag or sits alongside it. Every other closed item in the ledger carries exactly one bracket-tag — `grep` finds zero lines with both `PICKED UP` and `CLOSED` co-existing — so replacement is clearly the intended convention, but the story doesn't say so, leaving room for a dev to append instead and produce a first-of-its-kind dual-tagged line.

---

## Checked, no issue found (to save the next reader re-litigating these)

- **`batchId` type consistency:** `group.batchId` (from the JSON response) and the keys written into `batchAcceptResultsByBatch` (via `handleAcceptAllBatch(batchId)`, called with `group.batchId` from the template) are both plain strings — `Set.has` comparison in AC1 is safe, no coercion needed.
- **Stale-on-failure consistency:** pruning sits inside the `try`, after the `coachBatchGroups` assignment, so a failed refresh leaves `batchAcceptResultsByBatch` exactly as stale as the rest of the page state — matches the CONTRACT comment's guarantee and the story's own AC1 requirement.
- **Mid-flight `null`-placeholder pruning:** a concurrent, unrelated refresh *can* transiently prune an in-flight accept-all's `null` placeholder before that call's own response arrives, but `handleAcceptAllBatch` unconditionally rewrites the key with `response.data` right after, and its own trailing `loadCoachBookingRequests()` immediately re-evaluates visibility — so the final state is unaffected. Only a momentary internal state flicker, not observable.
- **`sprint-status.yaml`/ledger read-through claims:** the "0 PICKED UP / 103 CLOSED" counts and the `## Deferred from: skillars-deferred-36 implementation (2026-08-19)` section/line number are accurate as of the current file (the single `PICKED UP` entry present now was added by this story's own workflow after that count was taken — consistent, not a discrepancy).

---

## Recommendation

Do not block on Finding 3 (documentation clarity only). Findings 1 and 2 are both real and both fixable with small, targeted changes that stay within this story's stated scope (no redesign, no memoization) — worth resolving before or during `dev-story` rather than deferring again, since Finding 2 in particular contradicts a claim the story uses to justify AC2's "no diff needed" conclusion.
