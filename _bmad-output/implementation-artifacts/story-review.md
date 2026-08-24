# Story Review: skillars-deferred-60

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-60-availability-window-coach-id-guard-and-ledger-verification-sweep.md`

Method: every one of the fifteen factual claims in the story (the one real AC1 code claim, plus the
fourteen STALE-closure claims already applied to `deferred-work.md`) was independently re-verified against
the actual source tree at HEAD with `grep`/`Read` — none trusted from the story text or from the ledger's
own annotation. All five call sites of `isSlotWithinAvailabilityWindow` were read in full context (not just
the cited line) to confirm the named variable is actually the value in scope, not merely a plausible one.
The fourteen `deferred-work.md` STALE annotations were also checked as they exist in the file right now
(they are already applied, part of the story's own creation-pass commit `1e77d9d`), not merely as described
in the story body.

## Findings

### 1. AC1 — `RescheduleService.acceptReschedule`'s call site rationale misidentifies which variable is locked (Low, cosmetic)

AC1's instructions say to pass `coach.getId()` at `RescheduleService.java:230` "(the locked `CoachProfile`
already in scope from this method's pessimistic-lock block)". That's not accurate: the method locks a
*separate* variable, `lockedCoach` (`:208`, `coachProfileRepository.findByIdForUpdate(coach.getId())`,
refreshed with `PESSIMISTIC_WRITE` at `:215`) — `coach` itself is the original, unlocked reference fetched
earlier at `:155` (`coachProfileRepository.findByUserId(coachUserId)`) and is never re-pointed at the locked
entity. The existing windows fetch the AC's own call site sits directly beneath (`:229`,
`coachAvailabilityWindowRepository.findByCoachId(coach.getId())`) already uses the *unlocked* `coach`
reference too, so `coach.getId()` is still the right value to pass — this doesn't change what to implement,
since an entity's id is invariant regardless of which reference (locked or not) reads it. It's worth a
one-line correction in the AC text before handoff, though, both because it misdescribes the method's own
locking structure and because it's inconsistent with how the sibling call site one paragraph up
(`RescheduleService.requestReschedule`) is correctly described ("the same value already used to fetch
windows two lines above") — that phrasing, not the "locked CoachProfile" one, is what actually applies here
too.

By contrast, the AC's identical-sounding claim for `BookingDuplicationService.duplicateNextWeek` (`:79`,
"the locked `CoachProfile` already in scope from this method's own pessimistic-lock block") *is* accurate:
that method calls `entityManager.refresh(coach, LockModeType.PESSIMISTIC_WRITE)` in place (`:65`) on the
same `coach` variable, so `coach` really is the locked entity there. The two call sites read as parallel in
the AC text but are structurally different (refresh-in-place vs. separate locked variable), and only one of
the two descriptions is true of its target.

### 2. Task 3.2 — "~15 mock call sites" undercounts the actual arity-update surface (Low)

The three mock-based test files (`RescheduleServiceTest`, `BookingDuplicationServiceTest`,
`BookingBatchServiceTest`) have 10 + 5 + 4 = **19** real `when(...)`/`verify(...)` call sites against
`isSlotWithinAvailabilityWindow` needing a fourth matcher (one further textual hit in
`BookingBatchServiceTest.java:128` is a comment, not a call, and is correctly not counted). This doesn't
block implementation — Task 3.3's full test run and Task 4.2's before/after grep-count comparison will both
catch any site the "~15" estimate might cause someone to undercount by eye — but the number itself is off by
about a quarter and is worth correcting so it doesn't read as an authoritative checklist total.

### 3. "Why this story exists" — i18n STALE-closure item says "four locale bundles"; only three exist (Low, cosmetic)

The bullet about the duplicate `bioSanitizationWarning` key (and the matching `deferred-work.md` annotation
it produced) says the confirming grep "returns zero hits in any of the four locale bundles." `src/frontend/src/i18n/`
contains exactly three locale directories — `en-US`, `de-DE`, `fr-FR` — plus a barrel `index.js` that only
re-exports the three; there is no fourth bundle. The zero-hits grep result and the "confirmed present in
en-US, de-DE and fr-FR" follow-up sentence are both correct; only the "four" count is wrong. Doesn't affect
the STALE verdict.

### 4. References section describes the ledger annotation format inaccurately (Low, cosmetic)

The story's own References section (bottom of the file) says the fourteen closures get "its new `[CLOSED by
skillars-deferred-60 story creation]` annotation" in `deferred-work.md`. The annotations actually applied
(already present in the file, e.g. lines 637, 862, 928, 1117, 1317/1363, 1369, 1393, 1438, 1639, 1643, 1649,
1650, 1651) all use the format `[STALE — verified against current code by skillars-deferred-60 story
creation, 2026-08-24: ...]`, matching the established convention this same file already uses for
`skillars-deferred-56`'s equivalent closures — not a `[CLOSED by ...]` tag, which this file reserves for
items closed by an AC's own code change in the same story (e.g. `[CLOSED by skillars-deferred-56 AC1 — ...]`
at line 780). The applied annotations are correctly formatted and consistent with precedent; only the
story's self-description of them is wrong.

## Verified as accurate (no finding)

- **AC1 core claim**: `isSlotWithinAvailabilityWindow`'s signature (`BookingService.java:827-828`), the
  all-invalid-timezone WARN (`:855-859`, reading `windows.get(0).getCoachId()`), the javadoc span
  (`:822-826`), and the surrounding `!windows.isEmpty() && validWindowsEvaluated == 0` guard all match the
  story's "current shape" description line-for-line.
- **All five call sites** exist exactly where cited and each really does have the named coach-id value in
  scope at that point: `BookingService.createBookingRequest:221` (`req.coachId()`, confirmed against
  `:220`'s windows fetch), `RescheduleService.requestReschedule:116` (`booking.getCoachId()`, matching
  `:115`'s windows fetch), `RescheduleService.acceptReschedule:230` (`coach.getId()` — correct value,
  mischaracterized rationale, see Finding 1), `BookingBatchService.createBatch:147-148` (`req.coachId()`,
  matching `:126`'s once-per-batch windows fetch), `BookingDuplicationService.duplicateNextWeek:79`
  (`coach.getId()`, and here genuinely the locked entity per Finding 1's contrast). A repo-wide grep confirms
  no sixth caller exists anywhere in `src/main/java` or `src/test/java`.
- **Task 3.1**: `BookingServiceTest`'s three direct-call tests (`:331`, `:363`, `:387`) and the class-level
  `COACH_ID` constant (`:104`) are exactly as described; the first test's assertion on `COACH_ID.toString()`
  in the WARN message is real and would continue to hold under the new mechanism.
- **Task 3.2 mechanics**: the loose `any(), any(), any())` stubs and the two precise
  `eq(x), eq(y), any())` verifications (`RescheduleServiceTest:103,298`, `BookingDuplicationServiceTest:116`)
  are exactly where and how the story describes them (count aside — Finding 2).
- **Ledger closures 1–14**, individually re-verified against current source, all hold as STALE-and-correctly-closed:
  `SessionPackPurchaseRepository` duplicate method deleted; `bioSanitizationWarning` key gone (locale-count
  aside — Finding 3); `SecureRandom` is a static final field; `VideoApprovalResource` carries both
  class-level and all three per-method `@Observed` annotations; `DisputeService`'s two payment lookups both
  carry the `CAPTURED` filter; `DrillLibraryService`/`DrillUploadService` both register the feature-gate
  `Counter`; `AvailabilityManagerPage.vue`/`CoachCommandCenterPage.vue`/`WeeklyCalendar.vue` all use
  `Intl.DateTimeFormat` for day labels, with `CoachCommandCenterPage.vue`'s `getDayIndex()` correctly
  identified as the separate, deliberately-still-open D3 item rather than part of this closure;
  `session.store.js`'s `runSequencedDrillsRequest` helper closes both the original race and the later
  duplication follow-up in the same change; `playerStore.js`'s `fetchSelfPlayerId()` caches and dedupes via
  a module-scoped in-flight promise, closing the redundant-fetch item by a different mechanism than the one
  originally proposed; `BookingRequestResourceIT.getConfig_coachRole_returns403` exists; `BookingRequestPage.vue`'s
  config fetch is now `Array.isArray`-guarded; and all three `skillars-deferred-50` items (ordinary-hours IT
  test, `BookingDuplicationService` overlap check, `verify(...).isSlotWithinAvailabilityWindow(eq(...), eq(...), any())`
  in both `RescheduleServiceTest` and `BookingDuplicationServiceTest`) are shipped. The fourteen
  `deferred-work.md` annotations reflecting these closures are already applied in the file (part of commit
  `1e77d9d`) and are internally consistent with this file's own established STALE-annotation convention
  (format aside — Finding 4).
- **AC1's carried-forward item itself**: the ledger's newest section (`## Deferred from: code review of
  skillars-deferred-59-...`) ends the file with exactly the `windows.get(0).getCoachId()` finding, tagged
  `[PICKED UP by skillars-deferred-60 AC1]`, matching the story's characterization of it as the sole
  genuinely-open item found.
- **No premature implementation**: `BookingService.java`'s method is still 3-arg and package-private as of
  this review; `git status` shows no source files touched, consistent with the story's `ready-for-dev`
  status.

## Summary

All fifteen underlying factual claims survive independent re-verification — the fourteen ledger closures are
real and already correctly applied, and AC1's mechanical fix is scoped, described, and line-numbered
accurately enough to implement as written. Four Low-severity, cosmetic-only findings surfaced, none of which
change what code needs to be written: one mischaracterizes which variable is locked in
`RescheduleService.acceptReschedule` (Finding 1, the only one worth a real second look since it's an
incorrect technical claim about the code's own structure, not just a number), one undercounts a test-file
call-site total that a later task step re-verifies mechanically anyway (Finding 2), and two are minor count/
format inaccuracies in narrative text (Findings 3–4). No missed call sites, no missed corner cases in the
`isSlotWithinAvailabilityWindow` guard logic, and no false STALE closure were found. Recommend fixing
Finding 1's wording before or during implementation (cheap, and prevents propagating a wrong claim about the
method's locking structure into a comment or future story); Findings 2–4 are optional polish. Status can
remain `ready-for-dev`.
