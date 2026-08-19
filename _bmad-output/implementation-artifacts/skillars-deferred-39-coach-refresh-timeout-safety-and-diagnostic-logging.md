# Story Deferred-39: Coach-Refresh Timeout Safety & Diagnostic Logging

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want `loadCoachBookingRequests()` to never leave `coachRequestsLoading` stuck forever when the
latest-issued request hangs, the coach to be told (not silently shown an empty inbox) when that hung
initial load times out, and a diagnostic trace left when a superseded call's failure is silently
discarded,
so that `skillars-deferred-38`'s request-sequencing guard is robust against real network conditions
instead of only the well-behaved case, and its edge cases are observable when they fire — for the coach,
not just in the console.

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
- **Applying the same per-call timeout to the three sibling calls this same page also gates on loading
  flags** (`handleAccept`→`approveBooking`→`acceptBooking`, `handleDecline`→`rejectBooking`→
  `declineBooking`, `handleAcceptAll`→`handleAcceptAllBatch`→`acceptAllBatch`) — all share the identical
  "zero timeout precedent, stuck-forever-if-hung" risk class this story fixes for
  `getCoachBookingRequests()` (story-review Finding 2). Left out of this story's scope, which targets
  only `deferred-work.md` lines 1573/1575, both specific to `loadCoachBookingRequests()`; filed as a new
  ledger item (AC4) instead of silently expanding scope or leaving it unrecorded.

## Acceptance Criteria

1. **AC1 — Timeout-safe `getCoachBookingRequests()` call, with an observable failure on initial mount.**
   (a) `getCoachBookingRequests()` (`src/frontend/src/api/booking.api.js:29`) passes a `timeout: 20000`
   (20s) axios config option on its `api.get('/api/bookings/requests/coach')` call — scoped to this one
   endpoint only, not the shared `api` instance. When the request exceeds 20s with no response, axios
   rejects with an `ECONNABORTED` timeout error, which flows into `loadCoachBookingRequests()`'s existing
   `catch` block unchanged: if this was still the latest-issued call (`requestId ===
   coachRequestsSequence`), `coachRequestsError` is set, the function returns `false`, and the `finally`
   block clears `coachRequestsLoading` — no stuck spinner. If a newer call had already superseded it, the
   existing supersession check discards it as today (see AC2 for its logging). No change to
   `loadCoachBookingRequests()` itself is required for (a) — the fix is entirely in the API call's config.
   Document the 20s choice with a one-line comment at the call site (no established timeout precedent
   exists elsewhere in this codebase to match against; 20s is chosen as generous enough to never fire
   under normal backend latency while still bounding the worst case, consistent with this ledger item's
   own framing of the risk as "low-probability... requires a genuinely hung HTTP request, not just a slow
   one").
   (b) **`CoachBookingRequestsPage.vue`'s `onMounted` hook** (currently `onMounted(() =>
   bookingStore.loadCoachBookingRequests())`, fire-and-forget, no `await`) is changed to `async` and
   awaits the call, passing its return value through the page's existing `notifyIfRequestsStale()` helper
   (line 167) — the identical pattern the four other call sites (`handleAccept`, `handleDecline`,
   `handleAcceptAll`, and their internal `approveBooking`/`rejectBooking` calls) already use. **Why this
   is part of AC1, not a separate concern:** without it, (a)'s own fix is incomplete — a hung *initial*
   load, the exact case (a) exists to bound, resolves after the 20s timeout into the empty-array default
   state (`coachBookingRequests`/`coachBatchGroups` both default to `ref([])`,
   `booking.store.js:117-118`, never written on the `catch` path), which `CoachBookingRequestsPage.vue`'s
   template cannot distinguish from a genuinely empty inbox (there is no third "load failed" render
   branch, and `coachRequestsError` is read by zero components — see the CONTRACT comment at
   `booking.store.js:311`). Today that same hung load leaves the coach staring at an honest infinite
   spinner; after (a) alone, it would silently resolve into "you have no booking requests," with no
   toast, no banner, no recovery path — a false negative that could hide a real pending request, and a
   worse failure mode than the one being fixed. (b) closes this using the page's own pre-existing
   `notifyIfRequestsStale` pattern — no new UI, no new state, no new template branch. (story-review
   Finding 1)

2. **AC2 — Diagnostic trace for discarded superseded-call failures.** In `loadCoachBookingRequests()`'s
   `catch` block (`booking.store.js:364-367`), the `if (requestId !== coachRequestsSequence) return true`
   branch (line 365) gains a `console.warn` call before returning, logging that a superseded call's
   failure was discarded along with the error object — matching this repo's existing
   `console.warn('<message>:', err)` convention (`video.store.js:189,210`). No other line in that branch
   changes; the discard behavior itself (return `true`, don't touch any ref) is unchanged.

3. **AC3 — Ledger hygiene.** In `deferred-work.md`:
   - Line 1573 (stuck spinner) annotated `[CLOSED by skillars-deferred-39 AC1]` with a closure note
     describing the per-call timeout mechanism, the mount-toast fix, and why a global timeout was
     rejected.
   - Line 1575 (no diagnostic trace) annotated `[CLOSED by skillars-deferred-39 AC2]` with a closure note
     describing the added `console.warn`.
   - Line 1574 (no test coverage — standing repo-wide gap) left **untouched**, exactly as the prior four
     stories left it.

4. **AC4 — File the sibling stuck-spinner gap as a new ledger item.** `CoachBookingRequestsPage.vue`
   gates three more per-action loading flags on three more un-timed-out `api` calls sharing the exact
   same "zero timeout precedent, hangs forever if the request hangs" risk class AC1 fixes for
   `getCoachBookingRequests()`: `handleAccept` → `approveBooking` → `acceptBooking`
   (`booking.api.js:23`), `handleDecline` → `rejectBooking` → `declineBooking` (`booking.api.js:25`), and
   `handleAcceptAll` → `handleAcceptAllBatch` → `acceptAllBatch` (`booking.api.js:62`) — each sets its
   `try`-block loading flag and only clears it in the matching `finally`
   (`CoachBookingRequestsPage.vue:173-192` etc.), structurally identical to the bug AC1 fixes, just
   gating a per-row/per-batch button instead of the whole-page spinner. Deliberately out of this story's
   scope (which targets only `deferred-work.md` lines 1573/1575, both specific to
   `loadCoachBookingRequests()`) — add a new `deferred-work.md` section (`## Deferred from: story-review
   of skillars-deferred-39-coach-refresh-timeout-safety-and-diagnostic-logging (2026-08-19)`) naming
   these three sibling gaps, so they're on record rather than silently left unexamined. (story-review
   Finding 2)

## Tasks / Subtasks

- [x] Task 1: Add scoped request timeout + observable mount failure (AC: #1)
  - [x] 1.1 In `src/frontend/src/api/booking.api.js`, change
    `export const getCoachBookingRequests = () => api.get('/api/bookings/requests/coach')` to pass a
    second argument `{ timeout: 20000 }`, with a one-line comment explaining the 20s choice and that it
    is scoped to this call only (skillars-deferred-38's stuck-spinner follow-up). Do not touch
    `boot/axios.js` or any other `*.api.js` export.
  - [x] 1.2 Confirm by inspection that `loadCoachBookingRequests()`'s `catch` block
    (`booking.store.js:364-367`) needs no code change to correctly handle a timeout error — it is just
    another rejected promise, identical in shape to any other axios error already handled there.
  - [x] 1.3 In `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue`, change the `onMounted` hook
    (line 295-297) from `onMounted(() => { bookingStore.loadCoachBookingRequests() })` to
    `onMounted(async () => { notifyIfRequestsStale(await bookingStore.loadCoachBookingRequests()) })`,
    matching the four other call sites' existing pattern exactly. No other change to this file.
  - [x] 1.4 Run `npx eslint src/pages/coach/CoachBookingRequestsPage.vue` from `src/frontend` and confirm
    clean.
- [x] Task 2: Log discarded superseded-call failures (AC: #2)
  - [x] 2.1 In `booking.store.js`'s `loadCoachBookingRequests()` `catch` block, change line 365 from
    `if (requestId !== coachRequestsSequence) return true` to first `console.warn(...)` the discard (with
    the error object) and then `return true`, matching `video.store.js`'s `console.warn('<message>:',
    err)` shape. Keep the success-path supersession check (line 342, `try` block) unchanged — AC2 targets
    only the `catch` branch, matching line 1575's own scope ("discarded superseded-call **failures**").
  - [x] 2.2 Run `npx eslint src/stores/booking.store.js src/api/booking.api.js` from
    `src/frontend` and confirm clean.
- [x] Task 3: Ledger hygiene (AC: #3, #4)
  - [x] 3.1 Annotate `deferred-work.md` line 1573 `[CLOSED by skillars-deferred-39 AC1]` with a closure
    note covering both the timeout and the mount-toast fix.
  - [x] 3.2 Annotate `deferred-work.md` line 1575 `[CLOSED by skillars-deferred-39 AC2]` with a closure
    note.
  - [x] 3.3 Leave line 1574 untouched.
  - [x] 3.4 Add a new `## Deferred from: story-review of
    skillars-deferred-39-coach-refresh-timeout-safety-and-diagnostic-logging (2026-08-19)` section to
    `deferred-work.md` naming the three sibling stuck-spinner gaps (AC4).

### Review Findings

- [x] [Review][Patch] Unmount-before-settle race in `onMounted` fires a stale-list toast against a page the coach already left [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:295-301`] — FIXED: added an `isMounted` flag (set `false` via a new `onUnmounted` hook) and gated the `notifyIfRequestsStale` call on it, matching the codebase's existing `useBookingSse` unmount-guard shape. `npx eslint` clean post-patch. (Blind Hunter + Edge Case Hunter, independently agreeing)
- [x] [Review][Defer] `console.warn` logs the full raw Axios error object, which can carry request headers (e.g. `Authorization`) via `error.config` [`src/frontend/src/stores/booking.store.js:365-368`] — deferred, pre-existing. Matches this repo's existing `console.warn('<message>:', err)` convention (`video.store.js:189,210`), which has the same property — not unique to this diff, and browser-console-only (not transmitted anywhere), but worth tracking as a minor observability/security polish item rather than propagating silently forever.

## Dev Notes

- **No automated test infrastructure exists for the frontend** (see line 1574 analysis above) — this is
  a standing, already-accepted repo-wide gap (`skillars-deferred-35`/`36`/`37`/`38` precedent). Verify
  both ACs by inspection and `npx eslint`, matching those stories' own convention. Do not attempt to add
  test infrastructure as part of this story — that is explicitly out of scope (see "Considered and
  rejected" above).
- **Neither AC1(a) nor AC1(b) touches `loadCoachBookingRequests()` itself** — (a) is a `booking.api.js`
  config change, (b) is a `CoachBookingRequestsPage.vue` call-site change. Do not add timeout-specific
  branching logic inside the store function; a timeout is just another axios rejection and the existing
  `catch` block already handles arbitrary errors generically via `coachRequestsError.value = e`.
- **AC2 touches only the `catch` block's supersession check, not the `try` block's** (line 342). The
  ledger item (line 1575) is specifically about discarded *failures*; a discarded *success* (line 342) is
  not a failure and logging it would be noise on the normal, expected, non-error path of an out-of-order
  response.
- `getCoachBookingRequests()` is called from exactly one place in `booking.store.js`
  (`loadCoachBookingRequests()`, line 341) — no other caller is affected by the timeout addition.
- **AC1(b)'s `onMounted` change must match the existing `notifyIfRequestsStale`/other-call-site pattern
  exactly** — do not invent a new toast message, new error branch, or new template state. The helper
  already exists at `CoachBookingRequestsPage.vue:167` and already handles `refreshed === false` by
  notifying `booking.errors.listMayBeStale`; `onMounted` just needs to become `async` and pass the
  awaited return value through it, identically to `handleAccept`/`handleDecline`/`handleAcceptAll`.
- The shared axios response interceptor (`boot/axios.js:112-179`) already logs 401/403/5xx/network
  errors generically and re-rejects; a timeout error has no `error.response` (axios sets
  `error.code === 'ECONNABORTED'` with no HTTP response), so it falls into the interceptor's existing
  `else if (error.request)` branch — **verify this by inspection during implementation**: confirm the
  interceptor's existing network-error branch handles a timeout rejection without throwing or mis-
  logging it, since it currently only distinguishes "no `error.response`" (network/timeout) from "has
  `error.response`" (server responded). This is pre-existing interceptor behavior, not something this
  story needs to change — just confirm it doesn't break.

### Project Structure Notes

- `src/frontend/src/api/booking.api.js` — API layer, one-line change (AC1a).
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` — `onMounted` hook only, matching an
  existing pattern already used four times elsewhere in the same file (AC1b).
- `src/frontend/src/stores/booking.store.js` — Pinia store, one-line change inside an existing function
  (AC2). No new functions, no new state.
- `_bmad-output/implementation-artifacts/deferred-work.md` — ledger hygiene, two closures + one new
  section (AC3, AC4).
- No new files. No `boot/axios.js` change.

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
- [Source: `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` lines 5-19 (template render
  order), 160-171 (`notifyIfRequestsStale`), 173-192, 194-206, 246-293 (the four existing call sites
  AC1b/AC4 reference), 295-297 (`onMounted`, AC1b's target)]
- [Source: `src/frontend/src/api/booking.api.js` lines 23, 25, 62 — `acceptBooking`, `declineBooking`,
  `acceptAllBatch`, the three sibling un-timed-out calls AC4 files as a new ledger item]
- [Source: `_bmad-output/implementation-artifacts/story-review.md` — this story's pre-implementation
  review; Finding 1 (High) drove AC1b, Finding 2 (Medium) drove AC4]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- Confirmed by inspection that `boot/axios.js`'s response interceptor's `else if (error.request)` branch
  (network/timeout errors, no `error.response`) handles an `ECONNABORTED` timeout rejection the same as
  any other network error — logs and re-rejects, no special-casing needed, no crash risk.
- `npx eslint src/pages/coach/CoachBookingRequestsPage.vue` — clean.
- `npx eslint src/stores/booking.store.js src/api/booking.api.js` — clean.

### Completion Notes List

- AC1(a): `getCoachBookingRequests()` (`booking.api.js:29-31`) now passes `{ timeout: 20000 }`, scoped to
  this one call only. Verified by inspection that `loadCoachBookingRequests()`'s `catch` block needed no
  change — a timeout rejection is just another axios error, handled identically to any other.
- AC1(b): `CoachBookingRequestsPage.vue`'s `onMounted` is now `async` and routes
  `loadCoachBookingRequests()`'s return value through the existing `notifyIfRequestsStale()` helper,
  matching the four other call sites' pattern exactly. No new template branch, no new state.
- AC2: `loadCoachBookingRequests()`'s `catch` block now `console.warn`s a discarded superseded-call
  failure (with the error object) before returning `true`, matching `video.store.js`'s existing
  `console.warn('<message>:', err)` convention. The `try` block's success-path supersession check is
  unchanged, per Dev Notes.
- AC3: `deferred-work.md` lines 1573 and 1575 annotated `[CLOSED by skillars-deferred-39 AC1]` /
  `AC2` respectively with closure notes; line 1574 (standing frontend-test-infra gap) left untouched.
- AC4: new `## Deferred from: story-review of skillars-deferred-39-...` section appended to
  `deferred-work.md`, naming the three sibling un-timed-out calls (`acceptBooking`, `declineBooking`,
  `acceptAllBatch`) as a fresh, out-of-scope ledger item.
- No automated test infrastructure exists for the frontend (standing repo-wide gap per Dev Notes) —
  verified both ACs by inspection and `npx eslint`, matching the `skillars-deferred-35`–`38` convention.
  Per this repo's `docs/validation-strategy.md`, `mvn verify` was not run (no backend code touched).

### File List

- `src/frontend/src/api/booking.api.js` (modified)
- `src/frontend/src/stores/booking.store.js` (modified)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` (modified; code review added an `isMounted`/`onUnmounted` guard)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified; code review added one more deferred item)

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: two-item story (ledger mined thin of decision-free candidates for the sixth pass in a row; of the three items filed by `skillars-deferred-38`'s own code review, two — the stuck-spinner timeout gap and the missing diagnostic trace for discarded failures — are genuine, narrow, groupable candidates; the third, standing repo-wide absence of frontend test infrastructure, is an infrastructure-scale decision left alone exactly as the prior four stories left it). Adds a 20s per-call timeout to `getCoachBookingRequests()` (not the shared axios instance) so a hung latest-issued request can no longer leave `coachRequestsLoading` stuck forever, and a `console.warn` in `loadCoachBookingRequests()`'s catch block so a discarded superseded-call failure leaves a trace instead of vanishing silently. |
| 2026-08-19 | Story review (`story-review.md`) found two issues in the draft. **Finding 1 (High):** AC1's timeout, as originally scoped, fixed the stuck-spinner *symptom* but turned a hung *initial-mount* load into a worse, silent failure — `onMounted` calls `loadCoachBookingRequests()` fire-and-forget, so after the 20s timeout the coach would see a false "no booking requests" empty state with no toast and no recovery path, since the template has no third "load failed" branch and `coachRequestsError` is read by zero components. Fixed rather than merely documented: AC1 split into (a) the timeout and (b) making `onMounted` `async` and routing its return value through the page's own pre-existing `notifyIfRequestsStale()` helper — the identical pattern the four other call sites already use. **Finding 2 (Medium):** the same "zero timeout precedent, hangs forever" risk class AC1 fixes for `getCoachBookingRequests()` also applies, unaddressed, to three sibling calls this same page gates loading flags on (`acceptBooking`, `declineBooking`, `acceptAllBatch`) — left out of this story's scope (it targets only `deferred-work.md` lines 1573/1575) but not silently: new AC4 files a fresh ledger item naming all three so the gap is on record, matching how line 1574's test-infra gap already gets an explicit accepted-tradeoff writeup rather than silence. Story now has 4 ACs; `CoachBookingRequestsPage.vue` added to the touched-files list. |
| 2026-08-19 | Implementation complete, status set to `review`. All 4 ACs shipped verbatim against the spec: AC1(a) `getCoachBookingRequests()` gains a scoped 20s `timeout`; AC1(b) `CoachBookingRequestsPage.vue`'s `onMounted` made `async`, wired through the existing `notifyIfRequestsStale()` helper; AC2 `loadCoachBookingRequests()`'s `catch` block now `console.warn`s a discarded superseded-call failure before returning; AC3 `deferred-work.md` lines 1573/1575 closed with mechanism notes, line 1574 left untouched; AC4 a new `deferred-work.md` section files the three sibling un-timed-out calls (`acceptBooking`, `declineBooking`, `acceptAllBatch`) as a fresh ledger item. `npx eslint` clean on all three touched frontend files; no automated test added, standing repo-wide gap per Dev Notes; `mvn verify` not run (no backend code touched, per `docs/validation-strategy.md`). |
| 2026-08-19 | Code review complete, status set to `done`. Blind Hunter + Edge Case Hunter + Acceptance Auditor, 0 AC violations (Acceptance Auditor confirmed all 4 ACs shipped verbatim). Blind Hunter's separately-verified "no eslint config exists" claim was independently re-checked and found false — `eslint.config.js` exists at `src/frontend/`, both story-cited `npx eslint` commands re-run clean (exit 0). 1 patch applied: `onMounted`'s newly-awaited (up to 20s, per AC1a) `loadCoachBookingRequests()` call could resolve after the coach navigated away, firing `notifyIfRequestsStale()`'s toast against an unmounted page — a consequence that didn't exist pre-story, since `onMounted` was previously fire-and-forget with no post-await side effect. Fixed with an `isMounted` flag + `onUnmounted` hook, matching the codebase's existing `useBookingSse` unmount-guard shape; `npx eslint` clean post-patch. 1 finding deferred to `deferred-work.md` (AC2's `console.warn` logs the full raw Axios error object, which can carry `Authorization` headers via `error.config` — matches `video.store.js`'s existing convention, not unique to this diff, browser-console-only). 12 findings dismissed as noise (design trade-offs already reasoned in the spec's own "Considered and rejected" section, unverifiable claims contradicted by Edge Case Hunter's and Acceptance Auditor's project-read access, and the false "no eslint config" claim above). |
