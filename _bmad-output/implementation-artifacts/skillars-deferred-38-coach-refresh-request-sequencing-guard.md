# Story Deferred-38: Coach-Booking-Requests Refresh Request-Sequencing Guard

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want `bookingStore.loadCoachBookingRequests()` to ignore the response from a call that has been superseded by
a newer one, instead of letting whichever HTTP response happens to land last win regardless of when it was sent,
so that a coach's booking-requests inbox and `batchAcceptResultsByBatch` never desync from a slower, older
refresh overwriting a faster, newer one.

### Why this story exists

Drawn from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: code review of skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound
(2026-08-19)` section (line 1569) — one of two items that section's code review filed.

This story's creation re-read `deferred-work.md` end to end (all 1569 lines) a fifth time. `grep -c "\[PICKED
UP"` returns 0 and `grep -c "\[CLOSED"` returns 104 — every previously-filed item is either shipped, stale,
superseded, or an explicitly accepted low-priority tradeoff with its own reasoning already on record. `git diff
34b4d16..HEAD -- _bmad-output/implementation-artifacts/deferred-work.md` (the `skillars-deferred-36` →
`skillars-deferred-37` span, the last point a full read-through was recorded) confirms the only change since is
`skillars-deferred-37`'s own `[CLOSED ...]` annotation on the `skillars-deferred-36` item plus a new two-item
`## Deferred from: code review of skillars-deferred-37-...` section — nothing else in the file is new. The
ledger is mined thin of unrelated small items for the fifth story in a row (`skillars-deferred-34` through
`37` each recorded the same finding).

**Both new items were re-verified against current code during this story's creation. Only one is a genuine
candidate; the other is not.**

- **Line 1568 — `batchAcceptResultsByBatch` pruning only runs on `loadCoachBookingRequests`'s success path.**
  Re-read `skillars-deferred-37`'s own AC1 rationale: this was an explicit, considered requirement — pruning
  must mirror the function's pre-existing "leave refs stale-but-untouched on failure" CONTRACT (`booking.store.js
  :302-320`), not silently start being more aggressive than the rest of the page's data on a failed refresh. The
  item's own text calls it "spec-intentional, not an oversight," matching dozens of other permanently-accepted
  low-priority tradeoffs already on record throughout this ledger (e.g. the `docker-compose.local.yml` redis
  bind-mount note, the `ConfigService` TTL note, the batch-path overlap-check asymmetry note — none of these
  have ever been converted into a story across 37 prior passes). Revisiting an already-made, explicitly-reasoned
  decision without new evidence is not this story's job. **Left alone — no AC targets this item.**
- **Line 1569 — none of `loadCoachBookingRequests()`'s five call sites (mount, accept-all, approve, reject,
  decline) sequence overlapping calls.** Re-verified by reading every call site directly (`grep -n
  "loadCoachBookingRequests()" src/frontend/src/stores/booking.store.js
  src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`, seven hits: three internal — `approveBooking`
  `:393`, `rejectBooking` `:398`, `handleAcceptAllBatch` `:611` — and four in the page — the three
  post-catch-block direct calls (`handleAccept` `:179`, `handleDecline` `:200`, `handleAcceptAll` `:267`) plus
  `onMounted` `:296`). This is a genuine, reachable race: the per-row `accepting[id]`/`declining[id]` flags and
  the per-batch `acceptingAll[batchId]` flag are all independent (`CoachBookingRequestsPage.vue`), so a coach
  can trigger several of these calls within the same render — e.g. approving one row while a slower mount fetch
  is still in flight — and nothing stops an older-issued response from applying its data after a newer one
  already has, silently reverting the page to stale state or pruning a `batchAcceptResultsByBatch` entry a
  fresher call had already decided to keep. Unlike the line-1568 item, this is not framed as an accepted
  tradeoff — it is framed as a real, pre-existing gap with no design decision recorded against it anywhere else
  in the ledger. **This is the one candidate for this story.**

**Decision made during this story's creation:** add a monotonically-increasing request-sequence counter, local
to the store, that `loadCoachBookingRequests()` increments on every invocation and checks before committing
either outcome (success or failure) to state. A call whose sequence number no longer matches the counter's
current value has been superseded by a newer call issued after it — its response is discarded without touching
`coachBookingRequests`, `coachBatchGroups`, `coachRequestsError`, `batchAcceptResultsByBatch`'s pruning, or (via
a matching `finally` guard) `coachRequestsLoading`. This is the standard "last request issued wins" pattern and
needs no new call sites, no cancellation plumbing (no `AbortController` — the underlying `fetch`/axios call
still completes normally, its result is just not applied), and no change to any of the seven call sites' own
code — the guard lives entirely inside `loadCoachBookingRequests()` itself, the one function every one of them
already funnels through, matching the same "fix at the one convergence point" convention `skillars-deferred-37`
AC1 established for this exact function.

**Considered and rejected:**

- **`AbortController`-based cancellation of the in-flight `fetch`/axios call.** Would additionally free the
  network request itself, which the sequence-counter approach does not — a superseded call's response still
  downloads before being discarded. Rejected as unjustified complexity for this endpoint: `getCoachBookingRequests`
  returns a small, per-coach payload (a handful of single bookings and batch groups), not a bulk resource where
  wasted transfer matters, and wiring an `AbortController` through the shared `boot/axios.js` interceptor (which
  already unwraps every response to `response.data` before any caller sees it — the same interceptor
  `skillars-deferred-37`'s own critical patch had to account for) would be a wider, riskier change than this
  story's scope justifies for the same outcome the counter already achieves.
- **Applying the same guard to `loadParentBookings`/`loadCoachSchedule`.** Both have fewer, less-concurrent call
  sites than `loadCoachBookingRequests` (`loadParentBookings`: 3 call sites — `submitBookingRequest`, plus two
  pack-purchase/pause flows that are already sequential user actions, not independently-triggerable per-row
  flags; `loadCoachSchedule`: driven by a single week-selector watcher, not multiple simultaneous user actions),
  and the ledger item names only `loadCoachBookingRequests`'s five sites specifically. Widening the fix to
  functions the item does not name would be scope creep beyond what a bundled small-fix story should do — matches
  `skillars-deferred-37`'s own "do not widen a fix beyond what the ledger item actually asks for" convention.
- **A per-call-site debounce or disabling the triggering button while a refresh is in flight.** Would reduce how
  often the race is reachable but not close it (two calls can still overlap — e.g. a mount fetch racing a
  same-instant accept — and UI-level debouncing is a UX decision, not a correctness fix for the underlying data
  race). The sequence guard closes the race unconditionally regardless of how many overlapping calls occur.

## Deferred Item(s) Closed

| Source | Item | Current location (re-verified 2026-08-19) | AC | Planned outcome |
|---|---|---|---|---|
| Code review of `skillars-deferred-37` (2026-08-19) | None of `loadCoachBookingRequests()`'s five call sites sequence overlapping calls | `src/frontend/src/stores/booking.store.js:302-353` | 1 | A local, monotonically-increasing request-sequence counter is checked before either outcome branch commits state, so a response from a call superseded by a newer one is silently discarded instead of applied |

**Explicitly NOT in this story** (considered during story creation and rejected — see rationale above):

- The line-1568 item (pruning only runs on the success path) — left alone as an already-made, explicitly-reasoned
  design decision, not a defect.
- `AbortController`-based request cancellation.
- Extending the same sequencing guard to `loadParentBookings`/`loadCoachSchedule`.
- Any UI-level debounce or button-disabling change.
- Any change to `batchAcceptResultsByBatch`'s pruning logic itself (`skillars-deferred-37` AC1), `resultByBatch`,
  or `failureReasonFor` — this story only guards which response `loadCoachBookingRequests()` is allowed to
  commit; it does not change what a committed response does once accepted.

## Acceptance Criteria

1. **`bookingStore.loadCoachBookingRequests()` discards the outcome of any call superseded by a later one,**
   via a local, monotonically-increasing sequence counter checked before either branch commits state, and before
   `coachRequestsLoading` is cleared.

   **Verified current state (`booking.store.js:117-120,302-353`):**
   ```js
   const coachBookingRequests = ref([])
   const coachBatchGroups = ref([])
   const coachRequestsLoading = ref(false)
   const coachRequestsError = ref(null)
   ```
   ```js
   // CONTRACT — loadCoachBookingRequests and loadCoachSchedule below NEVER RETHROW. ...
   async function loadCoachBookingRequests() {
     coachRequestsLoading.value = true
     coachRequestsError.value = null
     try {
       const res = await getCoachBookingRequests()
       coachBookingRequests.value = res.singleBookings ?? []
       coachBatchGroups.value = res.batchGroups ?? []
       // ...skillars-deferred-37 pruning block, unchanged by this story...
       return true
     } catch (e) {
       coachRequestsError.value = e
       return false
     } finally {
       coachRequestsLoading.value = false
     }
   }
   ```

   **Required — add the counter beside the four refs it guards:**
   ```js
   const coachBookingRequests = ref([])
   const coachBatchGroups = ref([])
   const coachRequestsLoading = ref(false)
   const coachRequestsError = ref(null)
   // Non-reactive by design — this is an internal ordering token for loadCoachBookingRequests below, not
   // page-visible state. Matches this file's existing plain-`let` counter pattern (useBookingSse's
   // retryCount).
   let coachRequestsSequence = 0
   ```

   **Required — guard both outcome branches and the `finally`, with a new paragraph appended to the CONTRACT
   comment.** The shared "Each therefore RETURNS ITS OWN OUTCOME: true if this invocation refreshed, false if
   it failed" sentence at `:312` **must NOT be reworded.** That sentence's subject is "Each" — it describes
   **both** `loadCoachBookingRequests` and `loadCoachSchedule` (the CONTRACT block's opening line names both,
   and every sentence in the block is written as a single shared description of both functions). Only
   `loadCoachBookingRequests` gets the superseded-call guard in this story — `loadCoachSchedule` is explicitly
   out of scope (see "Considered and rejected" above) and its `true`/`false` continues to mean exactly what the
   unreworded sentence already says. Rewording the shared sentence to mention "superseded by a newer call" would
   make it **false for `loadCoachSchedule`**, which has no sequence counter and no superseded-call check. The
   superseded-call exception belongs only in the new, already-scoped paragraph below — which sits after the
   shared block and is named to `loadCoachBookingRequests` specifically — with one clause added to make the
   contrast to `loadCoachSchedule` explicit for the next reader:
   ```js
   // CONTRACT — loadCoachBookingRequests and loadCoachSchedule below NEVER RETHROW. ...
   // [existing CONTRACT text unchanged, INCLUDING the "Each therefore RETURNS ITS OWN OUTCOME: true if this
   // invocation refreshed, false if it failed" sentence at :312 — do not touch it] ...
   //
   // loadCoachBookingRequests is additionally guarded against out-of-order responses: it is called from five
   // sites (mount, accept-all, approve, reject, decline) whose calls can overlap, since the per-row
   // accepting[id]/declining[id] and per-batch acceptingAll[batchId] flags are independent and place no limit
   // on triggering several at once. A monotonically-increasing sequence counter (coachRequestsSequence) is
   // captured per call; if a newer call has started by the time this one's response arrives, this call's
   // outcome — success or failure — is discarded without touching any ref, including coachRequestsLoading, so
   // an older, slower response can never overwrite a newer, faster one. This means the shared "true if this
   // invocation refreshed" sentence above is imprecise for THIS function alone — a superseded call also
   // returns true — unlike loadCoachSchedule, which carries no such guard and whose true/false always reflects
   // whether that specific call itself refreshed. (skillars-deferred-38)
   async function loadCoachBookingRequests() {
     const requestId = ++coachRequestsSequence
     coachRequestsLoading.value = true
     coachRequestsError.value = null
     try {
       const res = await getCoachBookingRequests()
       if (requestId !== coachRequestsSequence) return true
       coachBookingRequests.value = res.singleBookings ?? []
       coachBatchGroups.value = res.batchGroups ?? []
       // ...skillars-deferred-37 pruning block, unchanged by this story...
       return true
     } catch (e) {
       if (requestId !== coachRequestsSequence) return true
       coachRequestsError.value = e
       return false
     } finally {
       if (requestId === coachRequestsSequence) coachRequestsLoading.value = false
     }
   }
   ```

   **Why `return true` for a superseded call, not `false`:** the function's return value drives
   `notifyIfRequestsStale` at every call site (`CoachBookingRequestsPage.vue`) — `false` triggers a "list may be
   out of date" toast. A superseded call's own fetch did not fail; it was simply pre-empted by a newer one that
   either already has applied (if it resolved first) or still will (if it is still in flight) — the coach's list
   is not stale because of anything this call did. Returning `false` here would produce a spurious staleness
   warning on the *older* of two concurrent calls even when the *newer* one lands perfectly fine, which is a
   worse UX regression than the desync this story exists to close. If the newer call itself fails, its own
   `catch` branch (not the older, superseded one) is what sets `coachRequestsError`/returns `false` — ownership
   of the outcome always belongs to whichever call is current at settle time.

   **Why guard `finally` too:** without it, an older call resolving after a newer one starts (but before the
   newer one finishes) would clear `coachRequestsLoading` while the newer call is still in flight, making any
   loading spinner disappear prematurely. Guarding `finally` the same way keeps the loading flag owned by
   whichever call is current, exactly like the two outcome branches.

   **Why increment before the first `await`, not inside `try`:** JavaScript runs everything before a function's
   first `await` synchronously — two calls issued back-to-back (e.g. `onMounted` firing while a click handler's
   `await bookingStore.approveBooking(id)` is already running) can never interleave their `++coachRequestsSequence`
   reads, so call order and sequence-number order always agree. No lock or additional guard is needed for the
   increment itself.

   **Behavior-preserving for the non-concurrent case — which is the overwhelming majority of real usage:** when
   only one call to `loadCoachBookingRequests()` is ever in flight at a time (the common case — a coach reviewing
   one batch, mounting the page once), `requestId` always still equals `coachRequestsSequence` at both check
   points, since nothing else increments it in between. Both guards are then no-ops and the function's behavior
   is byte-identical to before this story. The guard only changes behavior on the genuinely concurrent path this
   story exists to fix.

   **No change to:** the pruning block AC1 of `skillars-deferred-37` added (still runs only inside the success
   branch, only after the `requestId` check passes, unchanged code), the function's `true`/`false` return
   contract for a call that is *not* superseded, `loadCoachSchedule` or any other function, or any of the seven
   call sites — none of them need to change, since the guard is entirely internal to `loadCoachBookingRequests()`.

2. **Ledger hygiene.** In `deferred-work.md`:
   - Annotate the item at line 1569 (`## Deferred from: code review of
     skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound`) `[CLOSED by
     skillars-deferred-38 AC1]`, describing the sequence-counter mechanism shipped.
   - Do **not** touch line 1568 (the pruning-only-on-success-path item) or any other line — this story's
     creation re-read the file end to end and confirmed everything else is already closed, stale, superseded,
     or an explicitly-accepted tradeoff. Line 1568 in particular is deliberately left open per the "Why this
     story exists" rationale above.
   - `sprint-status.yaml`: add the
     `skillars-deferred-38-coach-refresh-request-sequencing-guard` entry (already added at story-creation time
     by this workflow) and its `last_updated` note.

## Tasks / Subtasks

- [x] **Task 1 — AC1: add the request-sequencing guard to `loadCoachBookingRequests()`**
  - [x] Add `let coachRequestsSequence = 0` in `booking.store.js`, immediately after the `coachRequestsError`
        `ref` declaration (`:120`)
  - [x] Capture `const requestId = ++coachRequestsSequence` as the first line inside `loadCoachBookingRequests`,
        before `coachRequestsLoading.value = true`
  - [x] Add `if (requestId !== coachRequestsSequence) return true` as the first line inside the `try`, right
        after `const res = await getCoachBookingRequests()` and before `coachBookingRequests.value = ...`
  - [x] Add the identical `if (requestId !== coachRequestsSequence) return true` as the first line inside the
        `catch (e)` block, before `coachRequestsError.value = e`
  - [x] Change the `finally` block's `coachRequestsLoading.value = false` to
        `if (requestId === coachRequestsSequence) coachRequestsLoading.value = false`
  - [x] Extend the CONTRACT comment with the new sequencing-guarantee paragraph (required text above),
        including its closing clause contrasting with `loadCoachSchedule`. Do **not** reword the existing
        shared "RETURNS ITS OWN OUTCOME" sentence (`:312`) or any other existing CONTRACT sentence — that
        sentence also describes `loadCoachSchedule`, which gets no superseded-call guard in this story, so
        rewording it to mention supersession would make it false for that function
  - [x] Confirm by inspection that the existing `skillars-deferred-37` pruning block is untouched and still runs
        only after the new `requestId` check passes
  - [x] `npx eslint src/stores/booking.store.js` clean
- [x] **Task 2 — AC2: ledger hygiene**
  - [x] Replace the line-1569 item's lack of a bracket tag with `[CLOSED by skillars-deferred-38 AC1]` plus a
        closure note describing the sequence-counter mechanism
  - [x] Leave line 1568 untouched
  - [x] `sprint-status.yaml` entry

### Review Findings

- [x] [Review][Defer] Stuck `coachRequestsLoading` spinner if the latest-issued call never settles [`src/frontend/src/stores/booking.store.js:369`] — deferred, pre-existing. The `finally` guard means only the most-recently-issued call's `finally` can clear `coachRequestsLoading`; with no `AbortController`/request timeout configured anywhere in `boot/axios.js`, a hung latest-issued request leaves the spinner stuck indefinitely even if an earlier, superseded call resolves. Low-probability (requires a genuinely hung HTTP request) but zero mitigation exists, and this specific consequence was not weighed by the story's own `AbortController`-rejection rationale (which evaluated only redundant-bandwidth cost).
- [x] [Review][Defer] No automated test coverage for the concurrency guard [`src/frontend/src/stores/booking.store.js:326-371`] — deferred, pre-existing. Standing repo-wide gap — no frontend test harness exists for `booking.store.js` (`skillars-deferred-35`/`36`/`37` precedent).
- [x] [Review][Defer] Discarded superseded-call failures produce no diagnostic trace [`src/frontend/src/stores/booking.store.js:365`] — deferred, pre-existing. A genuinely-failed but superseded request's error is silently dropped with no logging, undermining the file's own "future error-banner treatment" comment elsewhere. Minor observability gap, optional polish.

**Review summary:** Blind Hunter + Edge Case Hunter + Acceptance Auditor, 0 AC violations (implementation matches the spec's required diff verbatim, confirmed by the Acceptance Auditor). 16 raw findings across the three layers; 1 pair merged (Blind Hunter's stuck-loading finding + Edge Case Hunter's identical finding); 3 deferred (above), 12 dismissed as false positives after verification against the actual file/story context — notably a claimed "self-contradictory" `[CLOSED]`-vs-`review` status pairing (matches this ledger's own established convention across ~104 prior closures), claims about `useBookingSse`'s `retryCount` pattern and `loadCoachSchedule` lacking a guard (both confirmed true by the Acceptance Auditor's full-file read), and the explicitly-considered-and-rejected `AbortController`/debounce/scope-widening items already reasoned about in this story's own "Considered and rejected" section.

## Dev Notes

### Established conventions this story must follow

- **Guard at the one convergence point every call site already passes through, rather than touching each call
  site.** `loadCoachBookingRequests()` is that point (seven call sites, five conceptual triggers per the ledger
  item's own framing); this story adds the guard entirely inside that one function, matching
  `skillars-deferred-37` AC1's identical choice for the pruning fix in the same function.
- **A superseded call's outcome must never mutate state its own return value's semantics don't already cover.**
  `notifyIfRequestsStale` at every call site treats `false` as "warn the coach" — a superseded call returning
  `false` would be a false-positive warning on a call that did not actually fail, just lost a race to a newer
  one. Ownership of the true/false outcome belongs to whichever call is current when it settles.
- **Do not widen a fix beyond what the ledger item actually names.** The item is specifically about
  `loadCoachBookingRequests`'s five call sites — not about `loadParentBookings`/`loadCoachSchedule` (different,
  lower-concurrency call patterns), not about network-level cancellation, and not about the separate,
  already-decided line-1568 item in the same ledger section. See "Explicitly NOT in this story" above.
- **A `[CLOSED by X ACn]` annotation on the exact line the item lives on, no rewriting of the item's own prose.**
  Matches this ledger's ~104 existing closures.
- **When the ledger has exactly one actionable item left, make the decision during story creation rather than
  deferring again; when an item is already an explicitly-reasoned accepted tradeoff, leave it alone rather than
  manufacturing a fix for it.** Matches the `skillars-deferred-34`/`37` precedent for the first half, and this
  story's own line-1568 analysis for the second half — not every open ledger line is a story candidate.

### Files being modified — current state and what must be preserved

- **`src/frontend/src/stores/booking.store.js`** (`:117-120` the four `coachRequests*` refs, `:302-353`
  `loadCoachBookingRequests` and its CONTRACT comment) — this story adds one `let` declaration and a `requestId`
  capture/check inside `loadCoachBookingRequests` only. The CONTRACT comment's existing guarantees (never
  rethrows, returns `true`/`false`, leaves refs stale-but-untouched on failure) are unchanged and must keep
  holding — the new checks sit *inside* the existing `try`/`catch`/`finally` structure, they do not restructure
  it. `skillars-deferred-37`'s pruning block (inside the `try`, after `coachBatchGroups.value = ...`) is
  unchanged; it now simply runs conditionally on the new `requestId` check passing, since it is textually after
  that check. No other function in this file changes — `loadParentBookings`, `loadCoachSchedule`,
  `approveBooking`, `rejectBooking`, `handleAcceptAllBatch` all call `loadCoachBookingRequests()` exactly as
  today and require no edits themselves.
- **`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`** — **not modified.** All four page-side
  call sites (`handleAccept`'s catch, `handleDecline`'s catch, `handleAcceptAll`'s catch, `onMounted`) call
  `loadCoachBookingRequests()`/the store functions that wrap it exactly as today; the guard is invisible to them
  because it only changes what the *called* function does with a superseded response, not the call signature or
  return contract for a non-superseded one.

### Project Structure Notes

- No new REST endpoint, no DTO, no migration, no i18n keys, no production Java changes, no new files — this
  story is a single-function frontend change: one `let` declaration and a few guard lines inside
  `loadCoachBookingRequests` in `booking.store.js`.
- No automated frontend test exists for `booking.store.js` in this repo (`find src/frontend/src -iname
  "*.spec.js" -o -iname "*.test.js"` returns zero hits; `package.json`'s `"test"` script is a no-op stub) — the
  same standing, already-documented gap `skillars-deferred-35`/`36`/`37` recorded. Verification for this story
  is by inspection plus ESLint, matching those stories' own precedent. A manual verification a dev can perform
  without a test harness: temporarily add an artificial `await new Promise(r => setTimeout(r, 2000))` before
  `getCoachBookingRequests()`'s call inside a throwaway build, trigger `onMounted`'s slow fetch, then trigger a
  fast `approveBooking` before it resolves, and confirm the approve's fresher state is not overwritten when the
  slow mount fetch finally lands — then revert the temporary delay before committing.

### References

- `src/frontend/src/stores/booking.store.js:117-120` (the four `coachRequests*` refs), `:302-353`
  (`loadCoachBookingRequests` and its CONTRACT comment), `:391-399` (`approveBooking`/`rejectBooking`),
  `:591-619` (`handleAcceptAllBatch`)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:173-206` (`handleAccept`/`handleDecline`),
  `:246-293` (`handleAcceptAll`), `:295-297` (`onMounted`) — read for context, not modified by this story
- `src/frontend/src/stores/booking.store.js:38-44` (`useBookingSse`'s `let es`/`retryCount` — the existing
  plain-`let` non-reactive counter pattern this story's `coachRequestsSequence` follows)
- `_bmad-output/implementation-artifacts/deferred-work.md` (`## Deferred from: code review of
  skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound (2026-08-19)`, line 1569)
- `_bmad-output/implementation-artifacts/skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound.md`
  (the pruning fix this story's guard sits alongside, and the "fix at the one convergence point" precedent)
- `_bmad-output/project-context.md`
- `docs/validation-strategy.md` (smallest-relevant-scope test policy)

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — single-function change, verified by inspection + ESLint per the story's own documented gap (no
automated frontend test infrastructure exists in this repo for `booking.store.js`).

### Completion Notes List

- AC1: `loadCoachBookingRequests()` in `booking.store.js` now guards against out-of-order responses via a
  local, non-reactive `coachRequestsSequence` counter, incremented synchronously before the function's first
  `await` and checked before both outcome branches (`try`'s success path, `catch`) and the `finally` block's
  `coachRequestsLoading` clear. A superseded call's outcome is discarded without touching any ref, and still
  returns `true` per the story's documented rationale (avoids a spurious staleness warning on the older of two
  concurrent calls). Applied verbatim against the story's fully-specified required diff — no deviation.
  `skillars-deferred-37`'s pruning block is unchanged and confirmed by inspection to run only after the new
  `requestId` check passes. The shared "RETURNS ITS OWN OUTCOME" CONTRACT sentence was left unreworded per the
  story's explicit instruction (it also describes `loadCoachSchedule`, which gets no guard in this story); the
  new sequencing-guarantee paragraph was appended after it instead, with the closing clause contrasting
  `loadCoachSchedule`. `npx eslint src/stores/booking.store.js` clean.
- AC2: `deferred-work.md` line 1569 annotated `[CLOSED by skillars-deferred-38 AC1]` with a closure note
  describing the sequence-counter mechanism; line 1568 (the separate, already-accepted pruning-only-on-success
  tradeoff) left untouched. `sprint-status.yaml` already carried the `skillars-deferred-38` entry and
  `last_updated` note from story-creation time — no further edit needed there beyond the status transition
  this workflow performs.
- No automated test added: this repo has no frontend test harness for `booking.store.js` (a standing,
  already-documented gap per `skillars-deferred-35`/`36`/`37`). Verification is by inspection against the
  story's fully worked-out required diff, matching those stories' own precedent.

### File List

- `src/frontend/src/stores/booking.store.js` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — ledger hygiene, AC2)

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: single-item story (ledger mined thin of decision-free candidates for the fifth pass in a row; of the two new items filed by `skillars-deferred-37`'s own code review, only the request-sequencing gap is a genuine actionable candidate — the pruning-only-on-success-path item is an already-reasoned accepted tradeoff and is deliberately left alone). Adds a monotonically-increasing request-sequence guard to `loadCoachBookingRequests()` so an out-of-order response from a superseded call can never overwrite newer state. |
| 2026-08-19 | Senior-dev review of the draft (`story-review.md` Finding 1) found the original AC1 added a new CONTRACT paragraph but left the existing "true if this invocation refreshed" sentence unchanged and Task 1 forbade rewording any existing sentence — both wrong, since a superseded call now returns `true` without refreshing, which the old wording no longer precisely describes. AC1's required code was revised to reword that one sentence explicitly; Task 1 updated to match. |
| 2026-08-19 | **Second-pass review (`story-review.md` Finding 2) corrected the first pass:** the "RETURNS ITS OWN OUTCOME" sentence at `:312` is shared — its subject is "Each," describing both `loadCoachBookingRequests` and `loadCoachSchedule` — and rewording it to mention supersession made it false for `loadCoachSchedule`, which gets no guard in this story. AC1's required diff now leaves that shared sentence untouched and instead adds one clarifying clause to the already-planned `loadCoachBookingRequests`-specific paragraph, contrasting explicitly with `loadCoachSchedule`. Task 1 updated to match. No other gap found across either review pass. |
| 2026-08-19 | Implemented AC1 and AC2 verbatim against the story's fully-specified required diff: `coachRequestsSequence` counter added to `booking.store.js`, both outcome branches and `finally` guarded, CONTRACT comment extended without rewording the shared sentence. ESLint clean. `deferred-work.md` line 1569 closed with a mechanism note; line 1568 left untouched. Status moved to review. |
