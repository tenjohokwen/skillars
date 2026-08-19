# Story Deferred-41: Dispute Payment-Status Guard, Feature-Gate Misconfiguration Metrics, Session-Pack Coach Filter & Day-Name Localization

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want a payment-status tripwire in `DisputeService` closed by mirroring an already-established guard,
two silent misconfiguration paths made observable via the same metrics pattern this codebase already
uses elsewhere, an in-memory pack filter pushed into SQL, and three hardcoded-English weekday-name
arrays localized using the same technique already proven in this codebase,
so that four independently-real, independently-small, decision-light gaps found by re-mining the full
`deferred-work.md` ledger are closed in one pass instead of each waiting for its own single-item story.

### Why this story exists

This story's creation was explicitly instructed to **bundle several small, unrelated, decision-light
items into one story rather than create another narrow 1-2 AC story** — the pattern every prior
`skillars-deferred-*` pass has followed, most recently `skillars-deferred-40`.

`_bmad-output/implementation-artifacts/deferred-work.md` (1599 lines as of this story's creation) was
re-read end to end, top to bottom, including every section a prior pass's own notes said was "not
re-checked." The file's own protocol (documented near its top) treats `[CLOSED by ...]`,
`[PICKED UP by ...]`, `[STALE — ...]`, `[DISMISSED — ...]`, `[WITHDRAWN — ...]`, `[SUPERSEDED — ...]`,
`[MITIGATED — ...]` and "examined and deliberately left alone" as the markers that make an item
non-actionable; only items carrying none of these (or a `STILL OPEN` audit tag not since picked up) were
treated as candidates. Every candidate below was re-verified against the live code — not trusted from
the ledger's own prose — before being used.

**Four genuinely open, decision-light items were found and are bundled here:**

- **DisputeService's payment-amount lookups are unguarded on payment status** — recorded twice in the
  ledger under the same story: D5 (`## Deferred from: skillars-uat-3-payment-capture-integrity-and-
  backup-retention (2026-08-11)`, the story's own implementation-notes section) and D18 (`## Deferred
  from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)`, the
  code-review section, which explicitly says "Restated from this story's own D5 because the review
  reached it independently"). Re-verified today: `DisputeService.java:133` and `:168` both call
  `bookingPaymentRepository.findById(dispute.getBookingId())` with **no status filter**, unlike
  `RevenueReportingService.java:149-150` and `:181-182`, which both already guard the identical read with
  `.filter(bp -> "CAPTURED".equals(bp.getStatus()))` (added by `skillars-uat-3` AC1, with an explicit
  in-code comment: "Anything non-CAPTURED keeps today's 404."). The ledger item itself explains why this
  is currently safe (`DISPUTED` is unreachable from `PAYMENT_PENDING`/`CAPTURE_PENDING` per
  `BookingStateMachine`) but is recorded twice across this ledger's history as a tripwire
  worth closing rather than re-litigating on trust each time a state-machine change is proposed. Fixing it
  is a direct, mechanical mirror of an already-shipped, already-reviewed pattern in the same codebase —
  no new design decision required. **Candidate for this story (AC1).**

- **Two feature-gate misconfiguration paths log a WARN and nothing else, with no metric.**
  `## Deferred from: code review of skillars-deferred-21-...` (2026-08-14): `ConfigService.getBoolean`
  fails open (returns `false`) for a missing or non-boolean-valued config key guarding a
  security-sensitive gate, "now logged at WARN but not alertable." `## Deferred from: code review of
  skillars-deferred-22-...` (2026-08-14): "A fully-misconfigured feature gate (every tier disabled) now
  produces only a single WARN log line, with no metric or alert." Re-verified today: `ConfigService.java`'s
  `getBoolean(String)` (line 106-113) and its shared `parseBoolean` helper (line 127-131) still only
  `log.warn(...)`; `DrillLibraryService.resolveMinEnabledTier()` (line 246-255) and
  `DrillUploadService.resolveMinUploadTier()` (line 143-152) — the two "every tier disabled" fully-gated
  paths the second item names — still only `log.warn(...)` too. This codebase already has an established,
  directly-mirrorable pattern for exactly this: `PaymentPendingSweeper.java` injects `MeterRegistry`
  directly (not a dedicated `*Metrics` service class) and calls
  `Counter.builder(NAME).tag("reason", reason).register(meterRegistry).increment()` right next to its own
  `log.warn`/`log.info` calls (`booking.payment_pending.unrecoverable`, `:82,186`); `ShedLockConfig.java`
  does the same for `scheduler.lock.skipped`. Both ledger items were previously left open specifically
  because no metric existed yet to add — that gap doesn't exist anymore. **Candidate for this story
  (AC2).**

- **`SessionPackPaymentService.getPacksForParent` filters by `coachId` in Java after loading every pack
  row for the parent, not in SQL.** `## Deferred from: code review of skillars-3-2-session-pack-purchase-
  credit-dashboard (2026-06-13)`, retargeted by a 2026-08-04 audit note onto the current payment-module
  method: "In-memory `coachId` filter when listing a parent's packs — loads all packs for the parent then
  Java-stream filters by coachId; push the filter into SQL when pack volumes grow."
  `[SessionPackPaymentService.java:78-81]`. Re-verified today: `getPacksForParent` (now at lines 82-97)
  still calls `sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(parentId)` unconditionally
  and filters the returned list with `.filter(p -> coachId == null || coachId.equals(p.getCoachId()))`
  at line 84 — exactly as described, unchanged since the audit retargeted it. The repository already uses
  Spring Data derived-query methods for every other single-purpose lookup in the same file
  (`findByParentIdOrderByCreatedAtDesc`, `findByCoachIdAndExpiresAtBetween...`), so the fix is a one-line
  derived-query addition, not a design decision. **Candidate for this story (AC3).**

- **Three hardcoded-English weekday-name display arrays are a distinct, still-open i18n gap.**
  `## Deferred from: skillars-uat-4-i18n-locale-and-message-resolution-integrity (2026-08-12)`, D2:
  "Hardcoded English day-name/weekday display arrays are a distinct, systemic 'missing translation keys'
  bug, not a locale-formatting defect. `WeeklyCalendar.vue`'s `dayNames` array... `AvailabilityManagerPage
  .vue`'s `dayOptions` labels, and `CoachCommandCenterPage.vue`'s `dayLabel` array all render literal
  English regardless of the active vue-i18n locale... Fixing this properly means adding real translation
  keys across at least 3 files and is out of AC1's scope." Re-verified today: all three arrays are
  unchanged — `WeeklyCalendar.vue:95` (`const dayNames = ['Mon', 'Tue', ...]`),
  `AvailabilityManagerPage.vue:225-233` (`const dayOptions = [{ label: 'Monday', value: 1 }, ...]`), and
  `CoachCommandCenterPage.vue:277-279` (`function dayLabel(index) { return ['Mon', 'Tue', ...][index] }`).
  The framing that this "needs real translation keys" turns out to be avoidable: all three files already
  `import { useI18n } from 'vue-i18n'` and already destructure `locale` from it for other display-only
  date formatting in the same file (`WeeklyCalendar.vue:78,101-104`; `AvailabilityManagerPage.vue:170,207`;
  `CoachCommandCenterPage.vue:182,313-317`) — the exact `Intl.DateTimeFormat(locale.value, {...})` pattern
  `skillars-uat-4` AC1 already established repo-wide for this class of bug. Weekday names can be generated
  the same way (no new i18n bundle keys needed at all), turning a "3-file translation-key sweep" into a
  mechanical `Intl.DateTimeFormat(locale.value, { weekday: ... })` substitution. `CoachCommandCenterPage
  .vue:266-274`'s **separate** `getDayIndex` function — which matches a hardcoded `'en'`
  `Intl.DateTimeFormat` result against a hardcoded English array via `.indexOf()` — is explicitly a
  **different** mechanism (day-of-week *matching*, not display) and the ledger's own D3 warns not to touch
  it; re-verified it is untouched by this story's fix. **Candidate for this story (AC4).**

**Three additional items were found to be stale during this research — not story material, but corrected
here as a hygiene by-product (AC5):**

- **Def1 (`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system`,
  2026-06-20), never audited before now — found already fixed.** "`expireStaleReservations()` loop has no
  circuit breaker — sustained high rate of new expired reservations could delay other scheduled work
  indefinitely; no max-iteration or max-time guard." Re-verified today:
  `QuotaReservationTimeoutService.java:23` declares `MAX_RUN_DURATION = Duration.ofMinutes(8)`, and
  `expireStaleReservations()` (lines 29-45) computes a `deadline` from it and checks
  `Instant.now().isBefore(deadline)` in its drain loop's condition, logging a WARN and stopping if the
  budget is exhausted — exactly the "max-iteration or max-time guard" Def1 asks for. This is the same
  `MAX_RUN_DURATION` mechanism `skillars-deferred-40`'s own AC5 correction already cited as "a genuine
  preventive fix" for the sibling `NeglectedSkillDetectionService` comparison, confirming it was added to
  this class by an earlier story (unannotated in this ledger). Superseded by shipped code, not a gap.
- **D2 (`## Deferred from: code review of skillars-deferred-4`, first section, 2026-07-02), never audited
  before now — found already fixed.** "No log or metric is emitted when `@SchedulerLock` skips a run
  because another instance already holds the lock — indistinguishable in production from a job silently
  failing to run due to a bug." Re-verified today: `ShedLockConfig.java`'s `lockProvider` bean wraps the
  delegate `LockProvider` and, when `delegate.lock(...)` returns `Optional.empty()` (ShedLock's own signal
  that another instance holds the lock), calls `log.info(...)` **and**
  `Counter.builder(SCHEDULER_LOCK_SKIPPED).tag("lock_name", lockConfiguration.getName())
  .register(meterRegistry).increment()` — both the log and the metric this item asks for, with an in-code
  comment ("the only signal a @SchedulerLock-annotated run was skipped") describing exactly this defect.
  Superseded by shipped code, not a gap.
- **Def16 (`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system Run 2`,
  2026-06-20), never audited before now — found already fixed.** "`AccountManagementFacade` phone
  registration NullPointerException — pre-existing; `getEmail().toLowerCase()` throws NPE for
  phone-only registrations. [`AccountManagementFacade.java:~231`]" Re-verified today:
  `AccountManagementFacade.java:132-133` and `:231` all already guard the call —
  `userDTO.getEmail() != null ? userDTO.getEmail().toLowerCase() : null` — no unguarded
  `.getEmail().toLowerCase()` call exists anywhere in the file (`grep -c "getEmail().toLowerCase()"`
  returns only the three already-guarded ternary sites). Superseded by shipped code, not a gap.

**Decision made during this story's creation — why these four and not others:** every other candidate
examined either already carries its own "examined and left alone"/"deliberately not fixed"/"needs a
design decision" reasoning on record (e.g. the `jakarta.persistence.lock.timeout`-has-no-effect gap
tracked across four repositories, explicitly deferred pending a Postgres-locking design decision;
`AdminVideoService.deleteVideo`'s quota-release-inside-the-delete-transaction coupling, which trades one
failure mode for a different orphaned-reservation risk and needs the same kind of careful reasoning
`skillars-deferred-40` AC4 gave its own exception-isolation patch, not a mechanical copy; the coach-side
per-booking batch-outcome-reporting gap, explicitly re-filed as its own future item by
`skillars-deferred-31`), needs product input rather than a dev decision (the FIFO-vs-soonest-expiring pack
mismatch, the parent-cancel-after-session-start no-show question), or would itself require the standing,
repeatedly-declined frontend-test-infrastructure investment (`skillars-deferred-35`–`40` have all left this
alone for the same reason, and this pass leaves it alone too). AC1–AC3 are backend fixes across three
unrelated modules (`admin`, `config`/`session`, `payment`); AC4 is a frontend i18n fix unrelated to any of
them — bundled here purely because all four are small, real, decision-light, and this pass was asked to
bundle rather than defer a candidate that clears this bar.

## Acceptance Criteria

1. **AC1 — `DisputeService` guards its payment-status reads the same way `RevenueReportingService`
   already does.** In `DisputeService.java`, both `getAdminDisputeDetail` (line 133) and `resolveDispute`
   (line 168) call `bookingPaymentRepository.findById(dispute.getBookingId())` with no status filter.
   Add the identical guard `RevenueReportingService.java:149-150`/`:181-182` already established:
   ```java
   Optional<BookingPayment> paymentOpt = bookingPaymentRepository.findById(dispute.getBookingId())
       .filter(bp -> "CAPTURED".equals(bp.getStatus()));
   ```
   at both call sites, replacing the current unguarded `bookingPaymentRepository.findById(...)` call.
   Behavior-preserving today (every dispute currently reachable is on a booking whose payment already
   settled to `CAPTURED` before `DISPUTED` becomes reachable per `BookingStateMachine`) — this closes the
   tripwire so a future state-machine change cannot silently make a `CAPTURE_PENDING`/other-status row's
   provisional amounts leak into a dispute's credited/charged totals or refund calculation. No other
   change to either method's control flow.

2. **AC2 — Feature-gate and config misconfiguration WARN paths gain a Micrometer counter, mirroring
   `PaymentPendingSweeper`'s established `MeterRegistry`-injection pattern.** None of the three affected
   classes currently inject `MeterRegistry`; add it as a new field via each class's existing
   `@RequiredArgsConstructor` (`DrillLibraryService`, `DrillUploadService`) or as a new explicit
   constructor parameter (`ConfigService`, which does not use `@RequiredArgsConstructor` — see the
   compile-break warning below).
   - **`ConfigService`**: add a `config.value.misconfigured` counter, tagged `key` and `reason`
     (`"missing"` or `"non_boolean"`), incremented alongside each existing `log.warn(...)` inside
     `getBoolean(String)` (line 110) and `parseBoolean` (line 129) — the two call sites the ledger item
     names. Do **not** touch the separate numeric-value fail-open warn paths (`getLong`/`getBoundedLong`,
     lines ~76-99) — those are a different, unnamed ledger item and out of this AC's scope.
   - **`DrillLibraryService.resolveMinEnabledTier()`** (line 253) and
     **`DrillUploadService.resolveMinUploadTier()`** (line 150): both add a `feature.gate.fully_disabled`
     counter (same counter name, mirroring `ShedLockConfig`'s single-counter-many-tags shape), tagged
     `feature` with `"sessionBuilder"` and `"drillVideoUpload"` respectively, incremented alongside each
     existing `log.warn(...)`.
   - **Compile-break warning**: `ConfigService`'s constructor is explicit (not `@RequiredArgsConstructor`)
     and has exactly one manual test-construction site —
     `ConfigServiceTest.java:42` (`new ConfigService(configRepository, configProperties, configMapper)`) —
     which must gain a fourth `MeterRegistry` argument or the test module fails to compile.
     `DrillLibraryService` has **two** manual construction sites needing the same treatment —
     `DrillLibraryServiceTest.java:61` and `DrillSearchServiceTest.java:49` (both currently
     `new DrillLibraryService(drillRepository, drillVideoRefRepository, drillTagRepository, configService,
     coachProfileService, videoRepository, videoProviderAdapter)`) — and `DrillUploadService` has **one**,
     `DrillUploadServiceTest.java:64-66`. All four sites need a `@Mock MeterRegistry meterRegistry` field
     added and passed through the constructor call, or those three test files fail to compile.

3. **AC3 — `SessionPackPaymentService.getPacksForParent` pushes its `coachId` filter into SQL.** Add a
   new derived-query method to `SessionPackPurchaseRepository`, matching the file's existing
   Spring-Data-derived-query convention:
   ```java
   List<SessionPackPurchase> findByParentIdAndCoachIdOrderByCreatedAtDesc(Long parentId, UUID coachId);
   ```
   In `SessionPackPaymentService.getPacksForParent` (lines 82-97), replace the unconditional
   `findByParentIdOrderByCreatedAtDesc(parentId)` call plus its subsequent `.filter(p -> coachId == null ||
   coachId.equals(p.getCoachId()))` with a branch: call the new `coachId`-scoped query when `coachId != null`,
   and keep the existing `findByParentIdOrderByCreatedAtDesc(parentId)` call unchanged when it is `null`.
   No change to the rest of the method (tier lookup, `toResponse` mapping).

4. **AC4 — Localize the three hardcoded-English weekday display arrays via `Intl.DateTimeFormat`, not
   new translation keys.** All three files already import and use `locale` from `useI18n()` for other
   display-only date formatting in the same file — extend that existing pattern rather than adding i18n
   bundle keys.
   - **`WeeklyCalendar.vue`**: inside the `weekDays` computed (lines 93-113), replace the hardcoded
     `const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']` array and its `dayNames[i]` read
     with `new Intl.DateTimeFormat(locale.value, { weekday: 'short', timeZone: props.coachTimezone
     }).format(d)` — reusing the same `d` object the adjacent `date` computation two lines below already
     builds for the same day, and the same `timeZone: props.coachTimezone` option that computation already
     passes.
   - **`AvailabilityManagerPage.vue`**: convert the plain `const dayOptions = [...]` (lines 225-233) into a
     `computed(() => ...)` that builds the same seven `{ label, value }` entries from a fixed
     Monday-anchored reference date (e.g. `new Date('2024-01-01T00:00:00')`, a confirmed Monday — any
     Monday works since only the weekday name is read, not the date itself) offset by `value - 1` days,
     with `label: new Intl.DateTimeFormat(locale.value, { weekday: 'long' }).format(d)`. Update the
     template's `:options="dayOptions"` reference to read the computed's `.value` implicitly (Vue
     `<script setup>` unwraps top-level refs/computed in templates automatically — no template change
     needed beyond confirming `dayOptions` is still the identifier in scope).
   - **`CoachCommandCenterPage.vue`**: rewrite `dayLabel(index)` (lines 277-279) to compute from the same
     Monday-anchored reference date and index offset as above, with `weekday: 'short'`, instead of indexing
     into the hardcoded `['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']` array. **Do not touch
     `getDayIndex` (lines 266-274)** — its hardcoded `'en'` `Intl.DateTimeFormat` call and hardcoded English
     comparison array are a *matching* mechanism (parsing a real booking's weekday against a fixed
     reference), not a *display* mechanism, and the ledger's own D3 explicitly warns that localizing it in
     isolation would make every non-English coach's schedule silently misbucket bookings into the wrong day
     column. `dayLabel` and `getDayIndex` do not currently share any array or constant — confirmed by
     inspection they are two independent literal arrays today, so this AC's rewrite of `dayLabel` cannot
     accidentally touch `getDayIndex`.
   - No frontend test coverage — standing repo-wide gap (no `*.spec.js`/test runner exists anywhere in
     `src/frontend`, per every prior story's Dev Notes). Verify by `npx eslint` on all three touched files
     and by reading the diff against each file's existing `Intl.DateTimeFormat(locale.value, ...)`
     precedent in the same file.

5. **AC5 — Ledger hygiene.** In `deferred-work.md`:
   - Tag both DisputeService restatements with `` `[PICKED UP by skillars-deferred-41 AC1]` ``: D5 under
     `## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)` and
     D18 under `## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-
     retention (2026-08-11)` — the same open item recorded twice; tag both, not just one.
   - Tag the `## Deferred from: code review of skillars-deferred-21-...` `ConfigService.getBoolean`
     item and the `## Deferred from: code review of skillars-deferred-22-...` fully-misconfigured-gate
     item, each with `` `[PICKED UP by skillars-deferred-41 AC2]` ``.
   - Tag the `## Deferred from: code review of skillars-3-2-session-pack-purchase-credit-dashboard`
     in-memory-`coachId`-filter item with `` `[PICKED UP by skillars-deferred-41 AC3]` ``.
   - Tag the `## Deferred from: skillars-uat-4-i18n-locale-and-message-resolution-integrity` D2 item
     (hardcoded weekday arrays) with `` `[PICKED UP by skillars-deferred-41 AC4]` ``.
   - Tag Def1 (`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system`,
     2026-06-20) with `` `[STALE — verified against current code by skillars-deferred-41 story creation,
     2026-08-20: already fixed. QuotaReservationTimeoutService.java:23 declares MAX_RUN_DURATION =
     Duration.ofMinutes(8), and expireStaleReservations() checks it in its drain loop's condition,
     logging a WARN and stopping if exhausted — exactly the max-iteration/max-time guard this item asks
     for. Added by an earlier story, unannotated in this ledger.]` `` — do not delete the item, per this
     file's own "delete only once genuinely implemented, not once merely annotated" convention; the tag is
     enough for future audits to skip it.
   - Tag D2 (`## Deferred from: code review of skillars-deferred-4`, first section, 2026-07-02) with
     `` `[STALE — verified against current code by skillars-deferred-41 story creation, 2026-08-20:
     already fixed. ShedLockConfig.java's lockProvider bean already logs and increments a
     scheduler.lock.skipped counter (tagged lock_name) whenever delegate.lock(...) returns empty — exactly
     the missing log/metric this item asks for. Added by an earlier story, unannotated in this ledger.]` ``.
   - Tag Def16 (`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system Run
     2`, 2026-06-20) with `` `[STALE — verified against current code by skillars-deferred-41 story
     creation, 2026-08-20: already fixed. AccountManagementFacade.java:132-133 and :231 all already guard
     the call with a null check (userDTO.getEmail() != null ? userDTO.getEmail().toLowerCase() : null) —
     no unguarded .getEmail().toLowerCase() call exists anywhere in the file. Added by an earlier story,
     unannotated in this ledger.]` ``.

## Tasks / Subtasks

- [ ] Task 1: DisputeService payment-status guard (AC: #1)
  - [ ] 1.1 In `DisputeService.java`, replace the unguarded `bookingPaymentRepository.findById(...)` at
    line 133 (`getAdminDisputeDetail`) and line 168 (`resolveDispute`) with the `.filter(bp ->
    "CAPTURED".equals(bp.getStatus()))`-guarded version, matching `RevenueReportingService.java:149-150`
    exactly.
  - [ ] 1.2 Add or extend a unit test (no `DisputeServiceTest` exists yet — create one, or add to whatever
    test class already covers `DisputeService` if one is found during implementation) asserting a
    non-`CAPTURED` `BookingPayment` row is treated as absent (zero credited/charged amounts) by both
    `getAdminDisputeDetail` and `resolveDispute`, mirroring how `RevenueReportingService`'s own receipt
    paths are covered.
  - [ ] 1.3 Run the existing dispute ITs (`AdminDisputeResolveIT`, `DisputeDismissIT`,
    `DisputeSubmissionIT`) and confirm they remain green — their fixtures already seed `'CAPTURED'` rows
    (`AdminDisputeResolveIT.java:101-103`), so this change is behavior-preserving for them.
- [ ] Task 2: Misconfiguration metrics (AC: #2)
  - [ ] 2.1 Add `MeterRegistry meterRegistry` to `ConfigService`'s explicit constructor; increment a new
    `config.value.misconfigured` counter (tags `key`, `reason`) alongside the existing `log.warn(...)` in
    `getBoolean(String)` and `parseBoolean`.
  - [ ] 2.2 Update `ConfigServiceTest.java:42`'s manual `new ConfigService(...)` call with a fourth
    `MeterRegistry` argument (a `SimpleMeterRegistry`, matching `PaymentPendingSweeperTest`'s existing
    convention, or a `@Mock`).
  - [ ] 2.3 Add `MeterRegistry meterRegistry` as a new `@RequiredArgsConstructor` field to
    `DrillLibraryService` and `DrillUploadService`; increment a `feature.gate.fully_disabled` counter
    (tag `feature`, value `"sessionBuilder"`/`"drillVideoUpload"` respectively) alongside each class's
    existing `log.warn(...)` in `resolveMinEnabledTier()`/`resolveMinUploadTier()`.
  - [ ] 2.4 Update the three manual construction sites this breaks —
    `DrillLibraryServiceTest.java:61`, `DrillSearchServiceTest.java:49`, `DrillUploadServiceTest.java:64-66`
    — each with a new `@Mock MeterRegistry meterRegistry` field passed through.
  - [ ] 2.5 Add or extend unit tests asserting each of the three counters increments on the misconfigured
    path and does not increment on the correctly-configured path (mirroring
    `PaymentPendingSweeperTest`'s `SimpleMeterRegistry`-based assertion style).
  - [ ] 2.6 Run the affected test classes and confirm green; `npx eslint` is not relevant here (backend
    only).
- [ ] Task 3: SessionPackPaymentService coachId query push-down (AC: #3)
  - [ ] 3.1 Add `findByParentIdAndCoachIdOrderByCreatedAtDesc(Long parentId, UUID coachId)` to
    `SessionPackPurchaseRepository`.
  - [ ] 3.2 In `SessionPackPaymentService.getPacksForParent`, branch on `coachId != null` to call the new
    query instead of loading every row and filtering in Java.
  - [ ] 3.3 Update `SessionPackPaymentServiceTest.getPacksForParent_filtersByCoachId` (currently stubs
    `findByParentIdOrderByCreatedAtDesc` and expects in-memory filtering) to instead stub the new
    `findByParentIdAndCoachIdOrderByCreatedAtDesc` method returning only the matching row — the
    `forOtherCoach` fixture is no longer expected to be loaded at all, since the DB query now excludes it.
  - [ ] 3.4 Run `SessionPackPaymentServiceTest` and confirm green.
- [ ] Task 4: Weekday-name localization (AC: #4)
  - [ ] 4.1 `WeeklyCalendar.vue`: replace the hardcoded `dayNames` array with a per-day
    `Intl.DateTimeFormat(locale.value, { weekday: 'short', timeZone: props.coachTimezone }).format(d)` call
    reusing the existing `d` object.
  - [ ] 4.2 `AvailabilityManagerPage.vue`: convert `dayOptions` to a `computed` building localized
    `{ label, value }` entries from a fixed Monday reference date via `Intl.DateTimeFormat(locale.value, {
    weekday: 'long' })`.
  - [ ] 4.3 `CoachCommandCenterPage.vue`: rewrite `dayLabel(index)` the same way with `weekday: 'short'`.
    **Do not touch `getDayIndex`** (lines 266-274) or its hardcoded English comparison array.
  - [ ] 4.4 Run `npx eslint` on all three touched files and confirm clean. Manually verify (per this
    repo's established no-test-infra convention) that switching the app's locale still renders sensible
    weekday labels in each of the three components' UI.
- [ ] Task 5: Ledger hygiene (AC: #5)
  - [ ] 5.1 Apply `[PICKED UP by skillars-deferred-41 AC1]` to both restatements (D5, D18) of the
    DisputeService finding in `deferred-work.md`.
  - [ ] 5.2 Apply `[PICKED UP by skillars-deferred-41 AC2]` to the `skillars-deferred-21` and
    `skillars-deferred-22` misconfiguration items.
  - [ ] 5.3 Apply `[PICKED UP by skillars-deferred-41 AC3]` to the `skillars-3-2` in-memory-filter item.
  - [ ] 5.4 Apply `[PICKED UP by skillars-deferred-41 AC4]` to the `skillars-uat-4` D2 item.
  - [ ] 5.5 Apply the three `[STALE — ...]` annotations to Def1, D2 (skillars-deferred-4), and Def16 as
    specified in AC5 above.

## Dev Notes

- **This story bundles four unrelated fixes across four modules (`admin`, `config`+`session`, `payment`,
  frontend) by explicit instruction — do not look for a unifying theme beyond "small, real,
  decision-light, and this pass was asked to bundle."**
- **AC1 is a pure mirror of an already-shipped, already-code-reviewed pattern** — do not redesign the
  guard or generalize it into a shared helper method; `RevenueReportingService` doesn't share one either,
  and introducing one here would be a larger refactor than this AC asks for.
- **AC2's counter names are new — there is no existing `config.value.misconfigured` or
  `feature.gate.fully_disabled` metric to collide with.** Verified by grepping `Counter.builder` across
  `src/main` before choosing these names. Keep both as plain `MeterRegistry`-injected counters, matching
  `PaymentPendingSweeper`'s and `ShedLockConfig`'s shape — do **not** introduce a new dedicated `*Metrics`
  service class (like `VideoMetrics`) for what is only 1-2 counters per class; that pattern is reserved in
  this codebase for modules with many related metrics (timers, gauges, and counters together), not a
  single WARN-path counter.
  - **`ConfigService`'s constructor is NOT `@RequiredArgsConstructor`** (it's hand-written, likely because
    the class also builds a `ConcurrentHashMap` cache and schedules a refresh) — the `MeterRegistry`
    parameter must be added to the explicit constructor signature, not left to Lombok.
- **AC3's fix is intentionally the smallest possible push-down** — a single new derived-query method
  branched on by the service, not a rewrite of `getPacksForParent`'s tier-lookup/response-mapping logic
  that follows it. Do not attempt to also push the tier lookup into a single joined query; that's a
  separate, larger change the ledger item never asked for.
- **AC4's "no new i18n keys needed" framing is the load-bearing insight of this AC — verify it holds
  before implementing.** If any of the three files turns out not to already have `locale` in scope from
  `useI18n()` (unlikely, since all three currently pass this story's own verification, but re-check at
  implementation time in case a file was refactored since story creation), fall back to the ledger item's
  original framing (real i18n bundle keys) rather than forcing the `Intl.DateTimeFormat` approach onto a
  file that doesn't already have the plumbing for it.
- **AC4's Monday-anchored reference date (`2024-01-01T00:00:00`) is an implementation detail, not a
  requirement — any confirmed Monday works,** since only the weekday name is ever read from the
  constructed `Date`, never the date itself. Do not use `new Date()` (today) as the reference, since that
  would make the reference weekday shift daily for no reason and complicate reasoning about which offset
  produces which day.
- **AC4 does not touch `CoachCommandCenterPage.vue`'s `getDayIndex` matching logic under any
  circumstance.** If implementation discovers `dayLabel` and `getDayIndex` share more structure than this
  story's research found (they were confirmed to be two fully independent literal arrays at story
  creation), stop and re-scope rather than editing `getDayIndex` to "clean it up" — the ledger's own D3
  item exists specifically to warn against that exact mistake.
- Per `docs/validation-strategy.md`, run targeted tests only (the extended/new
  `DisputeService`/`ConfigService`/`DrillLibraryService`/`DrillUploadService`/`SessionPackPaymentService`
  tests, and `npx eslint` on the three touched frontend files) — do not run `mvn verify` unless targeted
  tests prove insufficient or this is final pre-PR validation.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java` — two-line change,
  each wrapping an existing `findById` call in `.filter(...)` (AC1).
- `src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java` — new or extended
  test file (AC1).
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` — new `MeterRegistry`
  constructor param + two counter-increment call sites (AC2).
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigServiceTest.java` — constructor-call
  fix + new counter assertions (AC2).
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` — new
  `MeterRegistry` field + one counter-increment call site (AC2).
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` — new
  `MeterRegistry` field + one counter-increment call site (AC2).
- `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java`,
  `DrillSearchServiceTest.java`, `DrillUploadServiceTest.java` — constructor-call fixes (three manual
  construction sites) + new counter assertions (AC2).
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` — one
  new derived-query method (AC3).
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` —
  `getPacksForParent`'s query call site branched on `coachId` (AC3).
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentServiceTest.java` —
  `getPacksForParent_filtersByCoachId` test updated for the new query shape (AC3).
- `src/frontend/src/components/availability/WeeklyCalendar.vue` — `dayNames` array replaced with a
  per-day `Intl.DateTimeFormat` call (AC4).
- `src/frontend/src/pages/coach/AvailabilityManagerPage.vue` — `dayOptions` converted to a localized
  `computed` (AC4).
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` — `dayLabel()` rewritten; `getDayIndex`
  untouched (AC4).
- `_bmad-output/implementation-artifacts/deferred-work.md` — four `[PICKED UP]` tag sets (AC1's item
  tagged twice, once per restatement) + three `[STALE]` corrections (AC5).
- No new backend or frontend files beyond the two new/extended test files named above. No changes to
  `RevenueReportingService.java`, `PaymentPendingSweeper.java`, or `ShedLockConfig.java` — all three are
  read-only precedents this story mirrors, not touched.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-uat-3-payment-capture-integrity-and-backup-retention` (D5, D18) — AC1's source]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java` lines
  106-153, 155-210 — AC1's target methods `getAdminDisputeDetail`/`resolveDispute`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java`
  lines 142-190 — AC1's guard pattern to mirror, `getCoachReceipt`/`getParentReceipt`]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening` and `## Deferred from:
  code review of skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-
  fixes` — AC2's two source items]
- [Source: `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java` lines
  106-131 — AC2's `ConfigService` target]
- [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` lines
  246-255 and `DrillUploadService.java` lines 143-152 — AC2's feature-gate targets]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`
  lines 81-82, 173, 186 — AC2's `MeterRegistry`-injection pattern to mirror]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java` — AC2's
  single-counter-many-tags precedent, and AC5's D2 stale-item verification]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-3-2-session-pack-purchase-credit-dashboard` — AC3's source, retargeted by its own 2026-08-04
  audit note]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
  lines 82-97, `SessionPackPurchaseRepository.java` — AC3's targets]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: skillars-uat-4-
  i18n-locale-and-message-resolution-integrity` D2 — AC4's source]
- [Source: `src/frontend/src/components/availability/WeeklyCalendar.vue` lines 69-113,
  `src/frontend/src/pages/coach/AvailabilityManagerPage.vue` lines 165-233,
  `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` lines 172-291 — AC4's three targets, including
  `getDayIndex`'s explicitly-untouched matching logic]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java`
  lines 14-46 — AC5's Def1 stale-item verification]
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/AccountManagementFacade.java`
  lines 132-133, 231 — AC5's Def16 stale-item verification]

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 4-item story per explicit instruction not to create another small story. Re-read `deferred-work.md` end to end (1599 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1 closes a payment-status tripwire in `DisputeService` recorded twice across the ledger's history, by mirroring `RevenueReportingService`'s already-shipped `CAPTURED`-status guard verbatim. AC2 closes two independent "WARN log only, no metric" observability gaps (`ConfigService.getBoolean`, `DrillLibraryService`/`DrillUploadService`'s fully-disabled feature-gate paths) by mirroring `PaymentPendingSweeper`'s established direct-`MeterRegistry`-injection counter pattern — both items were previously left open specifically because that pattern didn't exist yet in this codebase; it does now. AC3 pushes an in-memory `coachId` filter into a one-line Spring Data derived query, closing a `skillars-3-2`-era item an audit had retargeted onto the current payment-module code in 2026-08-04 and left untouched since. AC4 localizes three hardcoded-English weekday-name display arrays using the exact `Intl.DateTimeFormat(locale.value, ...)` technique `skillars-uat-4` AC1 already proved out repo-wide, avoiding the "needs real translation keys across 3 files" framing the original ledger item assumed was necessary — while explicitly leaving `CoachCommandCenterPage.vue`'s unrelated `getDayIndex` day-matching logic untouched, per that same ledger entry's own warning. AC5 additionally closes 3 stale ledger items (Def1, D2, Def16 — all found already fixed by earlier, unannotated stories) as a research by-product of the full re-read. |
