# Senior Dev Review: skillars-deferred-38 (Coach-Booking-Requests Refresh Request-Sequencing Guard)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-38-coach-refresh-request-sequencing-guard.md`
Method: every factual claim in the story was re-verified against current code (not taken on the story's word) —
all seven call sites of `loadCoachBookingRequests()` were located and read directly, the existing CONTRACT
comment was read in full, and the mechanism was traced through every combination of call-order vs.
resolve-order for two overlapping calls.

The story's core mechanism (AC1) is sound: a monotonically-increasing sequence counter, captured
synchronously before the function's first `await`, correctly makes call-issuance order (not resolve order)
authoritative, and is a no-op in the non-concurrent case. One real gap survived verification; it was folded
into the story before finalizing rather than left for `dev-story` to discover.

**Status: Findings 1 and 2 fixed in the story (2026-08-19).** A second review pass caught that Finding 1's own
fix (rewording the shared "RETURNS ITS OWN OUTCOME" sentence) broke accuracy for `loadCoachSchedule`, which
shares that sentence with `loadCoachBookingRequests` but gets no superseded-call guard in this story. The net
result, after both passes: the shared sentence at `:312` is left completely untouched; the superseded-call
semantics live only in the new `loadCoachBookingRequests`-specific paragraph, which now also carries an
explicit contrast to `loadCoachSchedule` so a reader can't assume the guarantee applies there too. Task 1
updated to match both times.

---

## Finding 1 (Medium, confirmed): the original draft's "do not reword any existing CONTRACT sentence" instruction contradicted its own required code

**Where:** AC1's required-code block and Task 1, first draft.

The first draft added a new paragraph to the CONTRACT comment describing the sequencing guarantee, but left
the existing sentence "Each therefore RETURNS ITS OWN OUTCOME: true if this invocation refreshed, false if it
failed" untouched, and Task 1 explicitly instructed "do not remove or reword any existing CONTRACT sentence."

That instruction is wrong given what AC1's own code does: once the guard ships, a **superseded** call also
returns `true`, despite not having refreshed anything itself — the existing sentence's literal claim ("true if
this invocation refreshed") stops precisely describing the new behavior. This is not a correctness bug in the
shipped code (the AC1 rationale on why `true` is the right return value for a superseded call is sound — a
`false` would produce a spurious staleness toast on the older of two concurrent calls even when the newer one
lands fine), but leaving the comment's wording unchanged would make it actively misleading about what `true`
now guarantees, for the next reader who has no reason to know a superseded call exists — the same category of
"is the doc comment still true after this diff" question `skillars-deferred-38`'s own predecessor stories treat
seriously (`skillars-deferred-31`'s CONTRACT-comment addition, `skillars-deferred-37`'s two-decision CONTRACT
extension).

**Applied:** AC1's required code now explicitly rewords that one sentence — "true if this invocation refreshed
OR was itself superseded by a newer call ... false only if it failed and was not superseded" — leaving every
other existing CONTRACT sentence untouched, and Task 1 now names this as the one sentence that must change
rather than forbidding all rewording.

---

## Checked, no issue found (to save the next reader re-litigating these)

- **Increment-before-first-`await` ordering:** confirmed JavaScript runs all synchronous code up to a
  function's first `await` without yielding, so two calls issued in the same tick (e.g. a click handler and a
  watcher firing back-to-back) cannot interleave their `++coachRequestsSequence` reads — issuance order and
  sequence-number order are guaranteed to agree. No lock is needed.
- **Resolve-order independence:** traced all four orderings of two overlapping calls (older-issued
  resolves-first-success, resolves-last-success, resolves-first-failure, resolves-last-failure, crossed with
  the newer call's own success/failure) — in every case the guard correctly lets only the call matching the
  *current* `coachRequestsSequence` value commit state, regardless of which one's HTTP response actually lands
  first.
- **`finally` guard correctness:** without guarding `finally`, an older call resolving after a newer call has
  already started (but not yet finished) would clear `coachRequestsLoading` mid-flight for the newer call,
  causing a premature loading-spinner disappearance. The guard added in AC1 prevents this; verified against
  Vue's `ref` semantics (plain boolean assignment, no special batching interaction with this guard).
- **Interaction with `skillars-deferred-37`'s pruning block:** the pruning block sits textually after the new
  `requestId` check inside the `try`, so a superseded call's early `return true` correctly skips pruning
  entirely — it never reads or writes `batchAcceptResultsByBatch` using a stale `coachBatchGroups` snapshot.
- **Interaction with `handleAcceptAllBatch`'s own writes to `batchAcceptResultsByBatch`:** those writes (the
  `null` placeholder, then the response data) happen inside `handleAcceptAllBatch` itself, before it calls
  `loadCoachBookingRequests()`, and are not gated by the new sequence counter — only the *pruning read* of
  `batchAcceptResultsByBatch` inside `loadCoachBookingRequests()` is. If `handleAcceptAllBatch`'s own trailing
  refresh call is later superseded by a newer call (e.g. a concurrent mount), the newer call's own pruning pass
  — using its own freshly-loaded `coachBatchGroups` — is what actually decides the batch's fate, which is
  exactly the correctness property this story exists to establish. `handleAcceptAllBatch`'s return value is
  unaffected either way, since `skillars-deferred-37` AC2 already made it return its own local `results`
  variable rather than re-reading the store.
- **Scope boundary against the sibling ledger item (line 1568):** re-read `skillars-deferred-37`'s own AC1
  rationale for "prune only on the success path" and confirmed it is an explicit, reasoned decision (mirrors
  the pre-existing stale-on-failure CONTRACT), not an oversight — correctly left out of this story's ACs.
- **Call-site count:** `grep -n "loadCoachBookingRequests()" src/frontend/src/stores/booking.store.js
  src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` returns exactly seven hits (three internal, four
  in the page), matching the story's own count and requiring no edits at any of them, confirming the guard
  really is fully internal to the one function.

---

## Second-pass review (2026-08-19)

Re-verified against current code a second time, specifically checking AC1's required CONTRACT-comment diff
against everything else that comment block documents (not just against `loadCoachBookingRequests` itself,
which the first pass already checked thoroughly). One new gap found; not yet applied to the story.

### Finding 2 (Medium, confirmed): AC1's reworded sentence is shared with `loadCoachSchedule` and becomes false for it

**Where:** `booking.store.js:302-320` — the CONTRACT comment block AC1 edits sits above **both**
`loadCoachBookingRequests` (`:321-353`) and `loadCoachSchedule` (`:355-368`), not above
`loadCoachBookingRequests` alone. Its opening line says so explicitly: "CONTRACT — `loadCoachBookingRequests`
and `loadCoachSchedule` below NEVER RETHROW." Every sentence in the block, including the one at `:312` AC1
targets, is written as "Each ..." — a single shared description of both functions' return-value contract, not
two separate comments that happen to be adjacent.

AC1's required diff rewords line 312 from "true if this invocation refreshed, false if it failed" to "true if
this invocation refreshed **OR was itself superseded by a newer call** ... false only if it failed **and was
not superseded**" — but leaves the sentence's "Each" subject untouched. That reword is correct for
`loadCoachBookingRequests` (which is what this story is actually giving the new superseded-call semantics to)
but becomes **false for `loadCoachSchedule`**, which this story's own "Considered and rejected" section
explicitly declines to touch ("Applying the same guard to `loadParentBookings`/`loadCoachSchedule`" — rejected
as scope creep). Confirmed by reading `loadCoachSchedule` directly (`:355-368`): it has no sequence counter, no
`requestId` capture, and no superseded-call check anywhere in its body — its `true`/`false` return still means
exactly what the *original*, unreworded sentence said. After AC1 ships as currently specified, the shared
comment would claim `loadCoachSchedule` also "return[s] true ... if ... superseded by a newer call," which is
not true of the shipped code — a reader debugging a stale-schedule-warning bug that goes looking at this
comment would be told a mechanism exists that doesn't.

This is the same category of defect `story-review.md`'s own Finding 1 was about (a CONTRACT sentence no longer
matching what the code guarantees after this diff) — it just wasn't visible until checking the sentence against
the *second* function the shared comment describes, not only against `loadCoachBookingRequests` itself.

**Applied (2026-08-19):** the shared "Each ... RETURNS ITS OWN OUTCOME" sentence at `:312` is no longer
reworded — AC1's required code now leaves it exactly as-is (accurate for both functions, unchanged from before
this story) and instead adds one clarifying clause to the end of the already-planned
`loadCoachBookingRequests`-specific paragraph ("...unlike `loadCoachSchedule`, which carries no such guard and
whose true/false always reflects whether that specific call itself refreshed"). Task 1 updated to match: it now
explicitly says not to reword `:312` or any other existing sentence, and explains why.
