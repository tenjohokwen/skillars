# Story Deferred-60: Availability-Window Coach-Id Guard & Deferred-Work Ledger Verification Sweep

Status: review

## Story

As an engineer operating this platform,
I want `BookingService.isSlotWithinAvailabilityWindow`'s diagnostic WARN to take the coach id as an
explicit parameter instead of inferring it from `windows.get(0)`,
so that the log line stays correct even if a future caller ever passes a mixed-coach window list, and
`deferred-work.md` to accurately reflect the roughly a dozen items in it that turned out, on live
re-verification, to already be fixed by earlier stories without ever being annotated closed.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1836 lines at the time this story was
created, at commit `a995f5d`, the tip of `master` immediately after `skillars-deferred-59` merged) was
re-mined end to end, reading every line rather than relying on section headers — the same full-file
discipline `skillars-deferred-59` used, continued here because its own finding still holds: the pool of
genuinely open, decision-light, low-risk items in the *older* sections is thin (most either carry their
own "pre-existing, accepted, out of scope" reasoning already recorded by prior audits, or need a
product/architecture decision this fast-clearing series deliberately does not make). What this pass
found instead, reading the *recent* tail closely, is a different and equally real class of ledger noise:
**items tagged `[PICKED UP by skillars-deferred-NN AC-x]`, where story NN has since shipped, but the
original ledger bullet was never converted to a `[CLOSED by ...]` annotation** — the exact
"unannotated fix" pattern this file's own audit history has flagged more than a dozen times before
(`skillars-deferred-16`, `-34`, `-40`, `-41`, `-43`, `-44`, `-45`, `-52`, `-56` all independently
rediscovered instances of it).

The user explicitly asked for a **larger** bundle than the ~5-item average recent stories have shipped.
Live re-verification against the current tree (every claim below was checked by reading the actual
source file or running a real grep, not trusted from the ledger's own text) found **fourteen** such
already-fixed-but-unannotated bullets across four unrelated areas, plus **one** genuinely still-open,
small, decision-light item worth an actual code change. Closing the fourteen is pure ledger-accuracy
work — no source file changes, because the fix already shipped under a different, unannotated commit —
and is done directly in this story's own creation pass, exactly as `skillars-deferred-43`/`-44`/`-45`/`-56`
each did for their own smaller STALE findings. The fifteenth is this story's one real Acceptance
Criterion.

**Fourteen items re-verified STALE (already fixed, ledger not updated) and closed by this story's own
creation pass — no dev-story code change needed for any of these:**

- `## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)` D2
  (duplicate expiry query methods on `SessionPackPurchaseRepository`, tagged `[PICKED UP by
  skillars-deferred-42 AC2]`) — **STALE, already closed.** `grep -rn
  "findByCoachIdAndExpiresAtBetween" src/main/java src/test/java` returns zero hits; only
  `findExpiringWithinWindowAndSessionsRemaining` remains, in both the repository and its one caller
  (`SessionPackExpiryNotifier.java:57`). The coach-scoped duplicate was deleted.
- `## Deferred from: code review of skillars-2-4-contact-detail-sanitization-ux (2026-06-13)` (duplicate
  i18n key `auth.coach.bioSanitizationWarning`, tagged `[PICKED UP by skillars-deferred-42 AC3]`) —
  **STALE, already closed.** `grep -rn "bioSanitizationWarning" src/frontend/src/i18n/` returns zero
  hits in any of the three locale bundles; only the surviving `contactDetailWarning` key remains,
  confirmed present in `en-US`, `de-DE` and `fr-FR`.
- `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group B
  (2026-06-11)` D8 (`SecureRandom` re-instantiated per `generateOtp()` call, tagged `[PICKED UP by
  skillars-deferred-42 AC1]`) — **STALE, already closed.**
  `CoachRegistrationService.java:56` declares `private static final SecureRandom SECURE_RANDOM = new
  SecureRandom();` as a field, and `generateOtp()` (`:214-217`) reads it, not `new SecureRandom()` —
  one instance for the class's lifetime, not one per call.
- `## Deferred from: code review of skillars-6-6-player-video-management-portal (2026-06-24)` W7
  (`@Observed(name = "video.approvals")` at class level on `VideoApprovalResource`, losing per-method
  granularity, tagged `[PICKED UP by skillars-deferred-44 AC1]`) — **STALE, already closed.**
  `VideoApprovalResource.java:26` still carries the class-level annotation, but three per-method
  `@Observed` annotations now sit alongside it: `:40` (`video.approvals.list`), `:65`
  (`video.approvals.approve`), `:73` (`video.approvals.reject`) — the exact per-operation granularity
  the item asked for, added rather than substituted.
- `## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)` D5 /
  its code-review duplicate D18 (`DisputeService`'s payment lookups unguarded on status, a bare
  `findById`, tagged `[PICKED UP by skillars-deferred-41 AC1]` at both locations) — **STALE, already
  closed.** `DisputeService.java:133-134` (`getAdminDisputeDetail`) and `:169-170` (`resolveDispute`)
  both read `bookingPaymentRepository.findById(...).filter(bp -> "CAPTURED".equals(bp.getStatus()))` —
  the `CAPTURED`-only filter the item asked for, verified byte-identical to `RevenueReportingService`'s
  own pre-existing pattern the item named as the precedent to mirror.
- `## Deferred from: code review of skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes
  (2026-08-14)` (a fully-misconfigured feature gate produces only a WARN, no metric/alert, tagged
  `[PICKED UP by skillars-deferred-41 AC2]`) — **STALE, already closed.** Both
  `DrillLibraryService.java` and `DrillUploadService.java` now import `io.micrometer.core.instrument.Counter`,
  inject a `MeterRegistry`, and call `Counter.builder(FEATURE_GATE_FULLY_DISABLED)...register(meterRegistry)`
  at their respective gate checks — a real metric now exists where only a log line did before.
- `## Deferred from: code review of skillars-uat-4-i18n-locale-and-message-resolution-integrity
  (2026-08-12)` D2 (hardcoded English day-name/weekday display arrays in `WeeklyCalendar.vue`,
  `AvailabilityManagerPage.vue` and `CoachCommandCenterPage.vue`, tagged `[PICKED UP by
  skillars-deferred-41 AC4]`) — **STALE, already closed for two of the three files named, and the
  third's residual is the ledger's own already-correctly-open D3, not this item.**
  `AvailabilityManagerPage.vue`'s `dayOptions` (`:225-233`) and `CoachCommandCenterPage.vue`'s
  `dayLabel()` (`:277-282`) both now build their labels via
  `new Intl.DateTimeFormat(locale.value, { weekday: ... }).format(d)` against a fixed reference Monday,
  not a literal array. `WeeklyCalendar.vue`'s `weekDays` computed (`:93-108`) does the same. The one
  remaining hardcoded-`'en'` array is `CoachCommandCenterPage.vue`'s `getDayIndex()` (`:268-274`) —
  already covered by this same section's own D3, still correctly open, and explicitly commented in
  place as deliberate (the matching array must stay in lock-step with the hardcoded formatter it
  parses, per D3's own reasoning). D2 itself, as filed, is closed.
- `## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)` W1 (concurrent
  fetch race between `applyFilters` and `onTabChange` in `DrillLibraryPage.vue`/`session.store.js`,
  tagged `[PICKED UP by skillars-deferred-45 AC2]`) together with `## Deferred from: code review of
  skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing (2026-08-20)`
  (the follow-up noting `fetchDrills`/`searchDrills` hand-copy the same 3-point guard instead of sharing
  a helper, tagged `[PICKED UP by skillars-deferred-46 AC2]`) — **both STALE, both closed by the same
  fix.** `session.store.js:22-41` now has a single `runSequencedDrillsRequest(apiCall)` helper (a local
  `drillsRequestSequence` counter, checked after the `await` and in the `finally`, with a
  superseded-failure `console.warn`), and `fetchDrills`/`searchDrills` (`:44-46`, `:60-62`) both call
  through it instead of each carrying its own copy — closing the original race *and* the later
  duplication follow-up in one shipped change.
- `## Deferred from: code review of skillars-uat-5-player-self-booking (2026-08-12)` D1 (duplicated,
  uncached self-profile fetch across `CoachPublicProfilePage.vue` and `BookingRequestPage.vue`, tagged
  `[PICKED UP by skillars-deferred-43 AC2]`) — **STALE, closed by a different mechanism than the one it
  asked for.** Both pages still call `playerStore.fetchSelfPlayerId()` independently (no shared
  composable was added), but `playerStore.js:26-53`'s `fetchSelfPlayerId()` now caches the resolved id
  (`if (selfPlayerId.value !== null) return selfPlayerId.value`) and dedupes any in-flight request via a
  module-scoped promise — added by the `skillars-deferred-45`/`-46` self-player-id work for a different
  reason (cross-account cache-poisoning safety on logout/relogin). The redundant-network-round-trip harm
  this item named no longer exists: a second page calling the same store action after the first either
  gets the cached value instantly or joins the same in-flight promise, never issuing a second HTTP call.
- `## Deferred from: code review of skillars-deferred-47-booking-active-slot-status-config-endpoint-and-frontend-wiring
  (2026-08-20)` (new `GET /api/bookings/requests/config` endpoint has no negative-auth-path IT coverage,
  tagged `[PICKED UP by skillars-deferred-48 AC1]`) — **STALE, already closed.**
  `BookingRequestResourceIT.java:704` has `getConfig_coachRole_returns403`, alongside the pre-existing
  `getConfig_authenticatedParent_returns200WithActiveSlotStatuses` at `:686`.
- The same review's sibling item (`BookingRequestPage.vue`'s config fetch assigns
  `res.activeSlotStatuses` to `ownBlockingStatuses.value` with no shape validation, tagged `[PICKED UP
  by skillars-deferred-48 AC2]`) — **STALE, already closed.** `BookingRequestPage.vue:642-646` now
  guards with `if (Array.isArray(res.activeSlotStatuses) && res.activeSlotStatuses.length > 0)`, falling
  back to the existing default and a `console.warn` on a malformed shape, matching the hardening this
  item asked for (the neighbouring `maxSize` field was not touched, per the item's own narrower framing
  of what needed the guard first).
- `## Deferred from: code review of skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement
  (2026-08-21)` (three related items, each tagged `[PICKED UP by skillars-deferred-50 AC-n]`) — **all
  three STALE, all three closed:**
  - AC2: `RescheduleResourceIT`'s one dedicated `SLOT_OUTSIDE_AVAILABILITY` test only proved the
    midnight-crossing edge case, not "ordinary hours, coach doesn't work this day." **Closed.**
    `RescheduleResourceIT.java:643`
    (`requestReschedule_ordinaryHoursCoachDoesNotWorkThisDay_returns403WithSlotOutsideAvailabilityKey`)
    now exists, using the dedicated `coachProfile2Id`/`coachProfile2BookingId` fixture (`:70-181`) built
    specifically for this scenario, with an explanatory comment citing "Deferred-50 AC2" in place.
  - AC1: `BookingDuplicationService.duplicateNextWeek` had no overlap/double-booking check, unlike
    `RescheduleService.acceptReschedule`'s explicit `findOverlappingBookings` call. **Closed.**
    `BookingDuplicationService.java:91-99` now calls `bookingRepository.findOverlappingBookings(...)`
    and throws `SLOT_UNAVAILABLE` on a hit, mirroring `acceptReschedule`'s shape with an explicit
    "Deferred-50 AC1" comment.
  - AC3: new/updated unit tests stub `isSlotWithinAvailabilityWindow(any(), any(), any())` without
    verifying the actual arguments passed. **Closed.** Both `RescheduleServiceTest.java` (`:103`, `:298`)
    and `BookingDuplicationServiceTest.java` (`:116`) now carry a `verify(bookingService)
    .isSlotWithinAvailabilityWindow(eq(expectedStart), eq(expectedEnd), any())` alongside the looser
    `any()`-stubbed `when(...)` calls used elsewhere in the same files.

None of the fourteen above need a source change in this story — they are annotated `[CLOSED …]` in
`deferred-work.md` directly by this story's creation pass, dated today, with the verification evidence
recorded inline (matching this file's own established convention for a `STALE` finding, e.g.
`skillars-deferred-56`'s treatment of `skillars-deferred-2` D1). The one item below is different: it is
still genuinely open, small, and decision-light, and is this story's actual Acceptance Criterion.

**One item re-verified still open, and small enough for a bundled fast-clearing story:**

- `## Deferred from: code review of skillars-deferred-59-radar-composite-overflow-guard-drill-video-ref-persist-fix-availability-timezone-diagnostics-and-ssh-firewall-rule-hygiene
  (2026-08-24)` (the newest section in the ledger, written by `skillars-deferred-59`'s own code review,
  one commit before this story was created) — `BookingService.isSlotWithinAvailabilityWindow`'s
  all-invalid-timezone summary WARN reads `windows.get(0).getCoachId()`, assuming every element of the
  `windows` list belongs to the same coach, an assumption nothing in the method's signature
  (`List<CoachAvailabilityWindow>`) enforces. Re-verified live at `BookingService.java:827-861`: the
  assumption is exactly as described, and all five current call sites
  (`BookingService.createBookingRequest:221`, `RescheduleService.requestReschedule:116`,
  `RescheduleService.acceptReschedule:230`, `BookingBatchService.createBatch:147-148`,
  `BookingDuplicationService.duplicateNextWeek:79`) do fetch `windows` via
  `coachAvailabilityWindowRepository.findByCoachId(...)`, so a mixed-coach list is genuinely unreachable
  today — the deferred-59 review's own "not exploitable via any current caller" framing holds. Every one
  of those five call sites already has the coach id in scope at the call (as `req.coachId()`,
  `booking.getCoachId()`, or `coach.getId()`), so passing it explicitly rather than inferring it is a
  mechanical, no-behaviour-change, no-decision-needed fix — squarely inside this fast-clearing series'
  bar, unlike the guard-or-document choice a genuinely-reachable mixed-coach caller would force.

## Acceptance Criteria

**AC1 — `BookingService.isSlotWithinAvailabilityWindow` takes `coachId` as an explicit parameter instead
of inferring it from `windows.get(0)`.**

- `isSlotWithinAvailabilityWindow`'s signature (`BookingService.java:827-828`) gains a fourth parameter,
  `UUID coachId`, placed after the existing `List<CoachAvailabilityWindow> windows` parameter.
- The all-invalid-timezone summary WARN (`BookingService.java:855-859`) reads the new `coachId`
  parameter instead of `windows.get(0).getCoachId()`. Behaviour is unchanged for every existing caller —
  same log message, same log level, same fields — only the source of the coach id changes from an
  inferred list element to an explicit argument.
- All five call sites are updated to pass their already-in-scope coach id as the new fourth argument:
  - `BookingService.createBookingRequest` (`:221`, the method's own internal call) — pass `req.coachId()`.
  - `RescheduleService.requestReschedule` (`:116`) — pass `booking.getCoachId()` (the same value already
    used to fetch `windows` two lines above).
  - `RescheduleService.acceptReschedule` (`:230`) — pass `coach.getId()` (the same value already used
    to fetch `windows` two lines above — this method locks a separate variable, `lockedCoach`, not
    `coach` itself, but an entity's id is invariant regardless of which reference reads it).
  - `BookingBatchService.createBatch` (`:147-148`) — pass `req.coachId()` (the same value already used
    to fetch `windows` once for the whole batch, before the per-slot loop).
  - `BookingDuplicationService.duplicateNextWeek` (`:79`) — pass `coach.getId()` (the locked
    `CoachProfile` already in scope from this method's own pessimistic-lock block, added by
    `skillars-deferred-58` AC2).
- No new null-checks or validation are added — every call site's coach id is already a non-null,
  already-validated value by the time it reaches this call (either a `@NotNull` request field or a
  loaded/locked entity's id), matching the method's existing trust boundary.
- Method visibility (package-private) is unchanged.

## Tasks / Subtasks

- [x] Task 1: Add the `UUID coachId` parameter (AC1)
  - [x] 1.1: Change `isSlotWithinAvailabilityWindow`'s signature in `BookingService.java` to accept
        `UUID coachId` as a fourth parameter.
  - [x] 1.2: Change the summary WARN at `BookingService.java:855-859` to log `coachId` instead of
        `windows.get(0).getCoachId()`.
  - [x] 1.3: Update the method's javadoc comment (`:822-826`) to record why the parameter is explicit
        now (mirrors the rationale already written in this story's "Why this story exists" section).
- [x] Task 2: Update all five call sites to pass the coach id (AC1)
  - [x] 2.1: `BookingService.createBookingRequest` — pass `req.coachId()`.
  - [x] 2.2: `RescheduleService.requestReschedule` — pass `booking.getCoachId()`.
  - [x] 2.3: `RescheduleService.acceptReschedule` — pass `coach.getId()`.
  - [x] 2.4: `BookingBatchService.createBatch` — pass `req.coachId()`.
  - [x] 2.5: `BookingDuplicationService.duplicateNextWeek` — pass `coach.getId()`.
- [x] Task 3: Update every test that calls or mocks `isSlotWithinAvailabilityWindow` for the new arity
  - [x] 3.1: `BookingServiceTest` — the three tests that call the real method directly
        (`isSlotWithinAvailabilityWindow_everyWindowHasInvalidTimezone_logsDistinctSummaryWarn`,
        `..._emptyWindowList_doesNotLogSummaryWarn`, `..._mixedValidAndInvalidTimezoneWindows_doesNotLogSummaryWarn`,
        around `:331-409`) need a fourth argument added to each call — use the existing `COACH_ID`
        constant already in scope in this test class. The first test's assertion that the WARN message
        contains `COACH_ID.toString()` continues to hold; the mechanism it's asserting against changes
        (explicit parameter, not `windows.get(0)`) but the observable behaviour does not.
  - [x] 3.2: `RescheduleServiceTest`, `BookingDuplicationServiceTest`, `BookingBatchServiceTest` — these
        mock `bookingService.isSlotWithinAvailabilityWindow(...)` and never construct a real
        `BookingService`, so every `when(...)`/`verify(...)` call needs a fourth `any()` matcher added
        (Mockito's `any()`/`eq()` argument-matcher count must match the mocked method's real arity, or
        the stub/verification silently fails to match). This is `any(), any(), any()` →
        `any(), any(), any(), any())` for the loose stubs, and `eq(x), eq(y), any())` →
        `eq(x), eq(y), any(), any())` for the two tests that verify the start/end arguments precisely
        (`RescheduleServiceTest:103,298`, `BookingDuplicationServiceTest:116`) — do not add an `eq(...)`
        matcher for the new coach-id argument in those two verifications; the existing tests were not
        written to assert on it and widening their scope is not part of this AC.
  - [x] 3.3: Run `mvn -o test -Dtest=BookingServiceTest,RescheduleServiceTest,BookingDuplicationServiceTest,BookingBatchServiceTest`
        and confirm all green before moving on — this is a compile-breaking signature change touched by
        four test classes, and a missed call site fails the build, not a single test.
        **Result: 85/85 green (18 RescheduleServiceTest, 33 BookingServiceTest, 26
        BookingBatchServiceTest, 8 BookingDuplicationServiceTest), 0 failures/errors.**
- [x] Task 4: Full verification
  - [x] 4.1: Per `docs/validation-strategy.md` (loaded as a persistent fact for this workflow), `mvn
        verify` is not run locally by default — the targeted suite above is this change's full blast
        radius (4 test classes, all touched call sites), so it stands as the validation gate; full-suite
        verification runs on GitHub CI once pushed.
  - [x] 4.2: Confirmed no other caller of `isSlotWithinAvailabilityWindow` exists beyond the five listed
        — `grep -rn "isSlotWithinAvailabilityWindow" src/main/java src/test/java` before implementation
        showed exactly 5 real call sites (1 declaration + 5 invocations across
        BookingService/RescheduleService×2/BookingBatchService/BookingDuplicationService); the same grep
        after implementation shows the identical 5 call sites, now each with the fourth argument, no
        sixth caller introduced or discovered.

### Review Findings

_(Populated by code-review / story-review passes after implementation.)_

## Dev Notes

**Scope discipline.** This is a one-line-of-reasoning, mechanical signature change — do not use this
story as an opportunity to also add coachId validation, restructure the method's loop, or otherwise
"improve while you're in there." The deferred-59 review's own framing was explicit: this is "not urgent
given the unreachability, but worth a guard if a future caller ever merges windows across coaches" — the
fix earns its place in a fast-clearing bundle precisely because it costs one parameter and five call-site
edits, nothing more.

**Why an explicit parameter and not a `windows.isEmpty()`-safe alternative kept inside the method.** Two
alternatives were considered and rejected during story creation:
- Guarding `windows.get(0)` with a null/empty check and falling back to `"unknown"` in the log — this
  keeps the actual defect (the assumption that all elements share one coach) and only prevents an
  `IndexOutOfBoundsException` that cannot occur anyway (the surrounding `if (!windows.isEmpty() &&
  validWindowsEvaluated == 0)` guard already prevents that). It would also still print a wrong coach id
  the moment a mixed-coach list is ever passed, silently.
- Deriving the coach id from the first *valid* window found in the loop instead of `windows.get(0)` —
  still infers rather than asserts, and still prints the wrong id for a genuinely mixed-coach list; it
  only changes which element of the wrong assumption gets read.

An explicit parameter is the only option that actually removes the assumption rather than relocating it,
and it costs nothing extra since every caller already has the value.

**Test file line numbers will drift.** The line numbers cited above (e.g. `BookingServiceTest.java:331-409`)
are accurate as of this story's creation at commit `a995f5d`. Locate the three tests by method name
(`isSlotWithinAvailabilityWindow_everyWindowHasInvalidTimezone_logsDistinctSummaryWarn` and its two
siblings) rather than trusting the line numbers if the file has moved by implementation time.

**Mockito arity note (Task 3.2).** `any()` matchers in a `when(...)` stub are positional and must equal
the real method's argument count exactly — Mockito does not pad short matcher lists. Missing the fourth
`any()` on any of the 19 mock call sites listed in Task 3.2 does not fail loudly at compile time (the
call still compiles against the four-arg method as long as the matcher count is wrong at the *Mockito*
level, not the Java level) — it fails at runtime with `InvalidUseOfMatchersException` ("3 matchers
expected, 4 recorded") the moment the suite runs. Rely on Task 3.3's full test run, not just a successful
`mvn compile`, to catch a missed site.

### Project Structure Notes

No new files. Four existing service classes touched (`BookingService.java`, `RescheduleService.java`,
`BookingBatchService.java`, `BookingDuplicationService.java`) plus four existing test classes
(`BookingServiceTest.java`, `RescheduleServiceTest.java`, `BookingDuplicationServiceTest.java`,
`BookingBatchServiceTest.java`). No migration, no new dependency, no i18n key.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — the fifteen items this story's creation
  pass verified (one carried forward as this story's AC1; fourteen closed). This story's creation
  commit (`1e77d9d`) tagged all fourteen `[STALE — verified against current code by
  skillars-deferred-60 story creation, ...]` in place, matching the format this file already uses for
  `skillars-deferred-56`'s equivalent closures. A subsequent pruning pass on that same file (this
  branch, later commit) then deleted all fourteen bullets outright, per the file's own stated "items
  are deleted outright once closed" convention — their original text is preserved both in that pruning
  commit's git history and inline in this story's own "Why this story exists" section above, so nothing
  is lost. See that file's own `## Deferred from: code review of skillars-deferred-59-...` section for
  the still-open AC1 item's original text.
- `skillars-deferred-59-radar-composite-overflow-guard-...md` — the immediately-prior story in this
  series; this story's AC1 is its own code review's one open finding.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

None — no failing test or build error was encountered during implementation. The targeted suite passed
on first run after all five call sites and 19 mock call sites plus 3 direct-call sites were updated
together (a compile-breaking signature change with no valid intermediate state, so there was nothing to
iterate against).

### Completion Notes List

- AC1 implemented exactly as specified: `isSlotWithinAvailabilityWindow` gained a fourth `UUID coachId`
  parameter; the all-invalid-timezone summary WARN now reads it instead of `windows.get(0).getCoachId()`;
  javadoc updated with the Deferred-60 rationale.
- All five call sites updated to pass their already-in-scope coach id, exactly as the story specified —
  including `RescheduleService.acceptReschedule`'s corrected rationale (passes `coach.getId()`, the same
  value used to fetch `windows` two lines above; the method locks a separate `lockedCoach` variable, not
  `coach` itself, per story-review Finding 1 — verified in source before writing the call site).
- All 19 Mockito mock call sites (`RescheduleServiceTest`: 10, `BookingDuplicationServiceTest`: 5,
  `BookingBatchServiceTest`: 4) and 3 direct-call sites (`BookingServiceTest`) updated for the new arity.
  The two precise `verify(...).isSlotWithinAvailabilityWindow(eq(x), eq(y), any())` sites
  (`RescheduleServiceTest:103,298`, `BookingDuplicationServiceTest:116`) got a bare trailing `any()` for
  the new argument, not an `eq(...)`, per the story's explicit instruction not to widen their assertion
  scope.
- No new null-checks, no method-visibility change, no source files beyond the eight listed touched — the
  "scope discipline" Dev Note was followed exactly, no incidental cleanup added.
- Targeted suite: `mvn -o test -Dtest=BookingServiceTest,RescheduleServiceTest,BookingDuplicationServiceTest,BookingBatchServiceTest`
  — 85/85 green, 0 failures/errors. `mvn verify` intentionally not run locally per
  `docs/validation-strategy.md`; full suite runs on GitHub CI post-push.
- Confirmed via `grep -rn "isSlotWithinAvailabilityWindow" src/main/java src/test/java` before and after:
  exactly 5 call sites both times, no sixth caller introduced or missed.

### File List

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/RescheduleServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-24 | Story created via story-creation process. Full re-mine of `deferred-work.md` (1836 lines, at commit `a995f5d`) found fourteen items tagged `[PICKED UP by skillars-deferred-NN ...]` for a story that has since shipped, each re-verified live against current source and found already fixed, unannotated — closed directly in this pass, no dev-story work needed. One item (from `skillars-deferred-59`'s own code review, the newest section in the ledger) re-verified still genuinely open and small enough for this series' bar, carried forward as AC1: `BookingService.isSlotWithinAvailabilityWindow` takes `coachId` explicitly instead of inferring it from `windows.get(0)`. |
| 2026-08-24 | Story-review adjustments applied (4 low-severity cosmetic findings fixed: `acceptReschedule`'s locked-variable rationale, a 15→19 mock-call-site count, a "four"→"three" locale-bundle count, and the ledger-annotation tag-format description). |
| 2026-08-24 | `deferred-work.md` pruned in a follow-up commit on this branch (unrelated ledger-hygiene pass, not part of this story's own scope) — the fourteen STALE closures this story's creation pass tagged were deleted outright per the file's own convention; noted here only because the story's References section describes it. |
| 2026-08-24 | Dev-story implementation complete. AC1 shipped: `isSlotWithinAvailabilityWindow` takes `coachId` explicitly; all 5 call sites and 22 test call sites updated; targeted suite 85/85 green. Status → review. |
