# Story Review: skillars-deferred-39-coach-refresh-timeout-safety-and-diagnostic-logging

Reviewed: 2026-08-19
Scope: pre-implementation audit of the story spec (`Status: ready-for-dev`, no code changed yet) against
current code at `src/frontend/src/stores/booking.store.js`, `src/frontend/src/api/booking.api.js`,
`src/frontend/src/boot/axios.js`, `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`, and
`_bmad-output/implementation-artifacts/deferred-work.md`.

**Fact-checks that passed:** line numbers 1573/1574/1575 in `deferred-work.md` match the story's quotes
exactly; `grep -c "\[CLOSED"` = 105, `grep -c "\[PICKED UP"` = 0 as claimed; `getCoachBookingRequests()`
has exactly one caller (`booking.store.js:341`); no existing axios-request-timeout precedent exists
anywhere in `src/frontend/src`; the `catch` block quoted at `booking.store.js:364-367` matches current
code verbatim; `video.store.js:189,210` do use the `console.warn('<message>:', err)` shape cited as
precedent. The story's own factual groundwork is accurate — no false positives filed against it.

---

## Finding 1 (High): AC1's timeout turns a hung *initial* load into a silently wrong "empty inbox" state, not an error state

**What the story claims:** AC1's rationale frames the 20s timeout purely as a fix for the stuck-spinner
symptom — the request rejects, `coachRequestsError` is set, `coachRequestsLoading` clears, "no stuck
spinner." Nothing in the story's ACs, Dev Notes, or "Considered and rejected" section examines what the
page actually renders once that spinner clears.

**What actually happens on the case AC1 exists to fix (a hung *initial mount* load):**
- `coachBookingRequests` and `coachBatchGroups` both default to `ref([])`
  (`booking.store.js:117-118`) and are never written to on the `catch` path — only the `try` path
  (lines 341-363) populates them.
- `CoachBookingRequestsPage.vue`'s `onMounted` hook (line 296) calls
  `bookingStore.loadCoachBookingRequests()` completely fire-and-forget — no `await`, no `.then`, no
  `notifyIfRequestsStale()` — unlike all four other call sites (`handleAccept`, `handleDecline`,
  `handleAcceptAll`, and their internal `approveBooking`/`rejectBooking` calls), which do check the
  return value and toast `booking.errors.listMayBeStale` on failure.
- The template's rendering order (`CoachBookingRequestsPage.vue:5-19`) is: spinner while
  `coachRequestsLoading`, else **"inbox empty" if both arrays have length 0**, else the populated list.
  There is no third branch for "load failed." `coachRequestsError` is read by zero components (the
  CONTRACT comment at `booking.store.js:311` says this explicitly and is accurate).

So: today, a hung initial load leaves the coach staring at a spinner forever — clearly broken, but at
least honestly signaling "something is wrong, don't trust this." After AC1, the same hung request
resolves after 20s into the empty-array default state, which the template cannot distinguish from a
genuinely empty inbox. The coach sees "you have no booking requests" with **no toast, no banner, no
console message the user can see** — a false negative that could cause a real pending request to be
missed entirely, and it is unrecoverable without a manual page reload (there is no retry affordance).
This is arguably a worse failure mode than the one being fixed, and it's the exact scenario AC1's own
motivating case (a genuinely hung latest-issued call) drives the app into.

Note this false-empty-state bug is not newly introduced by this story — it already exists today for any
initial-mount failure (5xx, network-down, etc.), since none of those get a toast either. But AC1 is what
makes the *timeout* case — previously invisible behind an infinite spinner — actually land in it, which
is precisely the scenario this story sets out to make "robust" and "observable." As written, the story
does neither for this specific, foreseeable path.

**Suggested handling:** at minimum, the story should say explicitly that this tradeoff exists and is
accepted (matching this ledger's pattern of naming tradeoffs rather than silently absorbing them). A
stronger fix would have mount check `loadCoachBookingRequests()`'s return value and toast the same
`booking.errors.listMayBeStale` warning the other four call sites already use — a small, low-risk,
in-pattern addition that doesn't require distinguishing "error" from "empty" in the template. Either way,
this should be a conscious decision recorded in the story, not an unexamined side effect.

**Evidence:** `booking.store.js:117-119,296(via CoachBookingRequestsPage.vue),311,341-367`;
`CoachBookingRequestsPage.vue:5-19,167-171,296`.

`[RESOLVED — story revised]` Took the stronger fix, not just the disclosure. AC1 split into (a) the
timeout and (b) making `CoachBookingRequestsPage.vue`'s `onMounted` `async` and routing its return value
through the page's own pre-existing `notifyIfRequestsStale()` helper, matching the four other call
sites' pattern exactly. New Task 1.3/1.4. `CoachBookingRequestsPage.vue` added to Project Structure
Notes and References.

---

## Finding 2 (Medium): the same "no timeout anywhere" risk this story fixes for the GET also applies, unaddressed, to three sibling calls on the same page

**What the story claims:** the "Considered and rejected" section evaluates one alternative to the
per-call timeout — a *global* timeout on the shared `api` axios instance — and rejects it for blast
radius (would affect login, payments, video upload, etc., across the whole app). It does not evaluate
the narrower middle ground: applying the same reasoning to the other network calls that back this exact
page's own loading flags.

**What's actually on this page:** `CoachBookingRequestsPage.vue` gates three more per-action loading
flags on three more un-timed-out axios calls, all going through the same `api` instance with the same
"zero timeout precedent" the story itself established via its `grep -rn "timeout"` sweep:
- `handleAccept` → `accepting.value[id]` → `bookingStore.approveBooking(id)` →
  `acceptBooking(id)` (`booking.api.js:23`, no timeout)
- `handleDecline` → `declining.value[id]` → `bookingStore.rejectBooking(id)` →
  `declineBooking(id)` (`booking.api.js:25`, no timeout)
- `handleAcceptAll` → `acceptingAll.value[batchId]` → `bookingStore.handleAcceptAllBatch(batchId)` →
  `acceptAllBatch(batchId)` (`booking.api.js:62`, no timeout)

Each of these sets its loading flag in a `try` and only clears it in the matching `finally`
(`CoachBookingRequestsPage.vue:173-192` etc.) — if the underlying call hangs forever, that row's or
batch's spinner is stuck forever, structurally the identical bug AC1 is fixing for
`getCoachBookingRequests()`, just gating a per-row/per-batch button instead of the whole-page spinner.

This story is about to close ledger line 1573 ("stuck `coachRequestsLoading` spinner") as fully
resolved, on the same page where three sibling stuck-spinner bugs of the same class remain completely
open and unexamined. That's not necessarily wrong to leave out of a narrowly-scoped story — but the
"Considered and rejected" section's framing ("a global timeout... an unreviewed blast-radius change far
outside this story's scope... Rejected in favor of a per-call timeout") reads as if the per-call/global
tradeoff was the only axis considered, when a same-page/same-risk-class scoping question was equally
available and goes unmentioned.

**Suggested handling:** not necessarily a fix-it-here item, but worth an explicit line in "Considered and
rejected" (or a follow-up ledger item) noting these three sibling gaps exist and were knowingly left
alone, the same way line 1574's test-infra gap gets an explicit accepted-tradeoff writeup rather than
silence.

**Evidence:** `booking.api.js:23,25,62`; `CoachBookingRequestsPage.vue:173-192,194-...,246-...`.

`[RESOLVED — story revised]` Took the follow-up-ledger-item option. New AC4 + Task 3.4: file a new
`deferred-work.md` section naming all three sibling gaps (`acceptBooking`, `declineBooking`,
`acceptAllBatch`), explicitly left un-fixed and out of this story's scope. New bullet added to
"Considered and rejected" for the same reason.

---

## Items checked and found accurate (no finding filed)

- AC1's claim that a timeout error "flows into the existing `catch` block unchanged" — confirmed; a
  timeout rejection has no `error.response`, matches the generic `coachRequestsError.value = e` handling
  already in place.
- AC1's claim that the shared response interceptor's `else if (error.request)` branch handles a timeout
  without throwing — confirmed by reading `boot/axios.js:126-178`; a timeout error carries `error.request`
  truthy and `error.response` undefined, landing in that branch as described. (The branch's message,
  "Unable to reach server," is arguably a slight misnomer for a slow-not-unreachable server, but this is
  pre-existing interceptor copy untouched by this story and already flagged for by-inspection
  verification in Dev Notes — not a new gap.)
- AC2's scoping to only the `catch` branch (not the `try` block's supersession discard at line 342) is
  correctly reasoned — a discarded success is not a failure, logging it would be noise.
- The claim that `getCoachBookingRequests()` has exactly one caller, so the timeout addition can't affect
  any other call site — confirmed via `grep -rn "getCoachBookingRequests" src`.
- Global `useLoading()`/`onLoadingChange` (`boot/axios.js`, `composables/useLoading.js`) also currently
  gets stuck indefinitely by the same hung-request scenario and is likewise bounded by AC1's fix as a
  side effect — a positive, not a gap; not called out by the story but doesn't need to be.
