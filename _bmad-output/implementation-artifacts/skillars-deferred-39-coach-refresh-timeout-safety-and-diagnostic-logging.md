# Story Deferred-39: Coach-Refresh Timeout Safety & Diagnostic Logging

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want `loadCoachBookingRequests()` to never leave `coachRequestsLoading` stuck forever when the
latest-issued request hangs, and to leave a diagnostic trace when a superseded call's failure is
silently discarded,
so that `skillars-deferred-38`'s request-sequencing guard is robust against real network conditions
instead of only the well-behaved case, and its edge cases are observable when they fire.

### Why this story exists

Drawn from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: code review of skillars-deferred-38-coach-refresh-request-sequencing-guard
(2026-08-19)` section (lines 1573-1575) — two of the three items that section's code review filed.

This story's creation re-read `deferred-work.md` end to end (all 1576 lines) a sixth time. `grep -c
"\[PICKED UP"` returns 0 and `grep -c "\[CLOSED"` returns 105 — every previously-filed item is either
shipped, stale, superseded, or an explicitly accepted low-priority tradeoff with its own reasoning
already on record. The ledger is mined thin of unrelated small items for the sixth story in a row
(`skillars-deferred-34` through `38` each recorded the same finding). Exactly one section remains open:
`skillars-deferred-38`'s own code review, with three items.

**All three items were re-verified against current code during this story's creation. Two are genuine,
groupable candidates; the third is deliberately left alone.**

- **Line 1573 — stuck `coachRequestsLoading` spinner if the latest-issued call never settles.**
  Re-verified: `src/frontend/src/boot/axios.js` (read in full) configures the shared axios instance
  (`axios.create({ baseURL: '', withCredentials: true })`, line 81) with no `timeout` option, and `grep
  -rn "timeout" src/frontend/src --include="*.js" --include="*.vue"` (excluding `setTimeout`/Quasar
  notify timeouts) finds **zero** request-timeout precedent anywhere in this codebase. Because
  `loadCoachBookingRequests()`'s `finally` guard (`requestId === coachRequestsSequence`) only lets the
  most-recently-issued call clear `coachRequestsLoading`, a hung latest-issued HTTP request — no server
  response, ever — leaves the spinner stuck indefinitely even if an earlier, superseded call resolves
  normally. Genuine, actionable gap with a narrow fix. **Candidate for this story (AC1).**
- **Line 1574 — no automated test coverage for the concurrency/sequencing guard.** Re-verified:
  `src/frontend/package.json`'s `"test"` script is literally `"echo \"No test specified\" && exit 0"`;
  no `vitest`/`@vue/test-utils` dependency exists in `package.json`; no `vitest.config.*` file exists
  anywhere under `src/frontend`. This is not a booking.store.js-specific gap — **zero automated frontend
  test infrastructure exists in this repo at all**, a standing condition this ledger has recorded
  identically across `skillars-deferred-35`/`36`/`37`/`38` without ever converting it into a story.
  Standing up a test framework from scratch (choosing/installing a runner, wiring a Vite/Quasar-
  compatible config, establishing the project's first component/store test pattern) is an infrastructure
  decision on its own, not a small bug fix groupable with the other two items here — bundling it into
  this story would make the story about test-infra setup with two unrelated one-line fixes grafted on,
  the wrong way round. **Left alone — no AC targets this item; it stays open in the ledger exactly as
  the prior four stories left it.**
- **Line 1575 — discarded superseded-call failures produce no diagnostic trace.** Re-verified by reading
  `booking.store.js:336-371`: the `catch` block's `if (requestId !== coachRequestsSequence) return true`
  branch (line 365) silently drops the error object with no logging of any kind — no
  `console.warn`/`console.error`, nothing. `video.store.js:189,210` establishes this repo's existing
  convention for this exact situation (`console.warn('<context message>:', err)`), so there is a
  precedent to follow, not a new pattern to invent. Small, self-contained, no design decision needed.
  **Candidate for this story (AC2).**

**Decision made during this story's creation:** bundle AC1 (timeout) and AC2 (diagnostic log) — both
are narrow, decision-light hardening fixes to the same function born from the same story's code review —
and leave the test-infra item (line 1574) untouched in the ledger, consistent with every prior pass.

Considered and rejected during story creation:
- **A global `timeout` on the shared `api` axios instance** (`boot/axios.js:81-84`) — would affect every
  request across the entire app (login, payments, video upload, SSE-adjacent calls), an unreviewed
  blast-radius change far outside this story's scope. Rejected in favor of a **per-call** timeout scoped
  to `getCoachBookingRequests()` only.
- **`AbortController`-based cancellation of superseded requests** — `skillars-deferred-38`'s own story
  already considered and rejected this (see that story's file); this story does not revisit it. A timeout
  is a strictly narrower, lower-risk mitigation for the one consequence (stuck spinner) that
  `skillars-deferred-38` didn't weigh.
- **Standing up frontend test infrastructure to cover this guard** — see line 1574 analysis above;
  explicitly out of scope for this story.

## Acceptance Criteria

1. **AC1 — Timeout-safe `getCoachBookingRequests()` call.** `getCoachBookingRequests()`
   (`src/frontend/src/api/booking.api.js:29`) passes a `timeout: 20000` (20s) axios config option on its
   `api.get('/api/bookings/requests/coach')` call — scoped to this one endpoint only, not the shared
   `api` instance. When the request exceeds 20s with no response, axios rejects with an `ECONNABORTED`
   timeout error, which flows into `loadCoachBookingRequests()`'s existing `catch` block unchanged: if
   this was still the latest-issued call (`requestId === coachRequestsSequence`), `coachRequestsError`
   is set, the function returns `false`, and the `finally` block clears `coachRequestsLoading` — no
   stuck spinner. If a newer call had already superseded it, the existing supersession check discards it
   as today (see AC2 for its logging). No change to `loadCoachBookingRequests()` itself is required for
   AC1 — the fix is entirely in the API call's config. Document the 20s choice with a one-line comment at
   the call site (no established timeout precedent exists elsewhere in this codebase to match against;
   20s is chosen as generous enough to never fire under normal backend latency while still bounding the
   worst case, consistent with this ledger item's own framing of the risk as "low-probability... requires
   a genuinely hung HTTP request, not just a slow one").

2. **AC2 — Diagnostic trace for discarded superseded-call failures.** In `loadCoachBookingRequests()`'s
   `catch` block (`booking.store.js:364-367`), the `if (requestId !== coachRequestsSequence) return true`
   branch (line 365) gains a `console.warn` call before returning, logging that a superseded call's
   failure was discarded along with the error object — matching this repo's existing
   `console.warn('<message>:', err)` convention (`video.store.js:189,210`). No other line in that branch
   changes; the discard behavior itself (return `true`, don't touch any ref) is unchanged.

3. **AC3 — Ledger hygiene.** In `deferred-work.md`:
   - Line 1573 (stuck spinner) annotated `[CLOSED by skillars-deferred-39 AC1]` with a closure note
     describing the per-call timeout mechanism and why a global timeout was rejected.
   - Line 1575 (no diagnostic trace) annotated `[CLOSED by skillars-deferred-39 AC2]` with a closure note
     describing the added `console.warn`.
   - Line 1574 (no test coverage — standing repo-wide gap) left **untouched**, exactly as the prior four
     stories left it.

## Tasks / Subtasks

- [ ] Task 1: Add scoped request timeout (AC: #1)
  - [ ] 1.1 In `src/frontend/src/api/booking.api.js`, change
    `export const getCoachBookingRequests = () => api.get('/api/bookings/requests/coach')` to pass a
    second argument `{ timeout: 20000 }`, with a one-line comment explaining the 20s choice and that it
    is scoped to this call only (skillars-deferred-38's stuck-spinner follow-up). Do not touch
    `boot/axios.js` or any other `*.api.js` export.
  - [ ] 1.2 Confirm by inspection that `loadCoachBookingRequests()`'s `catch` block
    (`booking.store.js:364-367`) needs no code change to correctly handle a timeout error — it is just
    another rejected promise, identical in shape to any other axios error already handled there.
- [ ] Task 2: Log discarded superseded-call failures (AC: #2)
  - [ ] 2.1 In `booking.store.js`'s `loadCoachBookingRequests()` `catch` block, change line 365 from
    `if (requestId !== coachRequestsSequence) return true` to first `console.warn(...)` the discard (with
    the error object) and then `return true`, matching `video.store.js`'s `console.warn('<message>:',
    err)` shape. Keep the success-path supersession check (line 342, `try` block) unchanged — AC2 targets
    only the `catch` branch, matching line 1575's own scope ("discarded superseded-call **failures**").
  - [ ] 2.2 Run `npx eslint src/stores/booking.store.js src/api/booking.api.js` from
    `src/frontend` and confirm clean.
- [ ] Task 3: Ledger hygiene (AC: #3)
  - [ ] 3.1 Annotate `deferred-work.md` line 1573 `[CLOSED by skillars-deferred-39 AC1]` with a closure
    note.
  - [ ] 3.2 Annotate `deferred-work.md` line 1575 `[CLOSED by skillars-deferred-39 AC2]` with a closure
    note.
  - [ ] 3.3 Leave line 1574 untouched.

## Dev Notes

- **No automated test infrastructure exists for the frontend** (see line 1574 analysis above) — this is
  a standing, already-accepted repo-wide gap (`skillars-deferred-35`/`36`/`37`/`38` precedent). Verify
  both ACs by inspection and `npx eslint`, matching those stories' own convention. Do not attempt to add
  test infrastructure as part of this story — that is explicitly out of scope (see "Considered and
  rejected" above).
- **AC1 does not touch `loadCoachBookingRequests()`** — only `booking.api.js`. Do not add timeout-
  specific branching logic inside the store function; a timeout is just another axios rejection and the
  existing `catch` block already handles arbitrary errors generically via `coachRequestsError.value = e`.
- **AC2 touches only the `catch` block's supersession check, not the `try` block's** (line 342). The
  ledger item (line 1575) is specifically about discarded *failures*; a discarded *success* (line 342) is
  not a failure and logging it would be noise on the normal, expected, non-error path of an out-of-order
  response.
- `getCoachBookingRequests()` is called from exactly one place in `booking.store.js`
  (`loadCoachBookingRequests()`, line 341) — no other caller is affected by the timeout addition.
- The shared axios response interceptor (`boot/axios.js:112-179`) already logs 401/403/5xx/network
  errors generically and re-rejects; a timeout error has no `error.response` (axios sets
  `error.code === 'ECONNABORTED'` with no HTTP response), so it falls into the interceptor's existing
  `else if (error.request)` branch — **verify this by inspection during implementation**: confirm the
  interceptor's existing network-error branch handles a timeout rejection without throwing or mis-
  logging it, since it currently only distinguishes "no `error.response`" (network/timeout) from "has
  `error.response`" (server responded). This is pre-existing interceptor behavior, not something this
  story needs to change — just confirm it doesn't break.

### Project Structure Notes

- `src/frontend/src/api/booking.api.js` — API layer, one-line change (AC1).
- `src/frontend/src/stores/booking.store.js` — Pinia store, one-line change inside an existing function
  (AC2). No new functions, no new state.
- `_bmad-output/implementation-artifacts/deferred-work.md` — ledger hygiene (AC3).
- No new files. No `boot/axios.js` change. No `CoachBookingRequestsPage.vue` change — this story is
  entirely inside the store/API layer, invisible to callers.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines 1571-1575 — code review of
  skillars-deferred-38, the section this story closes two items from]
- [Source: `src/frontend/src/stores/booking.store.js` lines 306-371 — `loadCoachBookingRequests()` and
  its CONTRACT comment block, the function both ACs touch]
- [Source: `src/frontend/src/api/booking.api.js` line 29 — `getCoachBookingRequests()`, AC1's target]
- [Source: `src/frontend/src/boot/axios.js` lines 81-84, 111-179 — shared axios instance and response
  interceptor, confirmed to have no existing timeout and to handle network/timeout errors generically]
- [Source: `src/frontend/src/stores/video.store.js` lines 189, 210 — `console.warn` diagnostic-logging
  convention AC2 follows]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-38-coach-refresh-request-sequencing-guard.md`
  — the story whose code review filed all three line-1573/1574/1575 items; its own
  `AbortController`/debounce rejection rationale is not revisited here]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: two-item story (ledger mined thin of decision-free candidates for the sixth pass in a row; of the three items filed by `skillars-deferred-38`'s own code review, two — the stuck-spinner timeout gap and the missing diagnostic trace for discarded failures — are genuine, narrow, groupable candidates; the third, standing repo-wide absence of frontend test infrastructure, is an infrastructure-scale decision left alone exactly as the prior four stories left it). Adds a 20s per-call timeout to `getCoachBookingRequests()` (not the shared axios instance) so a hung latest-issued request can no longer leave `coachRequestsLoading` stuck forever, and a `console.warn` in `loadCoachBookingRequests()`'s catch block so a discarded superseded-call failure leaves a trace instead of vanishing silently. |
