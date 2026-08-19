# Story Deferred-40: Coach-Action Timeout Hardening, Radar Confidence-Indicator Accuracy & Video Bandwidth Tracking

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want the three remaining coach-action calls that share `skillars-deferred-39`'s "zero timeout
precedent" risk class hardened, a minor error-object logging leak from that same story closed, the
skills-radar confidence indicator to actually reflect distinct-coach agreement instead of raw row count,
and video bandwidth usage to be tracked instead of permanently stuck at zero,
so that four independently-real, independently-small gaps — two fresh follow-ups from
`skillars-deferred-39`'s own review, and two long-standing "STILL OPEN" items surfaced by re-auditing the
full ledger — are closed in one pass instead of each waiting for its own single-item story.

### Why this story exists

This story's creation was explicitly instructed to **bundle small, unrelated, decision-light items
together rather than create another narrow 1-2 AC story** — the last six passes (`skillars-deferred-34`
through `39`) each produced a small, focused story, and the user asked this pass to look harder and group
more aggressively.

`_bmad-output/implementation-artifacts/deferred-work.md` (1583 lines as of this story's creation) was
re-read end to end, including sections the last several passes did not re-open (the file's protocol notes
near the top explain `[CLOSED by ...]`, `[AUDIT ...]`, and "examined and deliberately left alone" as the
three states that make an item non-actionable). Two categories of genuine, currently-open, appropriately-
sized items were found:

**Fresh, from `skillars-deferred-39`'s own review (added the same day this story is created):**

- **`## Deferred from: story-review of skillars-deferred-39-...`** — the three sibling per-action loading
  flags (`acceptBooking`, `declineBooking`, `acceptAllBatch`) on `CoachBookingRequestsPage.vue` share the
  exact "zero timeout precedent, hangs forever if the request hangs" risk class that
  `skillars-deferred-39` AC1 fixed for `getCoachBookingRequests()` alone. Re-verified now:
  `booking.api.js:23,25,62` still has no `timeout` config on any of the three. **Candidate for this story
  (AC1).**
- **`## Deferred from: code review of skillars-deferred-39-...`** — the `console.warn` added by
  `skillars-deferred-39` AC2 logs the full raw Axios error object, which can carry request headers (e.g.
  `Authorization`) via `error.config`. Re-verified now: `booking.store.js:365-368` still passes the raw
  `e` object to `console.warn`, unchanged since `skillars-deferred-39` shipped. **Candidate for this story
  (AC2).**

**Long-standing, `[AUDIT 2026-08-04: STILL OPEN]`-tagged, re-verified against current code today:**

- **DEF2 (`## Deferred from: code review of skillars-5-3-...`, 2026-06-19), audited 2026-08-04 as still
  open.** `entry_count` in `player_radar_composites` counts total assessment rows across all assessment
  types, not distinct coaches — a semantic mismatch with Story 5.4's confidence-indicator design ("3+
  entries = filled dot" is misleading when all 3 rows come from a single coach who happened to log three
  assessments). Re-verified today: `PlayerRadarComposite.java` has only `entryCount` (no distinct-coach
  column exists in any migration through `V97`); `RadarCompositeCalculationService.java:59-79` still sums
  `totalCount` across assessment types from one query with no coach-identity awareness;
  `SkillsRadarChart.vue:248-250`'s `confidenceDotFill(entryCount)` still thresholds directly on that raw
  count. **Still genuinely open. Candidate for this story (AC3).**
- **Def11 (`## Deferred from: code review of skillars-6-1-... Run 2`, 2026-06-20), audited 2026-08-04 as
  still open.** `bandwidth_used_bytes` in `video_quotas` is never incremented anywhere — only written by
  the initial `INSERT` (`QuotaService.java:158`) and zeroed by the monthly reset
  (`BandwidthResetService.java`). Re-verified today: `grep -rn "bandwidthUsedBytes\|bandwidth_used_bytes"
  src/main` still shows zero increment call sites — only the INSERT default, the entity column, the
  monthly-reset query, and the response DTO. **Still genuinely open. Candidate for this story (AC4).**

**Decision made during this story's creation — why these four and not others:** every other candidate
examined either already carries its own "examined and left alone" accepted-tradeoff reasoning on record
(e.g. the async-composite no-retry gap, DEF4/DEF3 in the same 5-3 section — accepted per that story's own
dev notes), needs product input rather than a dev decision (the pack-selection FIFO-vs-soonest-expiring
mismatch, flagged twice already, `skillars-11-2`/`skillars-deferred-11`), or would itself require a large
standalone infrastructure decision disproportionate to this story (standing up frontend test
infrastructure — the standing gap `skillars-deferred-35`–`39` have all left alone, left alone again here
for the same reason). AC1/AC2 are same-file follow-ups to `skillars-deferred-39`; AC3/AC4 are unrelated
backend modules (`development`, `video`) bundled here purely because both are small, real, decision-light,
and this pass was explicitly asked to bundle rather than defer a seventh time.

**Three additional items were found to be stale during this research — not story material, but corrected
here as a hygiene by-product (AC5):**

- **D6 (`## Deferred from: code review of skillars-deferred-4`, 2026-07-02), audited 2026-08-04 as still
  open — the audit itself is now stale.** `BookingDisputedEvent` was never implemented as literally named,
  but the dispute-handling need it pointed at **is** fully implemented via a different, complete design:
  `platform.admin.service.DisputeService` (`raiseDispute`/`resolveDispute`/`dismissDispute`, publishing
  `DisputeRaisedEvent`/`DisputeResolvedEvent`/`CoachWarningIssuedEvent`). Superseded-by-different-design,
  not a gap.
- **D7, same section, audited 2026-08-04 as still open — stale.** `SessionPackExhaustedEvent.playerId`
  is already fixed: the class now declares `parentId` with a comment citing "Deferred-12 AC5".
- **D1 (`## Deferred from: code review of skillars-deferred-4`, second section, 2026-07-02) — never
  audited, found partially stale today.** Of the two named services, only one is a genuine preventive fix:
  `QuotaReservationTimeoutService.java:23` (`MAX_RUN_DURATION`, an 8-minute self-terminating drain deadline
  under the 10-minute `lockAtMostFor`) actually stops the loop before ShedLock can force-expire the lock —
  this half is fixed. `NeglectedSkillDetectionService.java:25-28,81-82` only logs an 80%-of-budget
  `log.warn` tripwire and does **not** stop, chunk, or bound its loop — the underlying race (ShedLock
  force-expiring the lock mid-run, letting a second instance start overlapping) remains structurally
  possible for this service; the guard is a deliberate, documented detective control (the job's own comment
  explains it fires only weekly, so bailing out early isn't safe), not a preventive one. D1's ledger
  annotation should say so explicitly rather than closing both halves under the same "already fixed"
  language — re-verified by reading both files in full today.

## Acceptance Criteria

1. **AC1 — Timeout-safe `acceptBooking`, `declineBooking`, `acceptAllBatch`.** All three gain the same
   scoped `{ timeout: 20000 }` axios config `skillars-deferred-39` AC1 added to `getCoachBookingRequests()`
   — each on its own call, not the shared `api` instance. **Axios call-signature warning: `getCoachBookingRequests`
   is a `get(url, config)` — two args — but `acceptBooking`/`declineBooking` are `put(url, data, config)` and
   `acceptAllBatch` is `post(url, data, config)` — three args, where the second is the request body, not
   config. All three are currently called with no body argument. Simply appending `{ timeout: 20000 }` as a
   second argument lands it in the body slot, not the config slot, and the timeout silently never applies.
   Each call must pass an explicit `undefined` data argument before the config object:**
   ```js
   export const acceptBooking = (id) =>
     api.put(`/api/bookings/requests/${id}/accept`, undefined, { timeout: 20000 })
   export const declineBooking = (id) =>
     api.put(`/api/bookings/requests/${id}/decline`, undefined, { timeout: 20000 })
   export const acceptAllBatch = (batchId) =>
     api.post(`/api/bookings/batches/${batchId}/accept-all`, undefined, { timeout: 20000 })
   ```
   - `acceptBooking` (`src/frontend/src/api/booking.api.js:23`)
   - `declineBooking` (`src/frontend/src/api/booking.api.js:25`)
   - `acceptAllBatch` (`src/frontend/src/api/booking.api.js:65`)

   No `onMounted`-style companion fix is needed here (unlike `skillars-deferred-39` AC1b): all three calls
   are already properly `await`ed inside `handleAccept`/`handleDecline`/`handleAcceptAll`
   (`CoachBookingRequestsPage.vue`), each wrapped in its own `try`/`catch`/`finally` that unconditionally
   clears its own per-row/per-batch loading flag (`accepting[id]`, `declining[id]`,
   `acceptingAll[batchId]`) regardless of outcome — there is no sequencing guard here for a timeout to
   interact badly with, unlike `loadCoachBookingRequests()`'s `coachRequestsSequence` counter. A timed-out
   request is just another rejected promise, caught and handled identically to any other error already
   handled by these functions today. No other code changes required for this AC.

2. **AC2 — Redact the error object logged for a discarded superseded-call failure.** In
   `booking.store.js`'s `loadCoachBookingRequests()` `catch` block (line ~365-368, added by
   `skillars-deferred-39` AC2), change
   `console.warn('Discarding failure from a superseded loadCoachBookingRequests call:', e)` to log
   `e?.message ?? e` instead of the raw `e` object — avoids logging full Axios request/response internals
   (URL, method, and any response body echoed back in `error.response`) to the console for a routine
   superseded-call case. (Note: this codebase's `api` instance uses cookie-based auth — `withCredentials:
   true`, no `Authorization` header is ever set on this call path — so this is a general internals-hygiene
   fix, not the closure of a specific header-leak.) This is a minimal, scoped fix to the one `console.warn`
   this story's own predecessor added; it does **not** change `video.store.js`'s existing
   `console.warn('<message>:', err)` convention elsewhere, which is out of this AC's scope (see Dev Notes).

3. **AC3 — Skills-radar confidence indicator reflects distinct coaches, not total assessment rows.**
   Today `SkillsRadarChart.vue`'s confidence dot thresholds on `entryCount` (total assessment rows across
   all types), which a single prolific coach can fill on their own — misleading against Story 5.4's actual
   design intent ("multiple raters agree"). Add a new `distinct_coach_count` column, computed and
   maintained separately from `entryCount` (which keeps its current, correct "total assessment rows"
   meaning — it is also read by `ReportGenerationService` for report generation, unrelated to the
   confidence dot, and must not change meaning for that consumer):
   - **Migration** (`src/main/resources/db/migration/`, next number after `V97`): add
     `development.player_radar_composites.distinct_coach_count` (`INTEGER NOT NULL DEFAULT 0`), then
     backfill existing rows in the same migration from
     `development.radar_assessment_entries` (`COUNT(DISTINCT coach_id)` grouped by `player_id, skill_code`)
     — do not ship a migration that silently zeroes every existing player's confidence dot until their
     next assessment.
   - **New repository query** on `RadarAssessmentRepository`, mirroring `countDistinctOtherCoachesBySkill`'s
     existing shape (same joins/filters) but without excluding any coach: `SELECT rae.skill_code,
     COUNT(DISTINCT rae.coach_id) ... GROUP BY rae.skill_code`, scoped to `playerId`/`parentId`/`skillCodes`
     like `findAggregatesByPlayerAndSkills` is.
   - **`RadarCompositeCalculationService.onRadarEntrySubmitted`**: call the new query alongside the
     existing `findAggregatesByPlayerAndSkills` call, build a `skill -> distinctCoachCount` map, and pass
     it into `upsertComposite`.
   - **`PlayerRadarCompositeRepository.upsertComposite`**: add `distinct_coach_count` as a 5th column/param
     to the existing native upsert query (INSERT column list + `ON CONFLICT ... DO UPDATE SET`).
   - **`PlayerRadarComposite` entity**: add `distinctCoachCount` (`Integer`, `distinct_coach_count` column).
   - **`SkillRadarEntry` contract record**: add `Integer distinctCoachCount` alongside the existing
     `entryCount` field (do not replace it).
   - **`RadarDisplayService` and `ReportGenerationService`**: both construct `SkillRadarEntry` directly from
     a `PlayerRadarComposite` — update both call sites to also pass `comp != null ?
     comp.getDistinctCoachCount() : null`.
   - **`SkillsRadarChart.vue`**: change all three `confidenceDotFill(...)` call sites (lines ~125, 128,
     132) from `node.skill.entryCount` to `node.skill.distinctCoachCount`. Confirmed by inspection:
     `entryCount` has no other use anywhere else in this file (only these three call sites reference it) —
     this is a clean swap, not a partial one. Also update or remove the stale comment at
     `SkillsRadarChart.vue:155-156` ("entry_count counts total rows across all assessment types and
     coaches; a filled dot may show even when the composite is capped...") — it documents exactly the bug
     this AC fixes and will be actively wrong once the dot is driven by `distinctCoachCount`.

4. **AC4 — Video bandwidth usage is tracked on playback (approximated by file size, not exact metering).**
   `bandwidth_used_bytes` is currently never incremented. Real per-request byte metering is not available:
   confirmed by reading Bunny.net's Stream API "Get Video Statistics" endpoint
   (`GET /library/{libraryId}/statistics`) — its response (`VideoStatisticsModel`) contains views, watch
   time, country breakdowns, and engagement score, but **no bandwidth field**; Bunny's dashboard-level
   "Bandwidth served" figure is aggregate per-library/pull-zone, not attributable to a specific video or
   owner via any documented API. Given that, this AC ships the smallest honest approximation available
   with data this backend already has: **charge the video's known file size (`storageBytes`, already
   captured at upload) to its owner's bandwidth counter once per playback authorization** — a reasonable
   upper-bound proxy for "this playback will consume roughly this much bandwidth," consistent with how
   this codebase already tracks storage by file size rather than by live disk usage.
   - **`QuotaService`**: add `incrementBandwidthUsedBytes(String ownerId, long bytes)`, mirroring the
     existing `decrementStorageBytes(String ownerId, long bytes)` shape exactly (a guarded, single atomic
     `UPDATE main.video_quotas SET bandwidth_used_bytes = bandwidth_used_bytes + ? WHERE user_id = ?`, a
     `bytes <= 0` no-op guard, a `log.debug` on success). Not part of the `QuotaProvider` interface (like
     `decrementStorageBytes`, it is a plain additional method).
   - **`PlaybackService`**: inject `QuotaService`. In `authorizePlayback` (`PlaybackService.java`), after
     `videoProviderAdapter.generatePlaybackUrl(...)` succeeds (line ~106-108), call
     `quotaService.incrementBandwidthUsedBytes(video.getOwnerId(), video.getStorageBytes())` — guard for a
     `null` or non-positive `storageBytes` (skip the call rather than incrementing by zero/NPE).
     **Compile-break warning: `PlaybackService` uses `@RequiredArgsConstructor` over its `private final`
     fields, so adding `QuotaService` as a new field changes the generated constructor's arity from 6 to 7.
     `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java:53`
     constructs `PlaybackService` directly with the current 6-arg constructor — the only manual-construction
     site in the repo — and will fail to compile until it also passes a `@Mock QuotaService quotaService`.
     This file must be updated as part of this AC (`PlaybackServiceIT.java` is unaffected — it `@Autowire`s
     `PlaybackService` from the Spring context and will pick up the real bean automatically).**
   - **Explicitly out of scope, documented as considered and rejected:** integrating Bunny's CDN log-push
     for exact per-request metering (a standalone infrastructure project, disproportionate to this story);
     adding a bandwidth **quota enforcement** check (this AC fixes the tracking gap only — the ledger item
     never asked for a new limit, and adding one is a separate product decision).

5. **AC5 — Ledger hygiene.** `deferred-work.md` already carries `[PICKED UP by skillars-deferred-40 ACx]`
   markers on all four AC1–AC4 source items and the **complete, final** `[STALE — verified against current
   code by skillars-deferred-40 story creation, 2026-08-19: ...]` annotation text on D1/D6/D7, all added as
   part of this story's own creation commit (`358f575`). This AC's remaining work is therefore narrower than
   a first read suggests:
   - The `story-review of skillars-deferred-39-...` section's sibling-timeout item: replace its existing
     `[PICKED UP by skillars-deferred-40 AC1]` tag with `[CLOSED by skillars-deferred-40 AC1]`.
   - The `code review of skillars-deferred-39-...` section's `console.warn` item: replace its existing
     `[PICKED UP by skillars-deferred-40 AC2]` tag with `[CLOSED by skillars-deferred-40 AC2]`.
   - DEF2 (`## Deferred from: code review of skillars-5-3-...`): replace its existing `[PICKED UP by
     skillars-deferred-40 AC3]` tag with `[CLOSED by skillars-deferred-40 AC3]`.
   - Def11 (`## Deferred from: code review of skillars-6-1-... Run 2`): replace its existing `[PICKED UP by
     skillars-deferred-40 AC4]` tag with `[CLOSED by skillars-deferred-40 AC4]`.
   - D6 and D7 (`## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet
     (2026-06-24)`, `### Group 2 deferred (Services)` subsection): the `[STALE — ...]` annotation text is
     already complete and correct — confirm it, no further edit needed.
   - D1 (`## Deferred from: code review of skillars-deferred-4`, second section): the `[STALE — ...]`
     annotation is already present but needs correcting per this story's own D1 finding above — it must
     distinguish `QuotaReservationTimeoutService` (a genuine preventive fix) from
     `NeglectedSkillDetectionService` (a detective tripwire that leaves the underlying race structurally
     possible) rather than closing both under the same "already fixed" language.

## Tasks / Subtasks

- [x] Task 1: Timeout-harden the three sibling booking calls (AC: #1)
  - [x] 1.1 In `src/frontend/src/api/booking.api.js`, add a `{ timeout: 20000 }` config to `acceptBooking`,
    `declineBooking`, and `acceptAllBatch`. **These are `put`/`post` calls, not `get`** — pass an explicit
    `undefined` data argument before the config object (`api.put(url, undefined, { timeout: 20000 })` /
    `api.post(url, undefined, { timeout: 20000 })`), otherwise `{ timeout: 20000 }` lands in the request
    body slot and the timeout is never actually configured. Match `getCoachBookingRequests()`'s existing
    comment style (scoped to this call only, 20s choice explained once, not repeated three times verbatim —
    a single shared comment above the three, or one line per export, either is fine as long as the "scoped
    to this call, not the shared instance" rationale is clear).
  - [x] 1.2 Confirm by inspection (no code change) that `handleAccept`/`handleDecline`/`handleAcceptAll`
    in `CoachBookingRequestsPage.vue` need no change — their existing `try`/`catch`/`finally` shape already
    handles a timeout rejection like any other error.
  - [x] 1.3 Run `npx eslint src/api/booking.api.js` from `src/frontend` and confirm clean.
- [x] Task 2: Redact the AC2 console.warn from skillars-deferred-39 (AC: #2)
  - [x] 2.1 In `booking.store.js`'s `loadCoachBookingRequests()` catch block, change the `console.warn`
    added by `skillars-deferred-39` to log `e?.message ?? e` instead of the raw `e` object. No other
    change to that block.
  - [x] 2.2 Run `npx eslint src/stores/booking.store.js` from `src/frontend` and confirm clean.
- [x] Task 3: Radar composite distinct-coach-count (AC: #3)
  - [x] 3.1 Add a Flyway migration (next `V` number after `V97`) adding
    `development.player_radar_composites.distinct_coach_count INTEGER NOT NULL DEFAULT 0`, then backfilling
    existing rows from `development.radar_assessment_entries` grouped by `(player_id, skill_code)` with
    `COUNT(DISTINCT coach_id)`.
  - [x] 3.2 Add a new query to `RadarAssessmentRepository` returning `[skill_code, COUNT(DISTINCT
    coach_id)]` rows for a given `playerId`/`parentId`/`skillCodes`, mirroring
    `countDistinctOtherCoachesBySkill`'s join/filter shape but without excluding any coach.
  - [x] 3.3 In `RadarCompositeCalculationService.onRadarEntrySubmitted`, call the new query, build a
    `skill -> distinctCoachCount` map (default 0 for a skill with no matching row), and pass it as a 5th
    argument to `compositeRepository.upsertComposite(...)`.
  - [x] 3.4 Update `PlayerRadarCompositeRepository.upsertComposite`'s native query (INSERT column list +
    `ON CONFLICT ... DO UPDATE SET`) to add `distinct_coach_count`, and its method signature to accept the
    new `int distinctCoachCount` param.
  - [x] 3.5 Add `distinctCoachCount` (`Integer`, column `distinct_coach_count`) to `PlayerRadarComposite`.
  - [x] 3.6 Add `Integer distinctCoachCount` to the `SkillRadarEntry` record (after `entryCount`, before
    `lastUpdatedAt`), and update both constructor call sites — `RadarDisplayService.java:62-69` and
    `ReportGenerationService.java:228-235` — to pass `comp != null ? comp.getDistinctCoachCount() : null`.
  - [x] 3.7 In `SkillsRadarChart.vue`, change the three `confidenceDotFill(node.skill.entryCount)` call
    sites to `confidenceDotFill(node.skill.distinctCoachCount)`. Leave `entryCount` itself untouched
    everywhere else — it is not read anywhere else in this file, but is still a valid, differently-scoped
    field on the same DTO for other future consumers.
  - [x] 3.8 Update `src/test/java/com/softropic/skillars/platform/development/service/
    RadarCompositeCalculatorTest.java`: this file mocks `radarRepository`/`compositeRepository` directly
    against `RadarCompositeCalculationService` (despite its own filename). **Every existing
    `verify(compositeRepository).upsertComposite(...)` call in this file needs a 5th argument added**, and
    **every existing test needs a new `when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(...))
    .thenReturn(...)` stub** (or equivalent) — without it, an unstubbed Mockito mock returns `null` for the
    new query, which will NPE when the service iterates the result. Add at least one new test asserting the
    distinct-coach count is correctly computed when multiple rows share one skill across different coaches
    vs. the same coach.
  - [x] 3.9 Run the extended `RadarCompositeCalculatorTest` and confirm it passes.
- [x] Task 4: Video bandwidth tracking on playback (AC: #4)
  - [x] 4.1 Add `incrementBandwidthUsedBytes(String ownerId, long bytes)` to `QuotaService`, mirroring
    `decrementStorageBytes`'s shape (guarded no-op for `bytes <= 0`, single atomic `UPDATE ... SET
    bandwidth_used_bytes = bandwidth_used_bytes + ?`, `log.debug` on success). Not part of the
    `QuotaProvider` interface.
  - [x] 4.2 Inject `QuotaService` into `PlaybackService`. In `authorizePlayback`, after
    `videoProviderAdapter.generatePlaybackUrl(...)` succeeds, call `quotaService.incrementBandwidthUsedBytes
    (video.getOwnerId(), video.getStorageBytes())`, guarding a `null`/non-positive `storageBytes`. **This
    adds a 7th field to `@RequiredArgsConstructor`-generated `PlaybackService`, changing its constructor
    arity — update `src/test/java/com/softropic/skillars/platform/video/service/
    PlaybackRevocationWindowUnitTest.java:53`'s direct `new PlaybackService(...)` call (the only manual
    construction site in the repo) to add a `@Mock QuotaService quotaService` field and pass it through, or
    this test file will fail to compile.**
  - [x] 4.3 Add or extend a unit test covering `PlaybackService.authorizePlayback` to assert
    `incrementBandwidthUsedBytes` is called with the video's owner id and storage bytes on a successful
    playback authorization, and is NOT called when authorization fails before the playback URL is
    generated (e.g. an ineligible video state). No existing `PlaybackServiceTest` exists — create one
    following this codebase's Mockito unit-test conventions (see
    `RadarCompositeCalculatorTest.java`/`PlaybackRevocationWindowUnitTest.java` for the house style), or
    add a focused test class scoped to just this behavior if a full `authorizePlayback` test would be
    disproportionate — targeted coverage of the new call, not a full re-test of existing
    `authorizePlayback` behavior.
  - [x] 4.4 Add a unit test for `QuotaService.incrementBandwidthUsedBytes` covering the `bytes <= 0` no-op
    guard and the successful-increment path (see `QuotaReservationTimeoutServiceTest.java`/
    `NoOpQuotaProviderTest.java` for this module's existing test conventions).
- [x] Task 5: Ledger hygiene (AC: #5)
  - [x] 5.1 The `story-review of skillars-deferred-39-...` sibling-timeout item already carries `[PICKED UP
    by skillars-deferred-40 AC1]` (added at this story's creation) — replace that tag with `[CLOSED by
    skillars-deferred-40 AC1]`.
  - [x] 5.2 The `code review of skillars-deferred-39-...` `console.warn` item already carries `[PICKED UP by
    skillars-deferred-40 AC2]` — replace that tag with `[CLOSED by skillars-deferred-40 AC2]`.
  - [x] 5.3 DEF2 (`code review of skillars-5-3-...`) already carries `[PICKED UP by skillars-deferred-40
    AC3]` — replace that tag with `[CLOSED by skillars-deferred-40 AC3]`.
  - [x] 5.4 Def11 (`code review of skillars-6-1-... Run 2`) already carries `[PICKED UP by
    skillars-deferred-40 AC4]` — replace that tag with `[CLOSED by skillars-deferred-40 AC4]`.
  - [x] 5.5 D6 and D7 (`## Deferred from: code review of
    skillars-7-2-session-payment-lifecycle-credit-wallet`, `### Group 2 deferred (Services)` subsection)
    already carry the complete `[STALE — verified ... 2026-08-19: ...]` annotation from this story's
    creation — confirm the text is accurate (it is) and leave as-is; no edit needed.
  - [x] 5.6 D1 (`code review of skillars-deferred-4`, second section) already carries a `[STALE — ...]`
    annotation from this story's creation, but it overstates the fix — correct it to distinguish
    `QuotaReservationTimeoutService` (a genuine preventive `MAX_RUN_DURATION` deadline — fixed) from
    `NeglectedSkillDetectionService` (an 80%-of-budget `log.warn` tripwire only — a deliberate detective
    control, not preventive; the underlying race remains structurally possible), per this story's own D1
    finding above.

### Review Findings

Code review (2026-08-20): Blind Hunter (12 raw findings) + Edge Case Hunter (1 finding) + Acceptance Auditor
(0 AC violations — implementation verified to match AC1–AC5 and the Dev Agent Record's completion claims
exactly). After deduplication and independent verification against the live code (not taken on any layer's
word), 8 of the 12 Blind Hunter findings were dismissed as false positives or non-issues (see below); the
remaining findings are:

- [x] [Review][Defer] Bandwidth is charged per `authorizePlayback` call, not per unique viewing session —
  every re-authorization of the same video (token refresh, page reload, retry, a second concurrent viewer)
  re-charges the owner's bandwidth counter the video's full `storageBytes` again, with no dedup window. For a
  video authorized many times, this can overcount real bandwidth usage well beyond the single-charge-per-view
  approximation AC4's Dev Notes describe. [`PlaybackService.java:111-118`] — deferred, needs a full design
  review before fixing: `bandwidth_used_bytes` feeds an enforced bandwidth **quota**, not just a reporting
  number, so overcounting/undercounting has real product consequences, not merely a cosmetic one. Whoever
  picks this up must check what the spec actually wants, evaluate each candidate dedup rule (per playback
  token? per viewer+video+time-bucket?) against spec intent, weigh its resistance to gaming (a viewer
  deliberately re-triggering re-authorization to inflate or evade the owner's usage), and weigh the
  tradeoffs each option brings — not be patched ad hoc as part of a review.

- [x] [Review][Patch] `incrementBandwidthUsedBytes` has no exception isolation — if it throws (e.g. a
  transient DB/JDBC error) after `videoProviderAdapter.generatePlaybackUrl(...)` has already succeeded, the
  exception propagates out of the `@Transactional` `authorizePlayback` method with no catch block (only a
  `finally` for metrics/MDC cleanup), aborting the whole method and denying playback despite the video being
  legitimately playable and the external provider call having already succeeded. A purely internal bandwidth-
  bookkeeping failure should not be able to block playback. [`PlaybackService.java:111-118`] — **Applied:**
  wrapped the increment call in its own `try`/`catch (Exception ex)`, logging `log.warn(...)` on failure and
  letting `authorizePlayback` continue to return the already-issued playback response. Verified via targeted
  `mvn -o test -Dtest=QuotaServiceTest,PlaybackServiceTest,PlaybackRevocationWindowUnitTest` — 7/7 green.

- [x] [Review][Patch] `e?.message ?? e` only falls back on `null`/`undefined`, not on a falsy-but-defined
  empty-string `message` — an error whose `.message` is `""` would log an empty string, discarding all
  diagnostic information on the exact path this fix exists to make more useful. Low likelihood, mechanical
  fix (e.g. `e?.message || e`). [`booking.store.js:366`] — **Applied:** changed `??` to `||`. Verified via
  `npx eslint src/stores/booking.store.js` — clean.

- [x] [Review][Defer] The new `findDistinctCoachCountsByPlayerAndSkills` query, run alongside the existing
  `findAggregatesByPlayerAndSkills` query inside the same `@Async`/`AFTER_COMMIT` listener, marginally widens
  the already-documented, already-accepted DEF3 concurrent-recalculation race (two simultaneous submissions
  for the same player can now each read two independent, non-atomic snapshots before either upserts). DEF3
  itself already accepts this as a "theoretical low-probability issue" that self-corrects on the next
  submission — this diff doesn't introduce a new category of risk, just widens an existing, accepted one
  slightly. [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`] — deferred, pre-existing
  (widened, not introduced, by this diff)
- [x] [Review][Defer] Migration `V98`'s backfill is an unbatched, unchunked full-table `UPDATE` joined
  against `radar_assessment_entries`, grouped by `(player_id, skill_code)` — the same scaling shape as
  `Def10`'s already-accepted "full-table lock risk" concern for `video_quotas`, applied here to a different
  table. Not blocking at current expected table size; worth tracking if `player_radar_composites` grows
  large enough for migration-time locking to matter. [`V98__player_radar_composites_distinct_coach_count.sql`]
  — deferred, pre-existing pattern

**Dismissed as false positives or non-issues (verified independently against the live code, not on the
reviewing layer's word):**
- "`incrementBandwidthUsedBytes` discards the JDBC update row count and logs success unconditionally" —
  verified to be a faithful, spec-mandated mirror of the pre-existing `decrementStorageBytes` method's exact
  shape (same no-row-count-check pattern already exists there); not a new defect introduced by this diff.
- "No guard against a null/blank `ownerId`" — same reasoning; `ownerId` is not realistically nullable for a
  video that has reached `READY` state, and the existing `decrementStorageBytes` has no such guard either.
- "`PlaybackServiceTest` calls a 2-arg `authorizePlayback` that doesn't exist" (Blind Hunter, no project
  context) — verified false: `PlaybackService.java:150-152` already has a legitimate pre-existing 2-arg
  overload (`authorizePlayback(UUID, String)`) that delegates to the 3-arg version.
- "`QuotaServiceTest`'s `never()` checks use a mismatched Mockito matcher, making them vacuous" — verified to
  be a standard, correct Mockito idiom for asserting zero interactions of any shape on a mock that is
  otherwise unused in that test; not a real defect.
- "`QuotaServiceTest` hardcodes the literal SQL string, testing string-equality rather than behavior" —
  verified to be the only reasonable way to unit-test a raw-`JdbcTemplate` method against a mock; integration
  coverage already exists separately (`QuotaServiceConcurrencyIT`, referenced in the test's own header
  comment).
- "`story-review.md` is fully overwritten rather than appended" — confirmed to match this repo's established
  per-story review-file convention (each story's review replaces the prior one; prior content remains
  recoverable via `git log`/`git blame`), not a defect introduced by this diff.
- "Test setup boilerplate duplicated across test methods in the two new test files" — pure style nit, no
  functional risk; not worth a finding.
- "No upper bound/sanity check on the bandwidth counter" — same underlying concern as the decision-needed
  re-authorization overcounting item above; not a separate issue.

## Dev Notes

- **This story bundles four unrelated fixes across three areas (booking frontend, development backend,
  video backend) by explicit instruction — do not look for a unifying theme beyond "small, real,
  decision-light, and this pass was asked to bundle."** AC1/AC2 are direct, same-file follow-ups to
  `skillars-deferred-39`. AC3/AC4 are independent long-standing ledger items unrelated to either
  `skillars-deferred-39` or each other.
- **AC1 has no `(b)` half unlike `skillars-deferred-39` AC1** — do not add an `isMounted`-style guard or
  any `onMounted` change here. That pattern existed in `skillars-deferred-39` specifically because
  `onMounted`'s fire-and-forget call became newly-awaited and could resolve after unmount; the three calls
  here are already properly awaited inside click handlers with unconditional `finally` cleanup and were
  never fire-and-forget, so no equivalent gap exists.
- **AC2 is deliberately narrow** — only the one `console.warn` `skillars-deferred-39` AC2 added. Do not
  touch `video.store.js`'s own `console.warn('<message>:', err)` call sites (lines 189, 210) — they were
  not introduced by this diff's pattern choice and are out of this story's blast radius; if that broader
  convention is worth revisiting repo-wide, that is a separate decision for a future pass, not this AC.
- **AC3's `entryCount` vs `distinctCoachCount` distinction is load-bearing — do not conflate or rename.**
  `entryCount` ("total assessment rows") is a real, correctly-named, still-needed field read by
  `ReportGenerationService` for an unrelated purpose. `distinctCoachCount` is a new, additional field
  purpose-built for the confidence dot. Renaming or repurposing `entryCount` would silently change
  `ReportGenerationService`'s output.
- **AC3's migration must backfill, not just add-with-default.** A bare `ADD COLUMN ... DEFAULT 0` would
  make every existing player's confidence dot render "empty" until their next assessment recomputes the
  row — a visible regression for every player with existing radar data, not just a schema change. Backfill
  in the same migration using the real historical data still in `radar_assessment_entries`.
  `PlayerRadarComposite`'s PK is `(player_id, skill_code)` — the migration should reference those columns
  directly (native SQL against the table, not JPA/entity paths).
- **AC4's approximation is a documented, explicit tradeoff, not an oversight** — real per-request
  metering was researched (Bunny.net's `GET /library/{libraryId}/statistics` "Get Video Statistics"
  endpoint response schema has no bandwidth field; the dashboard's aggregate "Bandwidth served" figure is
  library/pull-zone-wide, not attributable to a single video or owner via any documented API) and found
  unavailable without a much larger CDN-log-push integration project, explicitly out of this story's scope.
  If a future story adds real metering, `bandwidth_used_bytes` will need to be treated as an estimate
  needing reconciliation, not overwritten silently — flag that consideration to whoever picks that up, but
  do not solve it here.
- **AC4 charges the video owner, not the viewer** — consistent with how `storage_used_bytes` already
  charges the uploader/owner for hosting cost, not whoever happens to be viewing. `viewerId` (the playback
  requester) is a separate concept from `video.getOwnerId()` and is not involved in this AC.
- **A stray discovery, not part of this story's scope:** `src/frontend/src/components/development/
  __tests__/SkillsRadarChartSpec.js` exists and imports `vitest`/`@vue/test-utils`, but neither is a
  `package.json` dependency and `npm test` is still a no-op (`"echo \"No test specified\" && exit 0"`) —
  this spec file cannot currently run; it appears to be an orphaned artifact from an earlier, abandoned
  attempt at frontend test infrastructure. Left untouched — the standing "no frontend test infrastructure"
  gap remains out of scope for this story exactly as `skillars-deferred-35`–`39` left it — but worth noting
  for whoever eventually picks up that gap: this file already models the `SkillRadarEntry` shape reasonably
  well (see its `makeSkill`/`makeSkills` factories) and would need its own `entryCount`-only mock data
  extended with `distinctCoachCount` once this story's AC3 ships, if it's ever revived.
- Per `docs/validation-strategy.md`, run targeted tests only (the extended `RadarCompositeCalculatorTest`,
  the new/extended `PlaybackService`/`QuotaService` tests, and `npx eslint` on the touched frontend files)
  — do not run `mvn verify` unless targeted tests prove insufficient or this is final pre-PR validation.

### Project Structure Notes

- `src/frontend/src/api/booking.api.js` — three one-line changes (AC1).
- `src/frontend/src/stores/booking.store.js` — one-line change inside an existing catch block (AC2).
- `src/main/resources/db/migration/V98__....sql` (or next free number) — new migration (AC3).
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentRepository.java` — new
  query (AC3).
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java` —
  extend `upsertComposite` (AC3).
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarComposite.java` — new field
  (AC3).
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
  — call new query, pass new arg (AC3).
- `src/main/java/com/softropic/skillars/platform/development/contract/SkillRadarEntry.java` — new field
  (AC3).
- `src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java` and
  `ReportGenerationService.java` — pass new field through (AC3).
- `src/frontend/src/components/development/SkillsRadarChart.vue` — three call-site changes (AC3).
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java` —
  extend every existing test + add new coverage (AC3).
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` — new method (AC4).
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` — new dependency,
  one new call (AC4).
- New or extended test file(s) for `PlaybackService` and `QuotaService` (AC4).
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java` —
  update the direct `new PlaybackService(...)` construction call to add the new `QuotaService` mock
  argument, or this file fails to compile once AC4 changes `PlaybackService`'s constructor arity (AC4).
- `_bmad-output/implementation-artifacts/deferred-work.md` — four closures + two stale-item corrections
  (AC5).
- No new frontend files. No changes to `boot/axios.js`, `VideoProviderAdapter`/`BunnyVideoProviderAdapter`,
  or any Bunny.net integration.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: story-review of
  skillars-deferred-39-...` and `## Deferred from: code review of skillars-deferred-39-...` sections, AC1/
  AC2's sources]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines ~685-689 — DEF2, `## Deferred
  from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation`, AC3's source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines ~1062-1065 — Def11, `## Deferred
  from: code review of skillars-6-1-video-module-foundation-quota-system Run 2`, AC4's source]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-39-coach-refresh-timeout-safety-and-diagnostic-logging.md`
  — the story AC1/AC2 directly follow up on; establishes the timeout-scoping and `console.warn` patterns]
- [Source: `src/frontend/src/api/booking.api.js` lines 23, 25, 29-31, 65 — `acceptBooking`,
  `declineBooking`, `getCoachBookingRequests` (the AC1a precedent), `acceptAllBatch`]
- [Source: `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` lines 173-206, 246-293 —
  `handleAccept`/`handleDecline`/`handleAcceptAll`, confirmed to already properly await + finally-clear
  their loading flags]
- [Source: `src/frontend/src/stores/booking.store.js` lines 364-370 — the AC2 target `console.warn`]
- [Source: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarComposite.java`,
  `PlayerRadarCompositeRepository.java`, `RadarAssessmentRepository.java` — AC3's entity/repository targets;
  `countDistinctOtherCoachesBySkill` (`RadarAssessmentRepository.java:47-58`) is the existing
  distinct-coach-count query shape to mirror without its coach-exclusion clause]
- [Source: `src/main/java/com/softropic/skillars/platform/development/service/
  RadarCompositeCalculationService.java` lines 34-83 — AC3's service target, `onRadarEntrySubmitted`]
- [Source: `src/main/java/com/softropic/skillars/platform/development/contract/SkillRadarEntry.java`,
  `RadarDisplayService.java:62-69`, `ReportGenerationService.java:228-235` — AC3's DTO and both construction
  call sites]
- [Source: `src/frontend/src/components/development/SkillsRadarChart.vue` lines 125,128,132,248-250 — AC3's
  frontend target, `confidenceDotFill`]
- [Source: `src/test/java/com/softropic/skillars/platform/development/service/
  RadarCompositeCalculatorTest.java` — AC3's existing test file to extend; tests
  `RadarCompositeCalculationService` despite its own class name]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` lines 142-149 —
  `decrementStorageBytes`, the shape AC4's new method mirrors]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` lines 48-138
  — AC4's target, `authorizePlayback`]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/video/BunnyVideoProviderAdapter.java`,
  `VideoProviderAdapter.java` — confirmed no bandwidth/statistics method exists on either]
- [Web source: Bunny.net Stream API "Get Video Statistics" (`GET /library/{libraryId}/statistics`)
  documentation, fetched during this story's creation (2026-08-19) — confirmed the response schema
  (`VideoStatisticsModel`: `viewsChart`, `watchTimeChart`, `countryViewCounts`, `countryWatchTime`,
  `engagementScore`) has no bandwidth field, informing AC4's approximation decision]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — D6, D7 (`## Deferred from: code
  review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)`, `### Group 2 deferred
  (Services)` subsection) and D1 (`## Deferred from: code review of skillars-deferred-4`, second section) —
  AC5's stale-item corrections]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java`
  lines 19-45 and `src/main/java/com/softropic/skillars/platform/development/service/
  NeglectedSkillDetectionService.java` lines 25-28, 81-82 — verification that D1's concern is a preventive
  fix for `QuotaReservationTimeoutService` but only a detective tripwire for
  `NeglectedSkillDetectionService`, which leaves the underlying race structurally possible]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java` — verification
  that D6's dispute-handling need is met by a different, complete design]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/contract/SessionPackExhaustedEvent.java`
  — verification that D7 is already fixed (`parentId`, not `playerId`)]

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

- Initial `RadarCompositeCalculatorTest` compile attempt failed: `List.of(new Object[]{"PAC", 1L})`
  passed to `thenReturn(List<Object[]>)` triggered a varargs type-inference ambiguity (`Object[]` vs
  `Object...`). Fixed with an explicit type witness (`List.<Object[]>of(...)`) on all 7 occurrences.
- `mvn -o test -Dtest=RadarCompositeCalculatorTest` → 6/6 green after the fix.
- `mvn -o test -Dtest=QuotaServiceTest,PlaybackServiceTest,PlaybackRevocationWindowUnitTest` → 7/7 green.
- `npx eslint` clean on all four touched frontend files (`booking.api.js`, `booking.store.js`,
  `SkillsRadarChart.vue` — run individually per Dev Notes' targeted-validation guidance).
- `mvn verify` not run, per `docs/validation-strategy.md` — targeted tests covered all four ACs;
  this is not final pre-PR validation.

### Completion Notes List

- **AC1**: `acceptBooking`, `declineBooking`, `acceptAllBatch` in `booking.api.js` now pass an explicit
  `undefined` data argument plus `{ timeout: 20000 }`, matching `getCoachBookingRequests()`'s existing
  scoped-timeout precedent. Confirmed by inspection (no code change) that
  `handleAccept`/`handleDecline`/`handleAcceptAll` in `CoachBookingRequestsPage.vue` already
  `try`/`catch`/`finally`-clear their own loading flags unconditionally — a timeout rejection is handled
  identically to any other error. `npx eslint src/api/booking.api.js` clean.
- **AC2**: `booking.store.js`'s `loadCoachBookingRequests()` catch-block `console.warn` now logs
  `e?.message ?? e` instead of the raw error object. `npx eslint src/stores/booking.store.js` clean.
- **AC3**: New migration `V98__player_radar_composites_distinct_coach_count.sql` adds
  `distinct_coach_count INTEGER NOT NULL DEFAULT 0` to `development.player_radar_composites` and
  backfills it from `radar_assessment_entries` (`COUNT(DISTINCT coach_id)` grouped by
  `player_id, skill_code`) in the same migration — no visible regression for existing players. Added
  `RadarAssessmentRepository.findDistinctCoachCountsByPlayerAndSkills`, wired through
  `RadarCompositeCalculationService.onRadarEntrySubmitted` → `PlayerRadarCompositeRepository.upsertComposite`
  (now 5-arg) → `PlayerRadarComposite.distinctCoachCount` → `SkillRadarEntry.distinctCoachCount` →
  both `RadarDisplayService` and `ReportGenerationService` construction call sites →
  `SkillsRadarChart.vue`'s three `confidenceDotFill` call sites, which now read `distinctCoachCount`
  instead of `entryCount`. The stale in-file comment documenting the old bug was rewritten to describe
  the new behavior. `entryCount` itself is untouched — still correctly read by `ReportGenerationService`.
  `RadarCompositeCalculatorTest` extended: every existing `upsertComposite` verification gained a 5th
  argument, every existing test gained a `findDistinctCoachCountsByPlayerAndSkills` stub, and a new test
  (`onRadarEntrySubmitted_distinctCoachCount_reflectsUniqueCoachesNotRowCount`) asserts the distinct-coach
  count tracks the distinct-coach query's result independent of row count. 6/6 green.
- **AC4**: `QuotaService.incrementBandwidthUsedBytes(ownerId, bytes)` added, mirroring
  `decrementStorageBytes`'s shape (guarded `bytes <= 0` no-op, single atomic `UPDATE`, `log.debug`).
  `PlaybackService` gained a `QuotaService` dependency (7th `@RequiredArgsConstructor` field); in
  `authorizePlayback`, after `generatePlaybackUrl(...)` succeeds, it charges the video's `storageBytes`
  to `video.getOwnerId()`'s bandwidth counter, guarded against `null`/non-positive `storageBytes`. This is
  a documented file-size-based approximation, not exact metering (Bunny.net's Stream API has no
  per-video bandwidth field) — charges the owner, not the viewer, matching `storage_used_bytes`'s existing
  convention. Updated `PlaybackRevocationWindowUnitTest`'s direct `new PlaybackService(...)` call (the
  only manual construction site) with a `@Mock QuotaService quotaService` argument — it still passes.
  Added `PlaybackServiceTest` (3 tests: successful playback increments bandwidth by owner id + storage
  bytes; an ineligible video state throws before the playback URL is generated and never increments;
  a `null` `storageBytes` skips the call) and `QuotaServiceTest` (3 tests: zero-bytes no-op,
  negative-bytes no-op, positive-bytes issues the exact atomic UPDATE). 7/7 green combined with the
  updated revocation-window test.
- **AC5**: `deferred-work.md`'s four `[PICKED UP by skillars-deferred-40 ACx]` tags (sibling-timeout item,
  console.warn item, DEF2, Def11) replaced with `[CLOSED by skillars-deferred-40 ACx]`. D6/D7's existing
  `[STALE — ...]` annotations confirmed accurate, left as-is. D1's `[STALE — ...]` annotation corrected —
  it previously implied both `QuotaReservationTimeoutService` and `NeglectedSkillDetectionService` were
  equally fixed; reworded to distinguish `QuotaReservationTimeoutService`'s genuine preventive
  `MAX_RUN_DURATION` fix from `NeglectedSkillDetectionService`'s detective-only `log.warn` tripwire, which
  leaves the underlying race structurally possible and remains open.
- No `mvn verify` run per `docs/validation-strategy.md` — all four ACs validated via targeted
  unit tests and `npx eslint`, no cross-cutting/dependency/config changes warranting the full suite.

### File List

- `src/frontend/src/api/booking.api.js` (modified — AC1)
- `src/frontend/src/stores/booking.store.js` (modified — AC2)
- `src/main/resources/db/migration/V98__player_radar_composites_distinct_coach_count.sql` (new — AC3)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarAssessmentRepository.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarCompositeRepository.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerRadarComposite.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/development/contract/SkillRadarEntry.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/development/service/RadarDisplayService.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
  (modified — AC3)
- `src/frontend/src/components/development/SkillsRadarChart.vue` (modified — AC3)
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java`
  (modified — AC3)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java` (modified — AC4)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (modified — AC4)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceTest.java` (new — AC4)
- `src/test/java/com/softropic/skillars/platform/video/service/QuotaServiceTest.java` (new — AC4)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java`
  (modified — AC4, constructor-arity fix)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — AC5)

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: bundled 4-item story per explicit instruction not to create another small story. Re-read `deferred-work.md` end to end (1583 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1/AC2 are same-day follow-ups from `skillars-deferred-39`'s own story-review and code-review (sibling-call timeout hardening; error-object log redaction). AC3/AC4 are two `[AUDIT 2026-08-04: STILL OPEN]`-tagged items re-verified still genuinely open today (radar composite `entry_count`/distinct-coach semantic mismatch; video `bandwidth_used_bytes` never incremented) — both received real design decisions during story creation (AC3: new additive `distinct_coach_count` column, not a rename of `entryCount`; AC4: file-size-based approximation charged to the video owner, after researching and ruling out exact per-request metering via Bunny.net's Stream API, which has no bandwidth field in its per-video statistics response). AC5 additionally closes 3 stale ledger items (obsolete/already-fixed) found as a by-product of the full re-read. |
| 2026-08-19 | Story revised per `story-review.md` senior-dev review (7 findings, all applied). AC1: fixed a defect where the literal instruction ("add `{ timeout: 20000 }` as a second argument") would have silently placed the config object in the request-body slot for the `put`/`acceptAllBatch` `post` calls — now specifies an explicit `undefined` data argument; also fixed a drifted line citation (`:62` → `:65`) for `acceptAllBatch`. AC2: reworded the rationale — this codebase uses cookie-based auth, not `Authorization` headers, so the original "prevents an Authorization leak" framing was inaccurate; reworded to the general "avoid logging full Axios internals" justification. AC3/Task 3.7: added scope to update or remove a now-stale in-file comment (`SkillsRadarChart.vue:155-156`) that documents the exact bug this AC fixes. AC4/Task 4.2: added a previously-unlisted compile-breaking gap — `PlaybackRevocationWindowUnitTest.java:53` directly constructs `PlaybackService` with the current 6-arg constructor and must be updated once `QuotaService` becomes a 7th `@RequiredArgsConstructor` field. AC5/Task 5: corrected wording — `deferred-work.md` already carries `[PICKED UP by skillars-deferred-40 ACx]` tags and complete `[STALE ...]` annotation text from this story's own creation commit; Task 5.1-5.4 now say to replace the existing tag rather than add a fresh one, and 5.5/5.6 say to confirm (D6/D7) or correct (D1) rather than redo. D1's annotation text itself was corrected to distinguish `QuotaReservationTimeoutService` (genuine preventive fix) from `NeglectedSkillDetectionService` (a detective tripwire only, not preventive) rather than closing both under one "already fixed" claim. Fixed a wrong section citation for D6/D7 (they live under the `skillars-7-2-session-payment-lifecycle-credit-wallet` heading, not a second `skillars-deferred-4` section) and a wrong package path for `SessionPackExhaustedEvent.java` (`platform/booking/contract`, not `platform/payment/contract`). |
| 2026-08-19 | Dev-story implementation complete, all 5 ACs done. AC1: `acceptBooking`/`declineBooking`/`acceptAllBatch` timeout-hardened with `{ timeout: 20000 }` via an explicit `undefined` data argument; confirmed by inspection their call sites need no other change. AC2: `booking.store.js`'s superseded-call `console.warn` now logs `e?.message ?? e`. AC3: new migration `V98__player_radar_composites_distinct_coach_count.sql` (additive column + same-migration backfill), new `distinctCoachCount` plumbed end-to-end from a new `RadarAssessmentRepository` query through `RadarCompositeCalculationService`, `PlayerRadarCompositeRepository.upsertComposite` (now 5-arg), `PlayerRadarComposite`, `SkillRadarEntry`, both `RadarDisplayService`/`ReportGenerationService` construction sites, to `SkillsRadarChart.vue`'s three `confidenceDotFill` call sites; `RadarCompositeCalculatorTest` extended (6/6 green, including a new test proving the dot now reflects distinct coaches, not row count). AC4: `QuotaService.incrementBandwidthUsedBytes` added; `PlaybackService.authorizePlayback` now charges the video owner's bandwidth counter by `storageBytes` after a successful playback URL is generated, guarded against `null`/non-positive bytes; `PlaybackRevocationWindowUnitTest`'s direct constructor call updated for the new 7th field; new `PlaybackServiceTest` (3 tests) and `QuotaServiceTest` (3 tests) added, all green. AC5: 4 ledger items closed (`[PICKED UP]` → `[CLOSED]`), D6/D7 confirmed accurate, D1 corrected to distinguish the genuinely-fixed `QuotaReservationTimeoutService` half from the still-open `NeglectedSkillDetectionService` half. `npx eslint` clean on all touched frontend files; targeted `mvn` test runs green; `mvn verify` not run per `docs/validation-strategy.md`. Status → review. |
| 2026-08-20 | Code review (`bmad-code-review`): Blind Hunter (12 raw findings, no project context) + Edge Case Hunter (1 finding, full path analysis) + Acceptance Auditor (0 AC violations — implementation verified to match AC1–AC5 and the Dev Agent Record's completion claims exactly, including independent re-verification of `RadarAssessmentRepository.java` and `PlaybackService.java`/`QuotaService.java` against the live repo). After deduplication and independent re-verification of every Blind Hunter finding against the live code (not taken on the layer's word — no project context means it can be wrong), 8 of 12 were dismissed as false positives (incl. a claimed 2-arg `authorizePlayback` compile error that's actually a pre-existing legitimate overload; a claimed vacuous Mockito matcher that's a standard zero-interaction idiom; a claimed new "no-row-count-check" defect that's a faithful, spec-mandated mirror of the pre-existing `decrementStorageBytes` shape). 1 decision-needed finding (repeat-`authorizePlayback` bandwidth overcounting with no dedup) presented to the user, who chose to defer it with an explicit design-review mandate (spec-intent check, anti-gaming resistance, tradeoff comparison per candidate dedup rule) since `bandwidth_used_bytes` feeds an enforced quota, not just a reporting number — logged to `deferred-work.md`. 2 patches applied: (1) `PlaybackService.java:111-118` — `incrementBandwidthUsedBytes` wrapped in its own `try`/`catch (Exception ex)` with a `log.warn`, so a transient bandwidth-tracking failure can no longer abort the whole `@Transactional authorizePlayback` and deny an otherwise-legitimate playback after the provider's signed URL was already issued; (2) `booking.store.js:366` — `e?.message ?? e` changed to `e?.message || e` so a falsy-but-defined empty-string `.message` no longer logs an empty string. Both verified: targeted `mvn -o test -Dtest=QuotaServiceTest,PlaybackServiceTest,PlaybackRevocationWindowUnitTest` 7/7 green; `npx eslint src/stores/booking.store.js` clean. 2 further findings deferred as pre-existing, marginally-widened-not-introduced risks (DEF3 concurrent-recalculation race; `Def10`-shaped unbatched migration backfill). Status → done. |
