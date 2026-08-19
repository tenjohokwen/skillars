# Story Deferred-40: Coach-Action Timeout Hardening, Radar Confidence-Indicator Accuracy & Video Bandwidth Tracking

Status: ready-for-dev

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
  audited, found stale today.** Both named services now have explicit runtime guards addressing exactly
  this concern: `QuotaReservationTimeoutService.java:23` (`MAX_RUN_DURATION`, an 8-minute self-terminating
  drain deadline under the 10-minute `lockAtMostFor`) and `NeglectedSkillDetectionService.java:25-28,81-82`
  (an 80%-of-budget `log.warn` tripwire). Re-verified by reading both files in full today.

## Acceptance Criteria

1. **AC1 — Timeout-safe `acceptBooking`, `declineBooking`, `acceptAllBatch`.** All three gain the same
   scoped `{ timeout: 20000 }` axios config `skillars-deferred-39` AC1 added to `getCoachBookingRequests()`
   — each on its own call, not the shared `api` instance:
   - `acceptBooking` (`src/frontend/src/api/booking.api.js:23`)
   - `declineBooking` (`src/frontend/src/api/booking.api.js:25`)
   - `acceptAllBatch` (`src/frontend/src/api/booking.api.js:62`)

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
   `e?.message ?? e` instead of the raw `e` object — `error.config` on an Axios error can carry the
   request's headers, including `Authorization`. This is a minimal, scoped fix to the one `console.warn`
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
     this is a clean swap, not a partial one.

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
   - **Explicitly out of scope, documented as considered and rejected:** integrating Bunny's CDN log-push
     for exact per-request metering (a standalone infrastructure project, disproportionate to this story);
     adding a bandwidth **quota enforcement** check (this AC fixes the tracking gap only — the ledger item
     never asked for a new limit, and adding one is a separate product decision).

5. **AC5 — Ledger hygiene.** In `deferred-work.md`:
   - The `story-review of skillars-deferred-39-...` section's sibling-timeout item annotated
     `[CLOSED by skillars-deferred-40 AC1]` with a closure note.
   - The `code review of skillars-deferred-39-...` section's `console.warn` item annotated
     `[CLOSED by skillars-deferred-40 AC2]` with a closure note.
   - DEF2 (`## Deferred from: code review of skillars-5-3-...`) annotated
     `[CLOSED by skillars-deferred-40 AC3]` with a closure note.
   - Def11 (`## Deferred from: code review of skillars-6-1-... Run 2`) annotated
     `[CLOSED by skillars-deferred-40 AC4]` with a closure note.
   - D6 and D7 (`## Deferred from: code review of skillars-deferred-4`, first section) annotated
     `[STALE — verified against current code by skillars-deferred-40 story creation, 2026-08-19: <reason>]`
     matching this file's own established convention for this exact situation (see e.g. the existing D2/D3
     entries in the `skillars-deferred-34`-adjacent section for the annotation shape to copy).
   - D1 (`## Deferred from: code review of skillars-deferred-4`, second section) annotated the same way.

## Tasks / Subtasks

- [ ] Task 1: Timeout-harden the three sibling booking calls (AC: #1)
  - [ ] 1.1 In `src/frontend/src/api/booking.api.js`, add `{ timeout: 20000 }` as a second argument to
    `acceptBooking`, `declineBooking`, and `acceptAllBatch`, matching `getCoachBookingRequests()`'s
    existing comment style (scoped to this call only, 20s choice explained once, not repeated three times
    verbatim — a single shared comment above the three, or one line per export, either is fine as long as
    the "scoped to this call, not the shared instance" rationale is clear).
  - [ ] 1.2 Confirm by inspection (no code change) that `handleAccept`/`handleDecline`/`handleAcceptAll`
    in `CoachBookingRequestsPage.vue` need no change — their existing `try`/`catch`/`finally` shape already
    handles a timeout rejection like any other error.
  - [ ] 1.3 Run `npx eslint src/api/booking.api.js` from `src/frontend` and confirm clean.
- [ ] Task 2: Redact the AC2 console.warn from skillars-deferred-39 (AC: #2)
  - [ ] 2.1 In `booking.store.js`'s `loadCoachBookingRequests()` catch block, change the `console.warn`
    added by `skillars-deferred-39` to log `e?.message ?? e` instead of the raw `e` object. No other
    change to that block.
  - [ ] 2.2 Run `npx eslint src/stores/booking.store.js` from `src/frontend` and confirm clean.
- [ ] Task 3: Radar composite distinct-coach-count (AC: #3)
  - [ ] 3.1 Add a Flyway migration (next `V` number after `V97`) adding
    `development.player_radar_composites.distinct_coach_count INTEGER NOT NULL DEFAULT 0`, then backfilling
    existing rows from `development.radar_assessment_entries` grouped by `(player_id, skill_code)` with
    `COUNT(DISTINCT coach_id)`.
  - [ ] 3.2 Add a new query to `RadarAssessmentRepository` returning `[skill_code, COUNT(DISTINCT
    coach_id)]` rows for a given `playerId`/`parentId`/`skillCodes`, mirroring
    `countDistinctOtherCoachesBySkill`'s join/filter shape but without excluding any coach.
  - [ ] 3.3 In `RadarCompositeCalculationService.onRadarEntrySubmitted`, call the new query, build a
    `skill -> distinctCoachCount` map (default 0 for a skill with no matching row), and pass it as a 5th
    argument to `compositeRepository.upsertComposite(...)`.
  - [ ] 3.4 Update `PlayerRadarCompositeRepository.upsertComposite`'s native query (INSERT column list +
    `ON CONFLICT ... DO UPDATE SET`) to add `distinct_coach_count`, and its method signature to accept the
    new `int distinctCoachCount` param.
  - [ ] 3.5 Add `distinctCoachCount` (`Integer`, column `distinct_coach_count`) to `PlayerRadarComposite`.
  - [ ] 3.6 Add `Integer distinctCoachCount` to the `SkillRadarEntry` record (after `entryCount`, before
    `lastUpdatedAt`), and update both constructor call sites — `RadarDisplayService.java:62-69` and
    `ReportGenerationService.java:228-235` — to pass `comp != null ? comp.getDistinctCoachCount() : null`.
  - [ ] 3.7 In `SkillsRadarChart.vue`, change the three `confidenceDotFill(node.skill.entryCount)` call
    sites to `confidenceDotFill(node.skill.distinctCoachCount)`. Leave `entryCount` itself untouched
    everywhere else — it is not read anywhere else in this file, but is still a valid, differently-scoped
    field on the same DTO for other future consumers.
  - [ ] 3.8 Update `src/test/java/com/softropic/skillars/platform/development/service/
    RadarCompositeCalculatorTest.java`: this file mocks `radarRepository`/`compositeRepository` directly
    against `RadarCompositeCalculationService` (despite its own filename). **Every existing
    `verify(compositeRepository).upsertComposite(...)` call in this file needs a 5th argument added**, and
    **every existing test needs a new `when(radarRepository.findDistinctCoachCountsByPlayerAndSkills(...))
    .thenReturn(...)` stub** (or equivalent) — without it, an unstubbed Mockito mock returns `null` for the
    new query, which will NPE when the service iterates the result. Add at least one new test asserting the
    distinct-coach count is correctly computed when multiple rows share one skill across different coaches
    vs. the same coach.
  - [ ] 3.9 Run the extended `RadarCompositeCalculatorTest` and confirm it passes.
- [ ] Task 4: Video bandwidth tracking on playback (AC: #4)
  - [ ] 4.1 Add `incrementBandwidthUsedBytes(String ownerId, long bytes)` to `QuotaService`, mirroring
    `decrementStorageBytes`'s shape (guarded no-op for `bytes <= 0`, single atomic `UPDATE ... SET
    bandwidth_used_bytes = bandwidth_used_bytes + ?`, `log.debug` on success). Not part of the
    `QuotaProvider` interface.
  - [ ] 4.2 Inject `QuotaService` into `PlaybackService`. In `authorizePlayback`, after
    `videoProviderAdapter.generatePlaybackUrl(...)` succeeds, call `quotaService.incrementBandwidthUsedBytes
    (video.getOwnerId(), video.getStorageBytes())`, guarding a `null`/non-positive `storageBytes`.
  - [ ] 4.3 Add or extend a unit test covering `PlaybackService.authorizePlayback` to assert
    `incrementBandwidthUsedBytes` is called with the video's owner id and storage bytes on a successful
    playback authorization, and is NOT called when authorization fails before the playback URL is
    generated (e.g. an ineligible video state). No existing `PlaybackServiceTest` exists — create one
    following this codebase's Mockito unit-test conventions (see
    `RadarCompositeCalculatorTest.java`/`PlaybackRevocationWindowUnitTest.java` for the house style), or
    add a focused test class scoped to just this behavior if a full `authorizePlayback` test would be
    disproportionate — targeted coverage of the new call, not a full re-test of existing
    `authorizePlayback` behavior.
  - [ ] 4.4 Add a unit test for `QuotaService.incrementBandwidthUsedBytes` covering the `bytes <= 0` no-op
    guard and the successful-increment path (see `QuotaReservationTimeoutServiceTest.java`/
    `NoOpQuotaProviderTest.java` for this module's existing test conventions).
- [ ] Task 5: Ledger hygiene (AC: #5)
  - [ ] 5.1 Close the `story-review of skillars-deferred-39-...` sibling-timeout item
    `[CLOSED by skillars-deferred-40 AC1]`.
  - [ ] 5.2 Close the `code review of skillars-deferred-39-...` `console.warn` item
    `[CLOSED by skillars-deferred-40 AC2]`.
  - [ ] 5.3 Close DEF2 (`code review of skillars-5-3-...`) `[CLOSED by skillars-deferred-40 AC3]`.
  - [ ] 5.4 Close Def11 (`code review of skillars-6-1-... Run 2`) `[CLOSED by skillars-deferred-40 AC4]`.
  - [ ] 5.5 Annotate D6 and D7 (`code review of skillars-deferred-4`, first section) `[STALE — verified
    ... 2026-08-19: ...]` per this story's own findings above.
  - [ ] 5.6 Annotate D1 (`code review of skillars-deferred-4`, second section) the same way.

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
- [Source: `src/frontend/src/api/booking.api.js` lines 23, 25, 29-31, 62 — `acceptBooking`,
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
  review of skillars-deferred-4`, first section) and D1 (second section, same heading) — AC5's stale-item
  corrections]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java`
  lines 19-45 and `src/main/java/com/softropic/skillars/platform/development/service/
  NeglectedSkillDetectionService.java` lines 25-28, 81-82 — verification that D1's concern is already
  addressed by existing `MAX_RUN_DURATION`/tripwire guards]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java` — verification
  that D6's dispute-handling need is met by a different, complete design]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackExhaustedEvent.java`
  — verification that D7 is already fixed (`parentId`, not `playerId`)]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story created via bmad-create-story: bundled 4-item story per explicit instruction not to create another small story. Re-read `deferred-work.md` end to end (1583 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1/AC2 are same-day follow-ups from `skillars-deferred-39`'s own story-review and code-review (sibling-call timeout hardening; error-object log redaction). AC3/AC4 are two `[AUDIT 2026-08-04: STILL OPEN]`-tagged items re-verified still genuinely open today (radar composite `entry_count`/distinct-coach semantic mismatch; video `bandwidth_used_bytes` never incremented) — both received real design decisions during story creation (AC3: new additive `distinct_coach_count` column, not a rename of `entryCount`; AC4: file-size-based approximation charged to the video owner, after researching and ruling out exact per-request metering via Bunny.net's Stream API, which has no bandwidth field in its per-video statistics response). AC5 additionally closes 3 stale ledger items (obsolete/already-fixed) found as a by-product of the full re-read. |
