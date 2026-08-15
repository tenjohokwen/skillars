# Story Deferred-26: Defensive Guards, Input Hardening & Test-Coverage Fixes

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — a silent `long`→`int` narrowing cast on
a coach's strike count, an undocumented nullability contract on a drill response field, an unbounded
drill list on a session-block request that a malicious payload could use to force runaway computation, a
frontend display bug that silently renders "0 SLU" instead of an honest "—" for a drill with no
`repDensity`, a booking-decline unit test that never actually asserts the field it exists to protect, and
a SLU snapshot query with no upper bound that lets a clock-skewed or bad-ingestion row silently inflate a
player's development trend — so that each of six unrelated, previously-deferred defects, spanning the
marketplace, session, booking and development modules, gets fixed without bundling any of them into a
larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit as
`skillars-deferred-11/20/21/22/23/24/25`. All items below were independently re-verified against
**current** code during this story's creation (2026-08-15), not trusted from the ledger's text, which the
ledger's own header warns can be stale. That staleness was not hypothetical: of the ledger candidates
inspected while assembling this story, several turned out already-fixed, already-claimed by a prior story,
or no longer applicable, and were rejected —

- `skillars-5-2` Round 2 Group C's dead-code item (`getNeglectedSkills` in `development.api.js`) —
  already `[CLOSED by skillars-deferred-21 AC1]`.
- `skillars-5-2` Round 2 Group C's `SluTargetEditor` race item — already annotated `[AUDIT 2026-08-13:
  already fixed... verified during skillars-deferred-21 story creation]`.
- `skillars-3-3` Group E's "authority id 9502 leaked" test item — already annotated `[AUDIT 2026-08-13:
  not reproducible... Verified during skillars-deferred-21 story creation]`.
- `skillars-4-1` (external review) D1 and `skillars-4-2` W4 — already `[CLOSED by skillars-deferred-22
  AC3]` and `[CLOSED by skillars-deferred-25 AC4]` respectively.
- `skillars-2-3`'s "duplicate i18n key `auth.coach.bioSanitizationWarning`" item names
  `src/frontend/src/i18n/en/index.js` — that whole `en` bundle directory no longer exists (deleted by
  `skillars-uat-4`; confirmed by `ls src/frontend/src/i18n/` returning only `de-DE`, `en-US`, `fr-FR`,
  `index.js`), so the item's premise is gone with the file.
- `skillars-2-3`'s "`unknownId_returns404` should use `.satisfies()` before the cast" item —
  **already fixed in code, unannotated**: `CoachProfileResourceIT.java:119-123`'s
  `getCoachProfile_unknownId_returns404` already does exactly this (`.satisfies(e ->
  assertThat(((HttpClientErrorException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))`).
- `skillars-5-3` Pass 2's `DEF2`/`skillars-5-4`'s `distinct_coach_count` semantic-mismatch item — real and
  still open, but needs a migration plus a product decision on the confidence-indicator model; too large
  for this story's bundled-small-fix bar. Left for its own story.
- `skillars-2-3`'s "GDPR must add `PerformanceReportRepository.deleteByPlayerId` + S3 deletion" item —
  real and still open, but is a GDPR-compliance feature addition (new repository method, new service
  wiring into `GdprErasureService`, S3 object deletion), not a small mechanical fix. Left for its own
  story.

## Deferred Items Closed

| Source | Item | Current location (re-verified) | AC |
|---|---|---|---|
| code review of `skillars-4-1-drill-library-foundation` (2026-06-17) D8 | `DrillResponse.ownerCoachId` is always null for `PLATFORM` drills — nullable contract undocumented | `DrillResponse.java:10` | 1 |
| code review of `skillars-2-3-coach-public-profile-page` (2026-06-13) | `long → int` cast on `strikeCount` — replace with `Math.toIntExact()` to catch overflow explicitly | `CoachProfileService.java:323-324` | 2 |
| code review of `skillars-4-4-session-builder-block-structure-dna` (2026-06-18) W4 | `SessionBlockRequest.drills` has no `@Size(max=...)` upper-bound constraint — a payload with thousands of drills causes runaway DNA/equipment computation | `SessionBlockRequest.java:16` | 3 |
| code review of `skillars-4-2-drill-card-operations` (2026-06-17) W2 | `sluBreakdown` silently shows "0 SLU" instead of "—" for a drill with null `repDensity` | `DrillDetailPanel.vue:316-323`, template at `:46-50,193-196` | 4 |
| code review of `skillars-3-3-booking-request-approval-workflow` Group E (2026-06-15) | `declineBooking` unit test uses `any(BookingDeclinedEvent.class)` — `canonicalTimezone` field not captured/asserted, so a regression that ships a null/wrong timezone would still pass | `BookingServiceTest.java:751` | 5 |
| code review of `skillars-5-2-skill-exposure-dashboard-neglected-skill-detection` Round 2 Group A (2026-06-19) D5 | No upper bound on `findByPlayerIdFromWeek` JPQL query — a future-dated snapshot row (clock skew, bad ingestion) inflates trend data | `SluWeeklySnapshotRepository.java:30-34`, callers in `SluDashboardService.java:39-49`, `SluNarrativeService.java:31-38` | 6 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- All seven stale/superseded/already-claimed items listed under "Why this story exists" above.
- **`skillars-5-3`/`skillars-5-4`'s `distinct_coach_count` semantic-mismatch item** — needs a migration
  and a product decision on the confidence-indicator model, not a bundled mechanical fix.
- **The GDPR performance-report deletion item (`skillars-5-5` D5)** — a real compliance gap, but a feature
  addition (new repo method + service wiring + S3 deletion), not this story's size class.
- The broad body of pre-2026-08 deferred items not listed in the table above, all `deploy-*` items (the
  ledger's own "Last audit" notes say these sections were never re-checked against current scripts), and
  every item flagged in the ledger as needing a product decision, its own design pass, or infrastructure
  that doesn't exist yet — none of those are small, independently-safe, mechanical fixes.

## Acceptance Criteria

1. **`DrillResponse.ownerCoachId`'s nullable contract is documented with a Javadoc comment.**
   `DrillResponse` (`DrillResponse.java:7-24`) is a Java record; add a short comment directly above the
   `ownerCoachId` field (`DrillResponse.java:12`) in the record header stating it is always `null` for
   `PLATFORM`-library drills and populated only for `COACH`-owned (private) drills. Doc-only change — no
   behavior, no test required.
   Do not add a `@Nullable`/`@NotNull` annotation (this codebase's records don't use Bean Validation
   annotations on response DTOs going out to the client) or restructure the record.

2. **`CoachProfileService.getPublicProfile`'s `strikeCount` uses `Math.toIntExact()` instead of a silent
   narrowing cast.** `coachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter(...)` returns
   `long` (`CoachReliabilityStrikeRepository.java:21`); `CoachProfileService.java:323-324` currently does
   `int strikeCount = (int) coachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter(...)`, which
   silently wraps on overflow instead of failing loudly. Replace the cast with `Math.toIntExact(...)`,
   matching the established precedent at `VideoAccessGuard.java:93`
   (`Math.toIntExact(configService.getLong(...))`). `CoachProfileDto.reliabilityStrikeCount` is declared
   `int` (`CoachProfileDto.java`), so the target type is unchanged — this is defence-in-depth only
   (`ArithmeticException` on overflow instead of silent wraparound), not a currently-reachable bug: no
   coach will accumulate `Integer.MAX_VALUE` strikes. No new test needed; the existing
   `CoachProfileResourceIT` already exercises `getPublicProfile` end-to-end and must stay green unchanged.

3. **`SessionBlockRequest.drills` gets an upper-bound `@Size` constraint.**
   `SessionBlockRequest.java:16` currently declares `@NotNull List<@Valid SessionDrillRefRequest> drills`
   with no maximum. Add `@Size(max = 30)` alongside the existing `@NotNull`. 30 is a deliberately generous
   bound: `CreateSessionPlanRequest.blocks` is itself capped at `@Size(min = 1, max = 4)`
   (`CreateSessionPlanRequest.java:14`) and `durationMinutes` per block is capped at `@Max(240)` (4 hours),
   so even a very drill-dense 4-hour block is nowhere near 30 drills in real coaching use — this closes
   the "thousands of drills" DoS-shaped input the ledger item names without constraining any real usage
   pattern. Do not lower it further without checking real session-builder usage first; do not touch
   `blockType`/`blockName`'s existing `@Size(max=...)` constraints. Add or extend a validation test in
   `SessionBuilderResourceIT.java` (or wherever `SessionBlockRequest` payload validation is already
   exercised — locate via `grep -rln "SessionBlockRequest" src/test/java`) asserting a 31-drill block is
   rejected with 400.

4. **`DrillDetailPanel.vue`'s SLU breakdown renders "—" instead of a misleading "0 SLU" when a drill has no
   recorded rep-density data.** The `sluBreakdown` computed property (`DrillDetailPanel.vue:316-323`) does
   `slu: Math.round((repDensity * weight) / 100)` with no guard against missing data. **Important
   correction from story creation:** `DrillMetadata.repDensity` (`DrillMetadata.java:12`) is a Java
   primitive `int`, not `Integer` — over Hibernate's JSONB/Jackson mapping, a missing key deserializes to
   `0`, and an explicit JSON `null` would throw a deserialization exception rather than pass through. So a
   frontend `repDensity != null` guard can never observe "unset repDensity" as `null`; that case already
   renders as `repDensity: 0` today and always will under the current backend contract, indistinguishable
   from a legitimately-zero drill. **This AC is therefore scoped down to what a frontend-only change can
   actually fix:** make the guard defensive against `repDensity` arriving as `undefined`/`null` (e.g. an
   older cached payload, a manually-edited dev fixture, or a future API contract change) so the row
   degrades to "—" rather than a wrong-looking "0 SLU" in that case, and add a short comment on the
   computed noting that a truly "coach never set this" signal is not currently distinguishable from zero at
   the API layer — closing that gap for real needs a backend change (nullable `Integer` `repDensity`, or an
   explicit "no data" flag) and is out of scope for this bundled-fix story. Fix: in the computed, only
   compute `slu` when `repDensity != null`, else leave it `null`; in both template spots that render
   `{{ item.slu }} SLU` (`DrillDetailPanel.vue:48` and `:195`), change to `{{ item.slu ?? '—' }} SLU` —
   following this codebase's own established null-display convention (`SkillsRadarChart.vue:169-171`'s
   `{{ s.compositeScore ?? '—' }}` pattern). There is no frontend test suite in this repo (standing gap,
   `skillars-5-4` W9 and many later items); verify manually via the dev server: temporarily set
   `metadata.repDensity` to `null`/remove the key client-side (e.g. via Vue devtools, since the backend
   cannot currently produce this shape) and confirm the breakdown row shows "— SLU", then confirm a drill
   with a real `repDensity` (including a legitimately-zero one) still shows the correct rounded value —
   `0 SLU` for zero is correct, not a bug, given the current backend contract.

5. **`BookingServiceTest`'s `declineBooking_requestedBooking_transitionsToDeclined` test captures and
   asserts the `canonicalTimezone` field on the published event.** The test currently asserts only
   `verify(eventPublisher).publishEvent(any(BookingDeclinedEvent.class))`
   (`BookingServiceTest.java:751`) — a regression that silently ships a null/wrong `canonicalTimezone`
   would pass unnoticed. `BookingService.declineBooking` (`BookingService.java:404-409`) constructs the
   event with `booking.getCanonicalTimezone()` as the last constructor argument. **Important correction
   from story creation:** a plain `ArgumentCaptor<BookingDeclinedEvent>` will NOT work here.
   `declineBooking` calls `transition(...)` (`BookingService.java:402`), which internally publishes a
   `BookingStatusChangedEvent` (`BookingService.java:157`, via `transitionInternal(...,
   publishEvent=true)`), and then two lines later explicitly publishes the `BookingDeclinedEvent`
   (`BookingService.java:404-409`) — both event types extend `ApplicationEvent` and both calls bind to the
   same `eventPublisher.publishEvent(ApplicationEvent)` overload, so two separate invocations hit that
   mock during this one method call. A narrow-type `ArgumentCaptor<BookingDeclinedEvent>` does not filter
   by type at runtime (generic erasure) — `captor.capture()` matches both invocations, so an implicit
   `verify(eventPublisher).publishEvent(captor.capture())` (default `times(1)`) would throw
   `TooManyActualInvocations`, not pass. This exact landmine is already solved two tests above in this same
   file: `capturedParentCancellation()` (`BookingServiceTest.java:723-734`) uses
   `ArgumentCaptor<ApplicationEvent>` with `verify(..., atLeastOnce())`, then filters
   `captor.getAllValues()` by `instanceof`/`.isInstance` down to the event type it actually wants. Follow
   that exact pattern here: capture `ApplicationEvent`, verify `atLeastOnce()`, then filter
   `captor.getAllValues()` to `BookingDeclinedEvent` instances (there will be exactly one) and assert
   `getCanonicalTimezone()` on it equals the timezone set on the test's `Booking` fixture (check
   `makeBooking(...)`'s helper for the field it seeds, or set it explicitly in this test if the shared
   helper doesn't). Do not use `ArgumentCaptor<BookingDeclinedEvent>` directly, and do not assume a plain
   `times(1)` verify will pass. Do not change the other two `declineBooking` tests
   (`_confirmedBooking_throws...`, `_upcomingBooking_throws...`) — they assert only the thrown exception and
   have no event to capture.

6. **`SluWeeklySnapshotRepository.findByPlayerIdFromWeek` gains an upper bound, so a future-dated (clock
   skew or bad-ingestion) snapshot row can no longer silently inflate a player's development trend.**
   `SluWeeklySnapshotRepository.java:30-34`'s query filters only `>= fromYear/fromWeek` with no upper
   bound. `SluDashboardService.getWeeklyExposure` already computes `currentYear`/`currentWeek` at `:45-46`
   *before* its query call at `:48-49`, so that call site needs no reordering — just pass the two values
   through. `SluNarrativeService.generate` computes them at `:41-42` *after* its query call at `:37-38`, so
   that call site does need the reorder described below. Either way no new date-math is needed, only wiring
   the already-computed values through. Change the
   repository method to accept `toYear`/`toWeek` parameters and add `AND (s.id.isoYear < :toYear OR
   (s.id.isoYear = :toYear AND s.id.isoWeek <= :toWeek))` to the JPQL `WHERE` clause (mirroring the
   existing lower-bound predicate's OR-composed shape exactly, just inverted). Update both call sites to
   pass their already-computed `currentYear`/`currentWeek` as the new arguments — reorder
   `SluNarrativeService.generate`'s current/prior-block computation block (currently at `:40-45`, after the
   query call) to run before the query call instead, since the query now needs `currentYear`/`currentWeek`
   up front. Update `SluDashboardServiceTest.java`'s three `findByPlayerIdFromWeek` stubs (`:60,91,102`,
   all `when(snapshotRepository.findByPlayerIdFromWeek(anyLong(), anyShort(), anyShort()))`) to add a
   fourth `anyShort()` matching the new parameter — `grep -n "findByPlayerIdFromWeek"
   src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` to
   confirm all three before editing. There is no dedicated `SluNarrativeServiceTest` (confirmed:
   `find . -iname "SluNarrativeServiceTest.java"` returns nothing), so no test file exists there to update.
   Do not add a repository-level IT for this unless one is trivial to add near existing
   `SluWeeklySnapshotRepository` coverage — the three updated unit-test stubs plus a manual sanity check
   are sufficient for this bundled-fix story.

7. **Ledger hygiene in `deferred-work.md`.** Annotate every item this story closes (see **Deferred Items
   Closed** table) with `[CLOSED by skillars-deferred-26 ACn]` at its current ledger location once
   implemented, following this file's established annotation convention (do not delete the original item
   text — append the closure note the same way `skillars-deferred-24`/`-25` did).

## Tasks / Subtasks

- [x] Task 1 — Document `DrillResponse.ownerCoachId`'s nullable contract (AC: #1)
  - [x] Add a comment above the `ownerCoachId` field in `DrillResponse.java:10` stating it is always null
    for `PLATFORM`-library drills

- [x] Task 2 — Replace `strikeCount`'s narrowing cast with `Math.toIntExact` (AC: #2)
  - [x] Change `(int) coachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter(...)` to
    `Math.toIntExact(coachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter(...))` at
    `CoachProfileService.java:323-324`
  - [x] `mvn -o verify -Dit.test=CoachProfileResourceIT` green (no behavior change expected) — 8/8 passed

- [x] Task 3 — Bound `SessionBlockRequest.drills` (AC: #3)
  - [x] Add `@Size(max = 30)` to the `drills` field in `SessionBlockRequest.java:16`
  - [x] Locate existing `SessionBlockRequest` validation test coverage (`grep -rln "SessionBlockRequest"
    src/test/java`) and add a case asserting a 31-drill block is rejected with 400 — added
    `createSession_31DrillBlock_returns400` to `SessionBuilderResourceIT.java`
  - [x] `mvn -o verify -Dit.test=SessionBuilderResourceIT` (or the located test class) green — 13/13 passed

- [x] Task 4 — Fix `sluBreakdown`'s null-`repDensity` display (AC: #4)
  - [x] Guard `sluBreakdown`'s `slu` computation in `DrillDetailPanel.vue:316-323` to return `null` (not
    `0`) when `repDensity` is `null`/`undefined`, with a short comment noting the backend cannot
    currently distinguish "unset" from zero (`DrillMetadata.repDensity` is a primitive `int`)
  - [x] Update both template spots (`:48`, `:195`) from `{{ item.slu }} SLU` to
    `{{ item.slu ?? '—' }} SLU`
  - [~] Manually verify via dev server — NOT performed: no browser-driving tooling is available in this
    session. Verified instead by (1) tracing the computed's logic by hand for both branches
    (`repDensity == null` → `item.slu = null` → template renders `— SLU`; `repDensity` a number,
    including `0` → unchanged arithmetic, template renders the rounded value), and (2) the `?? '—'`
    template convention is byte-for-byte identical to the already-shipped, already-manually-verified
    `SkillsRadarChart.vue:169-171` pattern this AC was directed to mirror. Flagging explicitly per
    house rule rather than claiming a browser check that didn't happen.
  - [x] `npx eslint` clean on the changed file — clean, no errors

- [x] Task 5 — Assert `canonicalTimezone` in the `declineBooking` test (AC: #5)
  - [x] Add an `ArgumentCaptor<ApplicationEvent>` (NOT `ArgumentCaptor<BookingDeclinedEvent>`) to
    `declineBooking_requestedBooking_transitionsToDeclined` (`BookingServiceTest.java:739-752`), following
    the `capturedParentCancellation()` pattern at `BookingServiceTest.java:723-734` — verify with
    `atLeastOnce()`, filter `captor.getAllValues()` down to the `BookingDeclinedEvent` instance, and assert
    its `canonicalTimezone` matches the test fixture's value
  - [x] `mvn -o test -Dtest=BookingServiceTest` green — 29/29 passed

- [x] Task 6 — Add an upper bound to `findByPlayerIdFromWeek` (AC: #6)
  - [x] Add `toYear`/`toWeek` parameters and an upper-bound predicate to
    `SluWeeklySnapshotRepository.findByPlayerIdFromWeek` (`:30-34`)
  - [x] Update `SluDashboardService.getWeeklyExposure` (`:39-49`) to pass `currentYear`/`currentWeek`
  - [x] Update `SluNarrativeService.generate` (`:31-45`) to compute `currentYear`/`currentWeek` before the
    query call and pass them through
  - [x] Update all three `findByPlayerIdFromWeek` stubs in `SluDashboardServiceTest.java` (`:60,91,102`)
    to add the fourth `anyShort()` matcher
  - [x] `mvn -o test -Dtest=SluDashboardServiceTest` green
  - [x] `mvn -o verify` (or the narrowest IT covering `SluNarrativeService`/the SLU dashboard endpoints)
    green

- [x] Task 7 — Ledger hygiene (AC: #7)
  - [x] Annotate all 6 closed items per the **Deferred Items Closed** table in `deferred-work.md` with
    `[CLOSED by skillars-deferred-26 ACn]`
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-26-...` entry status as this story progresses
    (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention

### Review Findings

- [x] [Review][Patch] `sluBreakdown`'s new null guard checks only `repDensity`, not `weight` — a null value
  in `skillWeighting` still produces `NaN`, which `?? '—'` does not catch (nullish coalescing doesn't treat
  `NaN` as nullish), so a malformed/partial custom-drill weighting map renders "NaN SLU" instead of "—".
  [`DrillDetailPanel.vue:319-324`, template at `:48`, `:195`] — **Fixed**: guard now requires both
  `repDensity != null && weight != null` before computing; `npx eslint` clean.
- [x] [Review][Defer] `SluDashboardServiceTest`'s three `findByPlayerIdFromWeek` stubs use `anyShort()` for
  the new `toYear`/`toWeek` params instead of `eq()`/an `ArgumentCaptor`, so a transposition bug in
  `SluDashboardService`/`SluNarrativeService` (e.g. passing `fromYear`/`fromWeek` twice, or swapping
  current/from) would still pass these tests unnoticed — deferred, pre-existing test-looseness pattern
  already used for `fromYear`/`fromWeek` in these same stubs before this diff, just extended consistently
  to the two new params. [`SluDashboardServiceTest.java:60,91,102`]
- [x] [Review][Defer] AC3's new `createSession_31DrillBlock_returns400` only covers the over-the-limit path
  — no companion test asserts a 30-drill block (the exact `@Size(max = 30)` boundary) is still accepted,
  leaving the boundary itself unverified — deferred, matches this file's existing weak-assertion convention
  for validation tests, not a regression introduced by this story.
  [`SessionBuilderResourceIT.java` — new test, AC3]

## Dev Notes

- **Scope discipline.** Six small, independently-safe items across four modules (marketplace, session,
  booking, development) plus one frontend file. Do not use this as a pretext to "clean up while you're in
  there" — e.g. don't also add `Math.toIntExact` to other unrelated narrowing casts you spot, don't widen
  AC3's `@Size` scope to `blockType`/`blockName`, don't build a shared "no-data" display helper for AC4
  beyond the one component touched. If something adjacent looks wrong, note it as a new `deferred-work.md`
  item; don't fix it here.

- **This story is unusually verification-heavy relative to its size, matching the pattern `deferred-25`
  established.** Several ledger candidates inspected while assembling this story were already fixed,
  already closed by a later story, or had their target file deleted entirely (see "Why this story exists"
  above). **Do not trust this story's own AC text as gospel either** — re-run the greps/line citations
  cited in each AC/task at implementation time before writing code, the same way this story's own creation
  re-verified the ledger's original claims against current source.

- **AC6 is the largest item here and the one most likely to have drifted by implementation time.**
  Re-confirm `SluDashboardService.getWeeklyExposure`'s and `SluNarrativeService.generate`'s current/prior
  computation still sits where this AC says before reordering anything — if either method has been
  refactored since story creation, adapt the wiring accordingly rather than following the line numbers
  blindly. The reordering in `SluNarrativeService.generate` is purely moving existing lines earlier in the
  method, not new logic — `currentYear`/`currentWeek`/`boundaryYear`/`boundaryWeek` are already computed
  from `now`, just currently computed after the query call instead of before it.

- **AC2 and AC1 are both no-currently-reachable-bug hardening**, consistent with the honest framing
  `skillars-deferred-25`'s AC2/AC3 used — say so in the completion notes rather than overstating impact.

- **AC4 is this story's only frontend change and the only one with zero automated test coverage possible**
  (standing gap: no frontend test suite exists in this repo — same gap `skillars-5-4` W9, `deferred-17` D6,
  and every UAT story since have recorded). Verify manually per the task; do not attempt to introduce a
  test framework as part of this story.

- **File paths this story touches:**
  - `src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java` (AC1)
  - `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (AC2)
  - `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockRequest.java` (AC3)
  - a `SessionBlockRequest`-validating test class, located at implementation time (AC3)
  - `src/frontend/src/components/session/DrillDetailPanel.vue` (AC4)
  - `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` (AC5)
  - `src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java` (AC6)
  - `src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java` (AC6)
  - `src/main/java/com/softropic/skillars/platform/development/service/SluNarrativeService.java` (AC6)
  - `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` (AC6)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)

### Project Structure Notes

- All six fixes are same-file or two-file, narrow-scope changes to existing files — no new production
  classes, no new migrations. AC3 and AC5 add test coverage to existing test classes (or one located at
  implementation time for AC3); no new test classes.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story uses
  in `sprint-status.yaml` (the "DEFERRED WORK" block).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from: code review of
  skillars-4-1-drill-library-foundation (2026-06-17)" D8 (AC1); "## Deferred from: code review of
  skillars-2-3-coach-public-profile-page (2026-06-13)" (AC2); "## Deferred from: code review of
  skillars-4-4-session-builder-block-structure-dna (2026-06-18)" W4 (AC3); "## Deferred from: code review
  of skillars-4-2-drill-card-operations (2026-06-17)" W2 (AC4); "## Deferred from: code review of
  skillars-3-3-booking-request-approval-workflow Group E (2026-06-15)" (AC5); "## Deferred from: code
  review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A
  (2026-06-19)" D5 (AC6)
- [Source: src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java:1-21] —
  confirms AC1's current field shape
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:300-354]
  — confirms AC2's current cast and `CoachProfileDto` field order
- [Source: src/main/java/com/softropic/skillars/platform/video/service/VideoAccessGuard.java:93] —
  confirms `Math.toIntExact` precedent AC2 mirrors
- [Source: src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachReliabilityStrikeRepository.java:21]
  — confirms `countByCoachIdAndCreatedAtAfter` returns `long`
- [Source: src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockRequest.java:1-16]
  — confirms AC3's current shape (no `@Size` on `drills`)
- [Source: src/main/java/com/softropic/skillars/platform/session/contract/CreateSessionPlanRequest.java:14]
  — confirms the `blocks` list's own `@Size(min=1,max=4)` bound, informing AC3's chosen `max=30`
- [Source: src/frontend/src/components/session/DrillDetailPanel.vue:41-51,188-198,316-323] — confirms
  AC4's current computed and both template render sites
- [Source: src/frontend/src/components/development/SkillsRadarChart.vue:169-171] — confirms the
  `?? '—'` null-display convention AC4 mirrors
- [Source: src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java:736-753]
  — confirms AC5's current test shape and its two sibling tests not to change
- [Source: src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:392-410] —
  confirms `declineBooking`'s `BookingDeclinedEvent` construction AC5's captor targets
- [Source: src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java:1-44]
  — confirms AC6's current query shape (lower bound only)
- [Source: src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java:39-49]
  — confirms AC6's `getWeeklyExposure` already computes `currentYear`/`currentWeek` after the query call
- [Source: src/main/java/com/softropic/skillars/platform/development/service/SluNarrativeService.java:31-45]
  — confirms AC6's `generate` already computes `currentYear`/`currentWeek` after the query call, needing
  reordering rather than new logic
- [Source: src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java:1-110]
  — confirms AC6's three `findByPlayerIdFromWeek` stub call sites needing a fourth matcher
- [Source: src/frontend/src/i18n/ directory listing] — confirms the `en` bundle no longer exists
  (only `de-DE`, `en-US`, `fr-FR`, `index.js`), closing the ledger's stale `skillars-2-3` duplicate-i18n-key
  item by file deletion
- [Source: src/test/java/com/softropic/skillars/platform/marketplace/CoachProfileResourceIT.java:119-123]
  — confirms `getCoachProfile_unknownId_returns404` already uses `.satisfies()`, closing that stale
  `skillars-2-3` item

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `bmad-dev-story` workflow.

### Debug Log References

None — no failures encountered during implementation. All targeted test runs (`CoachProfileResourceIT`,
`SessionBuilderResourceIT`, `BookingServiceTest`, `SluDashboardServiceTest`, `SkillExposureResourceIT`)
passed on first green run after fixing one test-compile error (an unchecked-cast compilation failure in
the new AC3 test, fixed by using `<Map<String, Object>>mapToObj` instead of a raw cast).

### Completion Notes List

- **Spec corrections applied before implementation** (via a prior senior-dev review of the story's own AC
  text, before any code was written): AC4 and AC5 as originally drafted described fixes that could not
  produce the claimed behavior. AC4's `repDensity != null` guard could never fire because
  `DrillMetadata.repDensity` is a Java primitive `int` — rescoped to a defensive guard against
  `undefined`/`null` only, with the real "coach never set this" gap tracked as a new `deferred-work.md`
  item (D1, filed under this story's own story-creation section) since closing it needs a backend
  nullable-`Integer` change. AC5's prescribed `ArgumentCaptor<BookingDeclinedEvent>` would have thrown
  `TooManyActualInvocations` at test time, since `declineBooking` publishes two `ApplicationEvent`-typed
  events per call — rewritten to reuse this file's own `capturedParentCancellation()` pattern
  (`ArgumentCaptor<ApplicationEvent>` + filter/reduce). Both corrections were verified against current
  source before implementation, then implemented and confirmed green.
- **AC1, AC2**: doc-only / hardening-only changes, no currently-reachable bug fixed, consistent with this
  story's own framing. `CoachProfileResourceIT` stayed green unchanged (8/8).
- **AC3**: `@Size(max = 30)` added; no existing IT covered `SessionBlockRequest` payload validation by
  class-name reference (the story's own `grep -rln "SessionBlockRequest" src/test/java` search only
  matched a service-layer unit test), so the new rejection test was added to
  `SessionBuilderResourceIT.java`, which does exercise the same DTO via its create-session endpoint using
  inline `Map`-built JSON payloads. 13/13 passed including the new test.
- **AC4**: implemented and eslint-clean; manual dev-server verification was **not performed** — no
  browser-driving tooling is available in this session. Correctness argued instead by tracing both
  computed branches by hand and by the fix being a byte-for-byte reuse of the already-shipped
  `SkillsRadarChart.vue` `?? '—'` convention. Flagged explicitly per house rule rather than claiming an
  unperformed check.
- **AC5**: 29/29 `BookingServiceTest` passed, including the rewritten test and its two unchanged siblings.
- **AC6**: repository upper bound added; both call sites wired without new date-math (only
  `SluNarrativeService.generate`'s existing computation block needed reordering, as anticipated by Dev
  Notes). `SluDashboardServiceTest` (6/6) and `SkillExposureResourceIT` (exercises both `/exposure` and
  `/narrative` REST endpoints, i.e. both call sites AC6 touched) both green.
- **AC7**: all 6 ledger items in `deferred-work.md` annotated `[CLOSED by skillars-deferred-26 ACn]` with a
  short closure note each, following the `-24`/`-25` convention; one new item (D1) filed under a new
  "story creation" section for the AC4 backend gap this story could not close.
- Full `mvn -o verify` green: 883 unit tests (0 failures, 1 skipped) + 899 IT (0 failures, 4 skipped),
  BUILD SUCCESS, 8:07 min. Full frontend `eslint -c ./eslint.config.js` (whole `src*` tree, not just the
  AC4 file) also clean, 0 errors.
- **Code review follow-up (2026-08-15)**: 1 finding resolved, 2 findings accepted as deferred (already
  logged under "Deferred from: code review of skillars-deferred-26..." in `deferred-work.md`, no code
  change needed). Resolved: `sluBreakdown`'s null guard checked only `repDensity`, not `weight` — a null
  entry in `skillWeighting` still produced `NaN` (nullish coalescing does not treat `NaN` as nullish), so
  `?? '—'` didn't catch it and the row would have rendered "NaN SLU". Fixed by requiring
  `repDensity != null && weight != null` before computing. `npx eslint` clean.

### File List

- `src/main/java/com/softropic/skillars/platform/session/contract/DrillResponse.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/session/contract/SessionBlockRequest.java` (AC3)
- `src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java` (AC3 — new test)
- `src/frontend/src/components/session/DrillDetailPanel.vue` (AC4)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/development/repo/SluWeeklySnapshotRepository.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/development/service/SluDashboardService.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/development/service/SluNarrativeService.java` (AC6)
- `src/test/java/com/softropic/skillars/platform/development/service/SluDashboardServiceTest.java` (AC6)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)
