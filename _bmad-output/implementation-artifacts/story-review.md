# Senior Dev Review: skillars-deferred-40 (Coach-Action Timeout Hardening, Radar Confidence-Indicator Accuracy & Video Bandwidth Tracking)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-40-coach-action-timeout-hardening-radar-confidence-accuracy-and-video-bandwidth-tracking.md`
Method: every factual claim was re-verified against current code and against `deferred-work.md`'s actual current
content — not taken on the story's word. All four ACs' target files were read in full (`booking.api.js`,
`booking.store.js`, `CoachBookingRequestsPage.vue`, `boot/axios.js`, the backend booking resources, the full
`development` radar chain — repo/entity/service/contract/Vue/test — and the full `video` playback/quota chain,
including every existing `PlaybackService`-adjacent test file). The ledger sections AC5 references were located
and diffed against the story's citations line-by-line.

AC2 and AC3's required code both check out cleanly against the code as described. **AC1 does not** — its
required code, taken literally, ships a change that silently fails to do what the AC exists to do (Finding 1).
AC4 has a real compile-breaking gap in an existing test the story never mentions (Finding 2). AC5 has two
issues: one of its "already fixed" closure claims overstates what the code actually does (Finding 3), and its
ledger-hygiene task list describes work that is already done, against markers the story never explains
(Finding 4), plus a wrong section citation for D6/D7 (Finding 5). Two smaller items round this out: AC2's
own stated rationale names a risk (an `Authorization` header leak) that doesn't actually exist for this call
site in this codebase (Finding 6), and AC3 leaves a now-inaccurate code comment behind that documents the
exact bug it just fixed (Finding 7).

---

## Finding 1 (High, confirmed): AC1's "add `{ timeout: 20000 }` as a second argument" is correct for the GET precedent and wrong for all three PUT/POST calls it actually targets — the timeout silently never applies

**Where:** AC1's required code and Task 1.1; `src/frontend/src/api/booking.api.js:23,25,65` (`acceptBooking`,
`declineBooking`, `acceptAllBatch`).

Axios's call signature differs by verb: `get(url, config)` takes two arguments, but `put(url, data, config)` and
`post(url, data, config)` take three — the *second* argument is the request body, not the config object. AC1's own
precedent, `getCoachBookingRequests` (`booking.api.js:31-32`), is a `GET` with no body, so `api.get(url, { timeout:
20000 })` correctly lands `{ timeout: 20000 }` in the `config` slot. But `acceptBooking`/`declineBooking` are
`api.put(url)` and `acceptAllBatch` is `api.post(url)` — all three currently called with **no body argument at
all**. Following AC1's literal instruction ("add `{ timeout: 20000 }` as a second argument") produces:

```js
export const acceptBooking = (id) => api.put(`/api/bookings/requests/${id}/accept`, { timeout: 20000 })
```

Here `{ timeout: 20000 }` becomes the **request body** (serialized as JSON), not the axios config. No timeout is
ever configured — the exact defect AC1 exists to close remains open on all three calls — and each call now also
sends a nonsensical `{"timeout":20000}` body to an endpoint whose backend method takes no `@RequestBody`
(confirmed: `BookingResource.acceptBooking`/`declineBooking` at `BookingResource.java:55-67`,
`BookingBatchResource.acceptAll` at `BookingBatchResource.java:48-53` — all `@PathVariable`-only), so Spring
silently ignores the extra body and returns 200 as normal. Nothing in the story's own verification steps would
catch this: ESLint (Task 1.3) sees valid JavaScript, ESLint has no rule for axios argument arity, ESLint has no
opinion on this, and no existing or planned test asserts a timeout is actually configured or exercises a hung
request. This is not a hypothetical — it is what happens if AC1 is implemented as literally written.

**Correct fix:** pass an explicit `undefined` (or `null`) data argument before the config object, e.g.:
```js
export const acceptBooking = (id) =>
  api.put(`/api/bookings/requests/${id}/accept`, undefined, { timeout: 20000 })
export const declineBooking = (id) =>
  api.put(`/api/bookings/requests/${id}/decline`, undefined, { timeout: 20000 })
export const acceptAllBatch = (batchId) =>
  api.post(`/api/bookings/batches/${batchId}/accept-all`, undefined, { timeout: 20000 })
```
AC1's required-code block and Task 1.1 should be corrected before `dev-story` runs — as written, a dev following
the story produces a change that reads correctly, passes ESLint, and accomplishes nothing.

(Related, low-severity citation slip in the same AC: `acceptAllBatch` is currently at `booking.api.js:65`, not
the `:62` the story cites in AC1's bullet list, Task 1.1, and the References section — the ledger's original
`:62` citation drifted by three lines as unrelated exports were added above it, and this story's own
re-verification pass didn't catch the drift. Easy to fix alongside Finding 1's correction.)

---

## Finding 2 (High, confirmed): AC4's new `QuotaService` dependency breaks `PlaybackRevocationWindowUnitTest` at compile time — not listed anywhere in the story

**Where:** AC4 / Task 4.2 ("Inject `QuotaService` into `PlaybackService`") and Project Structure Notes (which
lists only "New or extended test file(s) for `PlaybackService` and `QuotaService`").

`PlaybackService` uses `@RequiredArgsConstructor` (`PlaybackService.java:37`) over six `private final` fields.
`src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java:53`
constructs it directly with the current 6-arg generated constructor:

```java
playbackService = new PlaybackService(videoRepository, playbackTokenRepository, videoProviderAdapter, properties, videoMetrics, configService);
```

Adding `QuotaService` as a seventh `private final` field (as Task 4.2 requires) changes the Lombok-generated
constructor's arity to 7. This direct `new PlaybackService(...)` call — confirmed via `grep -rn "new
PlaybackService("` to be the **only** manual-construction site in the repository — will fail to compile until
this line is updated to also pass a `@Mock QuotaService quotaService`. Neither AC4's required-code list, Task 4
(4.1–4.4), Dev Notes, nor Project Structure Notes mention this file. Task 4.3 references
`PlaybackRevocationWindowUnitTest.java` only once, as a style example for a *new* test to imitate ("see
`RadarCompositeCalculatorTest.java`/`PlaybackRevocationWindowUnitTest.java` for the house style"), not as a file
that itself needs a compile-fixing edit — so a dev following the story's task list will not discover this until
the module fails to compile.

By contrast, `PlaybackServiceIT.java` is a Spring-context test that `@Autowire`s `PlaybackService` rather than
constructing it directly — it will pick up the real `QuotaService` bean automatically and needs no changes.

**Recommendation:** add a `@Mock QuotaService quotaService` field and thread it into the `new
PlaybackService(...)` call in `PlaybackRevocationWindowUnitTest.java:53`, or add it to Task 4's file list
explicitly so `dev-story` doesn't discover the compile failure only when running tests.

---

## Finding 3 (Medium, confirmed): AC5's "already fixed" claim for D1 is only true for one of the two named services — `NeglectedSkillDetectionService`'s guard is a warning tripwire, not a preventive fix

**Where:** Story body / AC5 / Task 5.6's D1 closure text; `QuotaReservationTimeoutService.java:19-49`;
`NeglectedSkillDetectionService.java:25-31,68-86`.

D1's original concern: an un-chunked work loop could run long enough that ShedLock force-expires the lock mid-run
and lets a second instance start an overlapping execution. The two cited guards are not equivalent:

- **`QuotaReservationTimeoutService`** genuinely fixes this — `expireStaleReservations` computes a deadline
  (`MAX_RUN_DURATION` = 8 min under a 10-min `lockAtMostFor`) and the `do/while` loop **stops itself** once that
  deadline passes, leaving the remainder for the next `fixedDelay` firing 60s later. This is a real, preventive
  fix, and the story's characterization of it is accurate.
- **`NeglectedSkillDetectionService`** does **not** stop, chunk, or otherwise bound its per-player loop. It only
  logs a `log.warn` once elapsed runtime crosses 80% of the 30-minute `lockAtMostFor` budget
  (`RUNTIME_WARNING_THRESHOLD = Duration.ofMinutes(24)`) and then keeps iterating unchanged. The code's own
  comment explains why: "this job only fires weekly... bailing out early isn't safe (the remaining players
  wouldn't be picked up for another week)" — the team deliberately chose a detective control (log loudly so an
  operator can raise `lockAtMostFor` by hand) over a preventive one. The race D1 describes — ShedLock
  force-expiring the lock mid-run under player-count growth, letting a second instance start an overlapping run —
  is **not eliminated** for this service; it is only made visible in advance so a human can intervene before it
  happens.

Labeling D1 "already fixed" / "STALE — already fixed, never previously audited" folds a detective control in with
a preventive one under the same closure language. A more accurate note would say the
`QuotaReservationTimeoutService` half is fixed and the `NeglectedSkillDetectionService` half is a deliberate,
documented early-warning mitigation that leaves the underlying race structurally possible — closer to how this
same ledger treats other accepted-but-still-real-design-limitation items (e.g. the D3 entry in the `skillars-6-6`
section, kept open specifically because the underlying limitation remains real even though the described scenario
isn't currently reachable).

---

## Finding 4 (Medium, confirmed): AC5's ledger-hygiene task list describes work that is already done, against a marker (`[PICKED UP by ...]`) the story never explains

**Where:** AC5, Task 5 (5.1–5.6), and the closing paragraph of "Why this story exists."

`deferred-work.md`'s current content — already committed as part of this story's own creation commit
(`358f575`) — contains:

- `[PICKED UP by skillars-deferred-40 AC1]` on the "story-review of skillars-deferred-39" sibling-timeout item
  (`deferred-work.md:1579`).
- `[PICKED UP by skillars-deferred-40 AC2]` on the "code review of skillars-deferred-39" `console.warn` item
  (`deferred-work.md:1583`).
- `[PICKED UP by skillars-deferred-40 AC3]` on DEF2 (`deferred-work.md:687`).
- `[PICKED UP by skillars-deferred-40 AC4]` on Def11 (`deferred-work.md:1064`).
- The **complete, final** `[STALE — verified against current code by skillars-deferred-40 story creation,
  2026-08-19: ...]` annotation — full reasoning text, not a placeholder — already appended to D6
  (`deferred-work.md:1105`), D7 (`deferred-work.md:1106`), and D1 (`deferred-work.md:1149`). This text is
  essentially verbatim what AC5/Task 5.5–5.6 describe as work still to be performed (see also Finding 3 on the
  D1 text's own accuracy).

This creates two concrete problems for whoever executes this story:

1. **Task 5.5 and 5.6 describe already-completed work as a to-do.** There is nothing left to change for D1, D6,
   or D7 — the annotation the story asks for is already in the file. A dev following the task list literally
   would either skip it (correct, but unstated) or risk double-annotating.
2. **Task 5.1–5.4 say to add a `[CLOSED by skillars-deferred-40 ACx]` tag, but each target line already carries
   a *different* tag — `[PICKED UP by skillars-deferred-40 ACx]` — right now.** The story never mentions this
   marker exists or explains its relationship to "CLOSED" (replace it? append alongside it? is "PICKED UP" this
   codebase's normal precursor state for a story that's been created but not yet dev-storied?). Nothing in the
   Dev Notes, AC5, or Task 5 acknowledges the marker, so a dev has no guidance on whether ending up with both
   tags on the same line is correct or a mistake.

**Recommendation:** Task 5 should say explicitly: replace each item's existing `[PICKED UP by
skillars-deferred-40 ACx]` tag with `[CLOSED by skillars-deferred-40 ACx]` (AC1–AC4), and confirm — not redo —
D1/D6/D7's already-complete `[STALE ...]` annotations (AC5's hygiene half is effectively finished already; the
task should say so, and should incorporate Finding 3's correction to D1's text while confirming it).

---

## Finding 5 (Low, confirmed): D6 and D7's ledger-section citation is wrong — no "`code review of skillars-deferred-4`, first section" exists

**Where:** AC5's fourth bullet, Task 5.5, and the References section's D6/D7 citation ("D6 and D7 (`##
Deferred from: code review of skillars-deferred-4`, first section)...").

There is exactly **one** heading in `deferred-work.md` literally titled `## Deferred from: code review of
skillars-deferred-4` — at `deferred-work.md:1148` — and it contains D1/D2/D3, not D6/D7. D6 and D7 actually live
under a completely different heading, `## Deferred from: code review of
skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)` (`deferred-work.md:1101`), inside its
`### Group 2 deferred (Services) — 2026-06-24` subsection (`deferred-work.md:1105-1106`). There is no second
occurrence of the `code review of skillars-deferred-4` heading anywhere in the file, so the story's implied
"first section" / "second section" pair (D6/D7 in one, D1 in the other, same title) doesn't exist — only D1 is
actually under that heading.

This is a citation error only — the actual annotation text was correctly placed at D6/D7's real location (see
Finding 4) — but the story's own AC5/References wording, if used to locate these items by heading search, points
a future reader at the wrong section entirely. (Same category, smaller: the References section's file path for
`SessionPackExhaustedEvent.java` is given as `platform/payment/contract/...`; the actual package is
`platform/booking/contract/SessionPackExhaustedEvent.java`.)

**Recommendation:** fix the citation to `## Deferred from: code review of
skillars-7-2-session-payment-lifecycle-credit-wallet`, `"Group 2 deferred (Services)"` subsection.

---

## Finding 6 (Low, confirmed): AC2's stated rationale — an `Authorization` header leaking via `error.config` — doesn't apply to this codebase's auth model

**Where:** AC2's required-code paragraph ("`error.config` on an Axios error can carry the request's headers,
including `Authorization`") and the matching sentence in Task 2.

`src/frontend/src/boot/axios.js:81-84` creates the shared `api` instance as `axios.create({ baseURL: '',
withCredentials: true })` — cookie-based auth (the `rint`/`skp` cookies set by `JWTAuthorizationFilter`), not a
bearer token attached via an `Authorization` header. No request interceptor on this instance ever sets
`config.headers['Authorization']` — confirmed by reading the interceptor in full (`axios.js:87-109`), which
only sets `Accept-Language`. A repo-wide grep for `Authorization` turns up exactly one unrelated usage
(`AuthorizationSignature`/`AuthorizationExpire` custom headers used by `video.api.js`'s Bunny TUS upload flow,
a completely different call path). So `error.config` on a failed `acceptBooking`/`declineBooking`/
`acceptAllBatch`/`getCoachBookingRequests` call cannot carry an `Authorization` header, because one is never
set for these calls — the specific leak AC2 names as its motivating risk does not exist at this call site
today.

This doesn't make the fix wrong — `e?.message ?? e` is still a reasonable, minimal improvement over logging a
raw Axios error object (which still contains the full request URL, method, and — depending on cookie handling
— could echo back response data in `error.response`), and there's no reason to revert it. But the rationale as
written would mislead a future reader into thinking this app uses header-based auth, and into treating this as
a closed "prevented an Authorization leak" fix when the actual (still valid) justification is the more general
"avoid dumping full request/response internals to the browser console."

**Recommendation:** reword AC2's rationale to a verb-based justification that doesn't name a specific header
this codebase doesn't use — e.g. "avoids logging full Axios request/response internals (URL, headers, response
body) to the console for a routine superseded-call case."

---

## Finding 7 (Low, confirmed): AC3 leaves a stale in-file comment describing the exact defect it just fixed

**Where:** `src/frontend/src/components/development/SkillsRadarChart.vue:155-156`, not listed anywhere in
Task 3.7 or AC3's required-code bullets.

Directly above the accessible screen-reader table, two lines up from the file's `<table class="sr-only" ...>`:

```html
<!-- NOTE: entry_count counts total rows across all assessment types and coaches; a filled dot
     may show even when the composite is capped (e.g. 3 OBJECTIVE-only assessments). -->
```

This comment documents — accurately, today — precisely the semantic mismatch AC3 exists to close. Task 3.7
lists only the three `confidenceDotFill(node.skill.entryCount)` call sites (lines ~125, 128, 132) for the
`entryCount` → `distinctCoachCount` swap; it says nothing about this comment. Once AC3 ships, the dot is filled
based on `distinctCoachCount`, and this comment becomes actively wrong — it describes a bug in the confidence
dot that no longer exists, sitting in the same file the story otherwise edits, with no other reason for a
reviewer to look at it since it isn't near any of the three edited lines.

**Recommendation:** add updating or removing this comment to Task 3.7's scope.

---

## Checked, no issue found (to save the next reader re-litigating these)

- **AC2's target line and fix:** `booking.store.js:366`'s `console.warn('Discarding failure from a superseded
  loadCoachBookingRequests call:', e)` confirmed as described; `e?.message ?? e` is a correct, minimal fix.
  Checked whether the sibling `coachRequestsError`/`coachScheduleError` refs (which also store the raw Axios
  error object elsewhere in the same store) are rendered, logged, or otherwise surfaced anywhere in the
  frontend — grepped the full `src/frontend/src` tree for both identifiers outside the store itself and found no
  consumers of either. AC2's narrow scope (the one `console.warn`) is correctly the only live leak in this
  diff's blast radius.
- **AC1's page-level claim:** `handleAccept`/`handleDecline`/`handleAcceptAll` in `CoachBookingRequestsPage.vue`
  do already wrap their calls in `try`/`catch`/`finally` with unconditional loading-flag cleanup, confirmed by
  reading all three handlers — no `onMounted`-style gap exists here, matching the story's Dev Notes. (This is
  independent of Finding 1 — the handlers themselves are fine; the bug is entirely inside `booking.api.js`.)
  One corner case worth flagging for whoever sizes the "20s is generous" claim, though not a defect: all three
  handlers' `catch` blocks also call `loadCoachBookingRequests()` again to refresh the stale list, and that call
  already carries its own 20s timeout (`skillars-deferred-39`). A hung primary call can therefore chain into a
  second 20s timeout on the recovery refresh — up to ~40s before the loading flag clears, not the flat 20s
  ceiling AC1's rationale implies. Not a bug (no permanent hang, and the pattern predates this story), just a
  detail the "bounding a genuinely hung request" framing doesn't mention.
- **AC3's entity/repository/service/contract chain:** read `PlayerRadarComposite.java`,
  `PlayerRadarCompositeRepository.java`, `RadarAssessmentRepository.java`,
  `RadarCompositeCalculationService.java`, `SkillRadarEntry.java`, both `RadarDisplayService.java` and
  `ReportGenerationService.java` construction call sites, and `SkillsRadarChart.vue` in full. Every file/line
  citation, every existing-shape claim (`countDistinctOtherCoachesBySkill`'s join/filter shape, the 4-arg
  `upsertComposite` signature), and the "`entryCount` has no other use in this file" claim (confirmed via
  full-repo grep — only `SkillsRadarChart.vue` references it in production code) all check out exactly as
  described. Confirmed `V97` is genuinely the latest migration (`V98` is free) and that
  `radar_assessment_entries.coach_id` is a non-null `UUID`, making the planned `COUNT(DISTINCT coach_id)`
  backfill query valid. `RadarCompositeCalculatorTest.java`'s existing five `upsertComposite` verifications and
  lack of any stub for the new query were confirmed exactly as Task 3.8 describes — the NPE risk it warns about
  is real.
- **AC4's approximation mechanics:** read `QuotaService.java`, `PlaybackService.java`, `Video.java`, and the
  `VideoPlayResource` in full. `video.getOwnerId()` / `video.getStorageBytes()` exist exactly as claimed (`Long
  storageBytes`, nullable column). Confirmed `storageBytes` is only ever set to a positive value after encoding
  completes (`VideoService.java:369`, gated on `finalMeta.storageBytes() > 0`), so the null/non-positive guard
  AC4 specifies is addressing a real reachable state for a `READY` video, not defensive boilerplate for an
  impossible case. Confirmed `VideoPlayResource.play` is the only caller of `authorizePlayback`, and that
  `decrementStorageBytes`'s no-op-on-missing-row behavior is safe to mirror for `incrementBandwidthUsedBytes`
  since `ensureQuotaRowExists` already runs at reservation time, before a video can ever reach `READY`.
  `PlaybackServiceIT.java` makes no assertions about bandwidth or storage, so it is unaffected by AC4's change
  beyond needing `QuotaService` to be wireable (it is, via the full Spring context that IT already uses).
  Confirmed no file literally named `PlaybackServiceTest` exists (only `PlaybackServiceIT` and
  `PlaybackRevocationWindowUnitTest` — see Finding 2 for the compile break on the latter).
