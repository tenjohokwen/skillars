# Story deferred-159: Homework Timeout Drop, GDPR Export ID-Space & Epic 12 Integrity Hardening

Status: done

## Story

As a **coach whose Quick Complete wrap-up assigns homework, a player requesting their own GDPR export, and an operator reading the coach-access window**,
I want **the verified correctness gaps left open by Epic 12 closed in one pass**: homework that survives the parent-never-confirms timeout path, homework drill ids validated at wrap-up, a GDPR export that looks the player's own bookings up by the right id, timeline `referenceId` hidden from non-author coaches, and a store branch that actually fires,
so that **no coach's homework is silently dropped, another coach's PRIVATE drill can no longer be assigned as homework, and the ledger carries an accurate record of the two items this story closes as already-covered or equivalent**.

## Source

Single-origin story, mined 2026-10-23 from `_bmad-output/implementation-artifacts/deferred-work.md`, the sections written **after** `skillars-deferred-158` was created (12.6 dev/review, 12.7 dev/review, 12.8 review, 12.9 review, 12.10 review, and the deferred-158 review). Citations re-verified against `master` @ `34084961` (12.10, PR #295). Line numbers drift fast; grep the symbol if one looks wrong.

Selection rule: pick items that are **verified-real at HEAD**, **fix with no new product decision**, and **share a code neighbourhood** (booking completion, homework, timeline read, GDPR export, config). Everything needing an owner decision or a migration is excluded below.

### Ledger item → AC map (so future mining passes do not re-pick these)

| # | Ledger bullet | Verified at HEAD | AC |
|---|---|---|---|
| H1 | "QUICK-complete timeout auto-confirm drops homework" (12.8 review) | `QuickCompleteTimeoutService.java:~63-68` publishes `BookingCompletedEvent(... List.of(), ...)`; the parent-confirm path at `BookingCompletionService.java:409` uses `deserializeHomeworkDrillIds(scd.getHomeworkDrillIds())` | AC1 |
| H2 | "Homework drill ids not ownership-validated" (12.8 review) | `BookingCompletionService.submitWrapUp:183-189` only serialises `req.homeworkDrillIds()` (`@Size(max=2)` on `WrapUpRequest:18`); `HomeworkAssignmentService.handleBookingCompleted:60-71` assigns any id | AC2 |
| H3 | "`GdprExportService:122` passes a User id where a PlayerProfile id is expected" (12.6 dev) | `GdprExportService.buildBookings:121-123`: `bookingRepository.findAllByPlayerId(userId)`; `Booking.playerId` is a `PlayerProfile` PK (see memory *Skillars ID Spaces*). The ledger's `:122` is still accurate at HEAD. The defect is id-space hygiene, **not** a live leak (see AC3) | AC3 |
| H4 | "Timeline `referenceId` bypasses the AC9 metadata allowlist" (12.10 review) | `TimelineQueryService.getTimeline` maps `e.getReferenceId()` straight into `TimelineEventResponse` for every viewer | AC4 |
| H5 | "Timeline authorship comes from `metadata.coachId`, not the row's `coach_id` column" (12.10 review) | `TimelineQueryService.redactCoachPrivateFieldsIfNotAuthoringCoach` compares `metadata.get(METADATA_KEY_COACH_ID)`. **Closed as verified-equivalent, no code change** (see AC4) | AC4 |
| H6 | "No startup rejection for an out-of-range `coachAccessExpiryDays`" (12.10 review) | `ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS` is `failFast=false`; `ConfigService.getBoundedLong` falls back to the default 90 **with a WARN** (it does not clamp). `ConfigStartupAssertion` already logs ERROR + metric for this key, and the admin API already rejects out-of-range writes with a 400. **Closed as already-covered, ledger correction only** | AC5 |
| H7 | "`sessionBuilder.store.js` checks `data.helpCode === 'security.featureGated'`" (12.9 review) | `sessionBuilder.store.js:151-154`. `ApiAdvice.featureGatedHandler` (`ApiAdvice.java:390-392`) puts `security.featureGated` in `errorMsg.errorKey`; `helpCode` is a minted log reference. `SkillsRadarAssessmentPanel.vue:177` already does it correctly | AC6 |
| H8 | "No test for the PARENT loop continuing past a `CHILD_BOOKING_*` skip" (deferred-158 review) | `GdprErasureServiceTest` PARENT booking-lock tests `erase_parentUser_childBookingStatementLockTimeout_...` (`:836`, deferred-157) and `erase_parentUser_childBookingStatementContended_...` (`:863`, deferred-158) use one child | AC7 |

### Explicitly NOT in this story (and why)

- **Plan lock / drill snapshot at QUICK wrap-up** (12.8): needs a product decision (lock the plan vs snapshot drill ids into `session_completion_data`).
- **`TimelineEventListener.onBookingCompleted` not idempotent** (12.8): pre-existing, no production replay source once H1 is fixed; needs a unique-index migration to be atomic. Leave in the ledger.
- **`@JsonIgnore` on `coachPlanNote` omits it from the coach's own export** (12.6): needs a DTO projection of `bookings.json`, and AC3 deliberately does not change the serialised shape.
- **`isBookingPlannable` accepts only `CONFIRMED`**, **`setPlayerRequest` bare `Instant.now()`**, **coach plan note last-write-wins** (owner-accepted), **`findLatestWhatsNextSuggestion` time bound**, **plan-note panel after locked 403**: UX or product-policy items.
- **12.7 items** (`voice_note_text` contract drop, orphan audio, recovered-recap timestamps, recap attendance on recovery, raw recap text, refunded sessions, sweeper-vs-resume text loss): each needs a migration or a design call.
- **12.9 radar `pagehide`/unordered PUTs, `fetchCoachTier` timeout**: need a transport decision (`keepalive`/`sendBeacon`) or an app-wide axios timeout.
- **Scan-equivalence count pin** (deferred-158): a tighter exact pin makes every i18n key add a spec edit; not worth the churn.
- **Plan-path private drill names** (`SessionPlanService.createSession`/update resolve any drill id with `findAllById` and no PRIVATE/owner check; `TimelineEventListener.onBookingCompleted` then snapshots plan drills onto a SESSION_COMPLETED row whose `drills` key every viewer sees). Found by the story review of this story. Same class of defect as AC2 but a different service and gate; **add a ledger bullet** (Task 8).
- **Two 12.6 test-gap items:** no page-level spec for `SessionBuilderPage` mount ordering (heavy dependency graph), and `CoachPlanNoteResourceIT.patchNote` builds its body with `Map.of`, so the `planNote: null` clear contract is not covered end to end. Test-only, decision-free; left for a test-hygiene story.
- **Pending QUICK rows at deploy time:** QUICK rows created before AC2 ships stay pending up to the timeout (default 24h) with unvalidated `homework_drill_ids`. Not new in kind (`confirmCompletion` publishes the same rows today); one line beside the plan-path ledger bullet (Task 8.2).
- **Erasure between wrap-up and the QUICK timeout:** AC1 makes the delayed path write `homework_assignments` and a `HOMEWORK_ASSIGNED` row, and neither listener checks a development-data erasure marker. The `SESSION_COMPLETED` write on the same path has the same gap, so it is not new in kind. One-line ledger note (Task 8).
- Carry-overs excluded by the deferred-158 story still apply (Unknown Player, PDF glyphs, deferred-143, deferred-154 route-constraint specs, human-only reviews, `main."user"` index, `[deferred-19]`).

## Acceptance Criteria

### Booking completion and homework (H1, H2)

1. **The Quick Complete timeout carries the coach's homework.** `QuickCompleteTimeoutService.processExpiredQuickCompletes` builds `BookingCompletedEvent` with the homework drill ids stored on `scd`, not `List.of()`. Do not duplicate `BookingCompletionService.deserializeHomeworkDrillIds` (private, `:417-427`): extract it to **one static helper** in `booking.service` that takes the `ObjectMapper` and the JSON string (e.g. `HomeworkDrillIds.parse(objectMapper, json)`), keeping its exact current behaviour: **null/blank → empty list, silently** (the common homework-less case; no log), **unparseable → empty list + WARN** (only the `catch` at `:423-425` logs). `QuickCompleteTimeoutService` has no `ObjectMapper` today (its fields are `:31-35`) and `QuickCompleteTimeoutServiceTest` constructs it by hand (`:58`): inject the mapper and update that constructor call. Result: a QUICK wrap-up with homework that the parent never confirms produces `homework_assignments` rows and a `HOMEWORK_ASSIGNED` timeline entry after the timeout, as the parent-confirm path does.

2. **Homework drill ids are validated at wrap-up submit, before anything is saved.** In `BookingCompletionService.submitWrapUp` place the gate **after `verifyStatus(booking, COMPLETED_PENDING_CONFIRMATION)` (`:168`) and before `new SessionCompletionData()` (`:170`)**, so the deferred-153 sweeper-placeholder message still wins when it applies. Every id in `req.homeworkDrillIds()` must resolve to a `Drill` that is `libraryType = 'PLATFORM'` and `status = 'ACTIVE'`, or `libraryType = 'PRIVATE'`, `ownerCoachId == coach.getId()` and `status = 'ACTIVE'`. **This is the rule `DrillSuggestionService.allCandidates` (`:145-148`) already uses (its PRIVATE filter lives in the `DrillRepository:19` JPQL and the `chk_drill_owner` constraint) and the wizard's drill source**; do **not** reuse `DrillLibraryService:153` (that is the `addTag` rule: it rejects PLATFORM drills and ignores status).
   - Any other id (unknown, another coach's PRIVATE, archived) **and any `null` element** (`@Size(max=2) List<UUID>` accepts `[null]`) rejects the whole request with `ResourceNotFoundException` (already imported in this class). The same response for unknown and foreign ids avoids confirming that another coach's drill exists. No partial save, no status transition.
   - Resolve all ids in **one** `drillRepository.findAllById` call. Deduplicate first so `[a, a]` is accepted, and **persist and publish the deduplicated list** (LIVE currently publishes the raw `req.homeworkDrillIds()` at `:207`; change it to the deduplicated list so both modes agree).
   - `booking.service` does not import `session` today; injecting `DrillRepository` creates no bean cycle (it is a repository). No ArchUnit/Modulith rule forbids it.
   - Scope of the privacy claim: this closes *assigning another coach's PRIVATE drill as homework*. The plan path is a separate gap (see exclusions). The async `HomeworkAssignmentService.handleBookingCompleted` is left unchanged.

### GDPR export (H3)

3. **The PLAYER branch of `GdprExportService.buildBookings` looks the player's own `PlayerProfile` up by user id.** Replace `bookingRepository.findAllByPlayerId(userId)` (`:121-122`) with `playerProfileRepository.findByUserId(userId)` and `findAllByPlayerId(profile.getId())`; no profile → add nothing, no exception. The parent, coach and dedupe-by-id logic and the serialised `Booking` shape are unchanged.
   - **Honest rationale (corrected by the story review):** this is id-space hygiene, **not** a live privacy leak. `User` and `PlayerProfile` ids are both `BaseEntity` `@Id @Tsid` (app-side, no DB sequence), so a collision is practically impossible; the memory *Skillars ID Spaces* records that the "independent sequences collide" claim was wrong twice. The player's own bookings are already exported today, because the **parent query runs for every role** and a self-registered player books with `parentId = own user id`. The current PLAYER branch is therefore a query that effectively never matches. Fix it so the code means what it says; do not describe it as a leak anywhere (ledger, Dev Agent Record, PR).
   - `GdprExportService` does **not** inject `PlayerProfileRepository` today (verified): add it as a constructor dependency (`GdprExportService` already imports `security.repo.UserRepository`, so the package dependency exists) and update the test wiring with an explicit `@Mock PlayerProfileRepository`.
   - `GdprExportServiceTest` uses `MockitoExtension` (strict stubs). Existing `buildBookings_*` cases that stub `findAllByPlayerId(userId)` must move to the profile id. For the new regression case (`userId != profile.getId()`), assert `verify(bookingRepository, never()).findAllByPlayerId(userId)` and that the profile-id call is made; do not leave a never-called stub (it throws `UnnecessaryStubbingException`; the class's `lenient()` use at `:51` is the escape hatch only if truly needed).

### Timeline read path (H4, H5)

4. **`referenceId` follows the same visibility rule as `metadata`.** In `TimelineQueryService.getTimeline`, `referenceId` is returned as-is to the player/parent and to the authoring coach; for a **coach viewer, `referenceId` is `null` unless `isAuthoringCoach`** (allowlist form, matching the AC9 rule that unknown/stub types expose nothing). On today's types that means `null` on `SESSION_COMPLETED`/`HOMEWORK_ASSIGNED` for a non-author coach (another coach's booking id) and on `PERFORMANCE_REPORT` (the report id the metadata allowlist already hides). The enum also holds four stub types with no producer (MILESTONE_REACHED, VIDEO_UPLOADED, PAYMENT_RECEIVED, REVIEW_LEFT); this form keeps their future `referenceId`s hidden from coaches by default. `PERFORMANCE_REPORT` is not an authorable type, so **every** coach, including the one who generated the report, gets `null`, consistent with the metadata rule ("coaches see nothing"). `RADAR_ASSESSMENT` rows always have a null `referenceId`, so there is nothing to do for them.
   - **Implementation trap:** `redactCoachPrivateFieldsIfNotAuthoringCoach` returns `null` on `metadata == null` (`:72-74`) *before* it works out authorship. Compute `isAuthoringCoach` once per row **ahead of** that early return (lift it out of the method) and use it for both `metadata` and `referenceId`; otherwise a metadata-less row would leak its `referenceId`. **Guard the metadata read** (`metadata != null && ...`): the current expression dereferences it and would NPE if hoisted unguarded. Consequence, accepted: a null-metadata row is never "authored", so its `referenceId` is hidden from every coach including the author. Real producers always write metadata (`TimelineEventListener:72,193`), so only the defensive test fixture exercises this.
   - **H5 is closed as verified-equivalent, no code change.** Authorship stays `metadata.coachId`. Evidence: for an attended `SESSION_COMPLETED` the column and `metadata.coachId` are written from the same `event.getCoachId()` (`TimelineEventListener:72,105`); for an unattended one and for **every** `HOMEWORK_ASSIGNED` the column is `null` by design (`:105`, `:194-195`, the 5-arg overload) so only the metadata can decide; V160 backfilled the column *from* the metadata; and `PlayerTimelineRepository:62-68` (deferred-155) deliberately uses `metadata->>'coachId'` as the authorship signal because the column is attendance-gated. A column-first rule would change no outcome and contradict that precedent.
   - Verified before writing the story: no frontend code reads `referenceId` from the timeline response (the only grep hit is `CreditStatementPage.vue`, unrelated), and no endpoint resolves a report by that id. Re-grep before changing it and record the result in the Dev Agent Record.
   - Tests (`TimelineQueryServiceTest` / the existing access IT): non-author coach sees `referenceId == null` on the three types; author coach and player/parent see it; a `SESSION_COMPLETED` row with **null metadata** and a non-null `referenceId` is nulled for a non-author coach (the trap above); every new case fails on the pre-change code.

### Config (H6)

5. **H6 is closed as already-covered; ledger correction only, no behaviour change.** The ledger bullet's premise ("only a WARN", "have `ConfigStartupAssertion` fail") is inaccurate: `ConfigStartupAssertion` already logs ERROR and increments `config.value.misconfigured{reason=out_of_range}` for every `BoundedKey` regardless of `failFast` (existing test `nonFailFastKeyOutOfRange_noThrow_metricIncrementedWithKeyAndReasonTagsOnly`, `ConfigStartupAssertionTest:130`), and `ConfigService.updateConfig` → `rejectOutOfRange` already returns 400 for an out-of-range admin write, so a bad value reaches the DB only through a direct write or a Flyway script. The remaining read-time WARN sits in the shared `ConfigService.getBoundedLong` (~18 keys) and runs on every timeline and roster read; raising it to ERROR would log per request and needs a key-specific branch plus changes to `CoachAccessWindowTest`/`ReportGenerationServiceTest` strict stubs. Not worth it. `failFast` stays `false`, justified **only** by the `ConfigBounds` severity rubric (a bad value here degrades, it does not lose data or halt a core flow). Add one sentence to the `CoachAccessWindow` Javadoc saying an out-of-range stored value falls back to the 90-day default with a WARN, and that `0` cannot be set through the admin API. If the owner wants a hard boot refusal, that is a separate decision.

### Frontend (H7)

6. **The session-builder feature-gate branch fires on a real response.** `sessionBuilder.store.js:~151-154` must test `e?.response?.data?.errorMsg?.errorKey === 'security.featureGated'` (the shape `SkillsRadarAssessmentPanel.vue:177` already uses). `sessionBuilderStoreSpec.js:~123` mocks the body in the same wrong shape (`{ helpCode: 'security.featureGated' }`): change it to `{ helpCode: 'ABC123', errorMsg: { errorKey: 'security.featureGated', message: '...' } }` and add a negative case, a 403 whose `helpCode` happens to equal `security.featureGated` but whose `errorKey` is different, must **not** set `isGated`. Do not touch any other 403 handling in the store.

### Test coverage follow-up (H8)

7. **The PARENT erasure loop is pinned to continue past a `CHILD_BOOKING_*` skip.** In `GdprErasureServiceTest` add a two-child PARENT test modelled on `erase_parentUser_childBookingStatementLockTimeout_...` (`:836`) and `erase_parentUser_childBookingStatementContended_...` (`:863`). `BookingSchemaLockException` is a **private** static class (`GdprErasureService:~1710`), so the test cannot throw it; make the booking statement throw `PessimisticLockingFailureException`/`CannotAcquireLockException` for child A only (as the existing tests do) (**stub child A first** in the `findByParentIdOrderByIdAsc` list: strict stubs can flag an unused child-A `doThrow` if B is processed first, and A-first is the only order in which the `break` mutation is detectable) and assert child B's tombstone is saved (`playerProfileRepository.save(childB)`) and child A's is not. **Mutation:** temporarily add a `break` inside the `catch (BookingSchemaLockException e)` at `GdprErasureService:~1045` and confirm the new test fails, then restore. (`GdprErasureIT:~2188` already catches a `break` at the end of the loop body, so that is not the mutation that proves this test.)

## Tasks / Subtasks

- [x] Task 1: Homework survives the timeout path (AC: #1)
  - [x] 1.1 Extract the static `HomeworkDrillIds.parse(ObjectMapper, String)` helper; use it in `confirmCompletion` and `QuickCompleteTimeoutService`; inject `ObjectMapper` and fix `QuickCompleteTimeoutServiceTest:58`
  - [x] 1.2 Unit test the helper (null and blank → empty, **no WARN**; valid; malformed JSON → empty + WARN)
  - [x] 1.3 IT (none exists for this service): QUICK wrap-up with 1 drill; the cutoff is a bare `Instant.now()` (`:56`), so backdate `session_completion_data.created_at` (`updatable=false`) via `jdbcTemplate` **inside `transactionTemplate`**, by **more than 24h** (`booking.quick_complete_timeout_hours` is seeded at 24, `V139:59`); run the processor (`@SchedulerLock lockAtLeastFor = PT2M` only matters through the scheduler proxy; call the bean method directly, and check how existing ITs for sibling schedulers do it before adding a second invocation in one test); the homework drill must be a real ACTIVE drill (AC2's gate, and `TimelineDrillSnapshotResolver` drops unresolved ids so no `HOMEWORK_ASSIGNED` row would appear for a random UUID); consider extending `SessionCompletionResourceIT`'s existing QUICK wrap-up fixture instead of a new class; `Awaitility` for the `homework_assignments` row and then the `HOMEWORK_ASSIGNED` timeline row (two async hops: `HomeworkAssignmentService`, then `TimelineEventListener.onHomeworkAssigned`)
- [x] Task 2: Validate homework drill ownership (AC: #2)
  - [x] 2.1 Gate between `verifyStatus` and `new SessionCompletionData()`; reject null elements **first**, then dedupe with `distinct()`/`LinkedHashSet` (`List.copyOf`/`Set.copyOf` NPE on null → 500); one `findAllById`; persist and publish the deduplicated list
  - [x] 2.1b Injecting `DrillRepository` breaks two hand-written constructor calls (`@RequiredArgsConstructor`): `BookingCompletionServiceTest:70` and `SessionPauseResumeServiceTest:62`; add the mock to both
  - [x] 2.2 Tests: own PRIVATE ok, PLATFORM ACTIVE ok, other coach's PRIVATE → `ResourceNotFoundException` with no SCD row and no transition, unknown id rejected, archived rejected, `[null]` rejected, `[a, a]` accepted and persisted once
- [x] Task 3: GDPR export id space (AC: #3)
  - [x] 3.1 `findByUserId` → `findAllByPlayerId(profile.getId())`; inject `PlayerProfileRepository`
  - [x] 3.2 Update `GdprExportServiceTest`; add the `userId != profileId` case with `verify(..., never())`
- [x] Task 4: Timeline `referenceId` (AC: #4)
  - [x] 4.1 Lift authorship ahead of the `metadata == null` early return; null `referenceId` for non-author coaches
  - [x] 4.2 Re-grep frontend for `referenceId` timeline consumers; record result
  - [x] 4.3 Tests per AC4
- [x] Task 5: H6 closure (AC: #5)
  - [x] 5.1 Add the `CoachAccessWindow` Javadoc sentence; no behaviour change
- [x] Task 6: Store gate branch (AC: #6)
  - [x] 6.1 Fix the check and the spec body shape; add the negative case
  - [x] 6.2 Run Prettier and ESLint on both files (the Prettier gate blocks `build-and-push`)
- [x] Task 7: PARENT loop test (AC: #7)
  - [x] 7.1 Two-child test; mutation-verify at the catch
- [x] Task 8: Ledger bookkeeping
  - [x] 8.1 Mark H1-H8 `[CLOSED by skillars-deferred-159 ACn]` in `deferred-work.md`; H5 `[CLOSED as verified-equivalent]` and H6 `[CLOSED as already-covered]`, each with the evidence from AC4/AC5; H3 notes it was hygiene, not a leak
  - [x] 8.2 Add two new ledger bullets: the `SessionPlanService` plan-path private-drill gap, the erasure-marker gap on the delayed QUICK path, and the pending-QUICK-rows note (exclusions)

### Review Findings

- [x] [Review][Patch] AC2 has no mixed valid+invalid homework list test: every rejection case passes a single id, so mutating `!usable.containsAll(distinct)` to `usable.isEmpty()` keeps all tests green and `[validPlatform, foreignPrivate]` would be accepted. Add `[platformActive, foreignPrivate]` → `ResourceNotFoundException`, no SCD save [src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java:162]
- [x] [Review][Patch] Inline `java.util.stream.Collectors.toSet()` and `session.repo` imports wedged between the two `marketplace.repo` imports; use a normal `Collectors` import and keep import groups ordered [src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java:31-32,243]
- [x] [Review][Patch] New ledger section uses `### Deferred from:` where every other section uses `## Deferred from:`; the file also lost its trailing newline [_bmad-output/implementation-artifacts/deferred-work.md:3502]
- [x] [Review][Patch] The `development_status` comment for this story still reads "CREATED ... (/bmad-create-story)" and "H6 coachAccessExpiryDays loud-not-silent", which contradicts AC5 (closed as already-covered, no behaviour change) [_bmad-output/implementation-artifacts/sprint-status.yaml:417]
- [x] [Review][Patch] Testing standards ask the Dev Agent Record to say which negative cases were mutation-checked; AC3's `userId != profileId` case and AC6's negative store case are not mentioned [this file, Dev Agent Record → Completion Notes List]

## Dev Notes

### Current state and what must be preserved

- **`QuickCompleteTimeoutService`**: `@Scheduled` + `@SchedulerLock`, per-row `transactionTemplate.executeWithoutResult` doing `transition(COMPLETE)` then `getBookingOrThrow` then `publishEvent`. The event is `AFTER_COMMIT` for `HomeworkAssignmentService`, `TimelineEventListener` and the SLU listener, so the publish **must stay inside the transaction template** (do not move it out). The strongest reason: `CoachPayoutEnqueueListener:38` is a `@TransactionalEventListener(phase = BEFORE_COMMIT)` on `BookingCompletedEvent` that enqueues the coach payout in the **same** transaction as the `COMPLETE` transition; publishing outside would lose that atomicity. Preserve the two catch branches (already-completed skip, generic error log).
- **`BookingCompletionService`**: `submitWrapUp` for `LIVE` mode publishes with `req.homeworkDrillIds()`; for the other mode only the JSON is persisted and `confirmCompletion` (`:394-415`) deserialises it. AC2 validation sits **before** both. `deserializeHomeworkDrillIds` is `private` today.
- **`HomeworkAssignmentService.handleBookingCompleted`** is `@Async` + `AFTER_COMMIT`, truncates to 2, is idempotent per `(booking_id, drill_id)`. Do not change it.
- **`GdprExportService.buildBookings`** is package-visible and unit-tested directly; keep the signature `(User, Long)`.
- **`TimelineQueryService`**: `VIEWER_ALLOWLIST`, `REPORT_KEYS_FOR_PLAYER_OR_PARENT`, and `redactCoachPrivateFieldsIfNotAuthoringCoach` are the AC9 (12.10) rules. Keep every existing metadata assertion green. `isCoach`/`coachId` are already parameters of `getTimeline`.
- **`PlayerTimelineEvent.coachId`** exists (migration `V160`, index `V161`); it is the attendance-gated clock signal and is `null` for a non-attended `SESSION_COMPLETED` **and for every `HOMEWORK_ASSIGNED`**. Authorship is read from `metadata.coachId` (deferred-155 precedent) and this story keeps it that way.

### Gotchas

- **ID spaces** (memory *Skillars ID Spaces*): `Booking.playerId` is a `PlayerProfile` PK, not a `User` id. Never fix H3 by renaming the parameter.
- **IT writes** (memory *Skillars IT Autocommit Writes*): bare `jdbcTemplate` writes in an IT method body never commit (Hikari `auto-commit=false`); wrap in `transactionTemplate`. Relevant to the Task 1.3 IT setup.
- **Prettier gate** (memory *Skillars CI Prettier Gate*) lives in `.github/actions/`, not `workflows/`; a failure blocks `build-and-push`. Task 6 touches a store and its spec.
- **No local `mvn verify`** (memory *No Local mvn verify*): run only the targeted tests (`GdprExportServiceTest`, `GdprErasureServiceTest`, `QuickCompleteTimeoutServiceTest` and the new timeout IT, the booking-completion tests, `TimelineQueryService*`) and the two Vitest specs; GitHub CI is the full gate.
- **Booking fixtures** (memory *Skillars Booking Fixture CHECK*): `chk_bkg_end_after_start`; derive both instants from one `Instant.now()` in any new booking fixture.
- **AC2 error shape:** the wrap-up wizard (`WrapUpSequence.vue`) currently only branches on `concurrentModification`; a rejected drill id will show the generic submit error. That is acceptable here (the wizard only offers the coach's own accessible drills, so this is a defence against a crafted request). Do not build new wizard UI.

### Project Structure Notes

- Backend under `src/main/java/com/softropic/skillars/platform/{booking,session,admin,development,config}`; frontend under `src/frontend/src/stores`. No new module, no new migration, no new dependency, no new endpoint.
- `GdprExportService` lives in `platform.admin.service`; `PlayerProfileRepository` is in `platform.security.repo` (the class already imports `security.repo.UserRepository`).

### Testing standards

- Unit tests with the existing Mockito style in `GdprExportServiceTest` / `GdprErasureServiceTest`; ITs on real Postgres for the timeout and drill-ownership paths.
- Every new **negative** case (rejections, nulled `referenceId`, the `userId != profileId` case, the two-child loop test) must fail on the pre-change code; positive cases (own PRIVATE / PLATFORM accepted, author/player still see `referenceId`) are regression guards and pass before and after. State in the Dev Agent Record which negative cases you mutation-checked.

### References

- `deferred-work.md` sections: 12.6 dev (2026-10-22), 12.8 review (2026-10-23), 12.9 review (2026-10-23), 12.10 review (2026-10-23), deferred-158 review (2026-10-22)
- `QuickCompleteTimeoutService.java`, `BookingCompletionService.java:146-221,394-427`, `HomeworkAssignmentService.java:57-115`, `GdprExportService.java:114-140`, `TimelineQueryService.java:30-93`, `TimelineEventListener.java:229-253`, `ConfigBounds.java:185-190`, `ConfigStartupAssertion.java`, `CoachAccessWindow.java`, `DrillSuggestionService.java:145-148`, `ApiAdvice.java:388-393`, `sessionBuilder.store.js:151-154`, `SkillsRadarAssessmentPanel.vue:177`
- Project facts: `_bmad-output/project-context.md`

### Story Review Disposition, round 2 (`story-review.md`, 2026-10-23)

Applied F1-F9 after re-verifying F1, F2, F3 and F6's seed value directly in source (two hand-built `BookingCompletionService` constructors; blank/null returns silently at `:419`; the hoisted `metadata.get` would NPE; 24h seed). F4 adopted as the allowlist form (identical outcome today, safe by default). F8 rests on Mockito's documented strict-stubs behaviour, not execution, and is written as guidance. Killed by the reviewer and left alone: the "~18 keys" challenge, the unverified-IT challenge, and the "fifth listener" point (informational).

### Story Review Disposition, round 1 (`story-review.md`, 2026-10-23)

Applied after re-verifying each finding against source: C1 (privacy claim narrowed, plan path moved to exclusions + ledger), C2 (rule source is `DrillSuggestionService.allCandidates`), C3 (AC3 rationale corrected; matches memory *Skillars ID Spaces*; I had repeated the refuted "independent sequences" claim), C4 (AC5 reduced to a ledger closure; the story's own `failFast` argument also contained a false claim, that an absent value on a failFast key is a boot violation, which `HAS_CODE_DEFAULT` keys are exempt from), C5 (H5 closed as equivalent, per the deferred-155 precedent and the always-null `coach_id` on HOMEWORK_ASSIGNED), C6, C7, C8, C9, C10, C11, C12, and the BEFORE_COMMIT payout rationale (M1b). No finding was rejected. Not re-checked by me: `GdprErasureIT:~2188` and the `CoachAccessWindowTest`/`ReportGenerationServiceTest` stubs (now moot, since AC5 makes no code change). C9's null-binding outcome is unproven, so AC2 rejects nulls explicitly instead of relying on it.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5-5

### Debug Log References

- First run of the new drill-ownership IT failed only because `HttpTestClient` throws `HttpClientErrorException` on 4xx; the 404 itself was correct. Test changed to `assertThatThrownBy`.
- `deferred-work.md` line numbers drifted from the story's citations; items were located by text.

### Completion Notes List

- **AC1:** new package-private `HomeworkDrillIds.parse(ObjectMapper, String)` (null/blank silent, malformed WARN) replaces the private `deserializeHomeworkDrillIds`; used by `confirmCompletion` and `QuickCompleteTimeoutService` (now injects `ObjectMapper`). Tests: `HomeworkDrillIdsTest` (3), a timeout unit test asserting drill ids on the event, and an IT (`quickCompleteTimeout_withoutParentConfirm_...`) that backdates `created_at` 30h inside `transactionTemplate`, calls the bean directly, and awaits `homework_assignments` + the `HOMEWORK_ASSIGNED` row.
- **AC2:** `validateHomeworkDrills` runs after `verifyStatus`, before `new SessionCompletionData()`. Nulls rejected first, then `distinct()`, one `findAllById`, ACTIVE PLATFORM or own ACTIVE PRIVATE; otherwise `ResourceNotFoundException`. Deduplicated list is persisted and published in both modes. `DrillRepository` added to the constructor; the two hand-built constructor calls (`BookingCompletionServiceTest`, `SessionPauseResumeServiceTest`) updated. Unit tests for own-PRIVATE/PLATFORM ok, foreign PRIVATE, unknown, archived, `[null]`, `[a,a]`; ITs for foreign PRIVATE (404, no SCD row, status unchanged) and own PRIVATE accepted.
- **AC3:** PLAYER branch now `playerProfileRepository.findByUserId(userId)` then `findAllByPlayerId(profile.getId())`; no profile adds nothing. Described as id-space hygiene, not a leak. `GdprExportServiceTest` moved to a distinct profile id (7777) and gained a `never()` case and a no-profile case.
- **AC4:** authorship computed once per row in `toResponse` (null-metadata guarded) and gates both metadata and `referenceId`; coach viewers get `referenceId == null` unless authoring coach. Frontend re-grep for `referenceId` over `src/frontend/src`: only `CreditStatementPage.vue` (unrelated). 4 new unit tests. Mutation-checked: reverting the `referenceId` line fails 4 tests. H5 closed as verified-equivalent, no code change.
- **AC5:** Javadoc sentence on `CoachAccessWindow`; no behaviour change.
- **AC6:** store checks `errorMsg.errorKey`; spec body shape corrected; negative case added. Prettier, ESLint clean; Vitest 9/9.
- **Code review (2026-10-23, /bmad-code-review), mutation checks run by the reviewer:** (a) AC2 `!usable.containsAll(distinct)` → `usable.isEmpty()` fails the new `submitWrapUp_homework_validPlusForeignPrivate_wholeRequestRejected`; (b) AC3 reverted to `findAllByPlayerId(userId)` fails 3 `GdprExportServiceTest` cases (`PotentialStubbingProblem`, incl. `buildBookings_player_queriesBookingsByProfileIdNotUserId`); (c) AC6 reverted to `data.helpCode` fails both the positive gate case and the negative `helpCode`-equals-key case (2/9). All restored; targeted suites green after patches (`BookingCompletionServiceTest` 43, `SessionPauseResumeServiceTest` 8, `GdprExportServiceTest` 5; Vitest 9/9).
- **AC7:** `erase_parentUser_firstChildBookingLockTimeout_secondChildStillErased` (child A first). Mutation-checked: an inserted `break` in the PARENT `catch (BookingSchemaLockException)` fails it; source restored (no diff on `GdprErasureService`).
- **Task 8:** H1-H8 tagged in `deferred-work.md`; three new bullets appended (plan-path private drills, erasure marker on delayed QUICK path, pending QUICK rows).
- **Validation run (targeted only, no full `mvn verify`):** `HomeworkDrillIdsTest`, `QuickCompleteTimeoutServiceTest` (5), `BookingCompletionServiceTest` (42), `SessionPauseResumeServiceTest` (8), `GdprExportServiceTest` (5), `GdprErasureServiceTest` (32), `TimelineQueryServiceTest` (15); failsafe `SessionCompletionResourceIT` (32) and `PlayerTimelineResourceIT` (21); Vitest `sessionBuilderStoreSpec` (9). Negative cases mutation-checked: AC4 and AC7 only; AC1/AC2 negatives fail on pre-change code by construction (no gate / `List.of()`), not executed against the old code.

### File List

- src/main/java/com/softropic/skillars/platform/booking/service/HomeworkDrillIds.java (new)
- src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java
- src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java
- src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java
- src/main/java/com/softropic/skillars/platform/development/service/TimelineQueryService.java
- src/main/java/com/softropic/skillars/platform/development/service/CoachAccessWindow.java
- src/frontend/src/stores/sessionBuilder.store.js
- src/frontend/src/stores/__tests__/sessionBuilderStoreSpec.js
- src/test/java/com/softropic/skillars/platform/booking/service/HomeworkDrillIdsTest.java (new)
- src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java
- src/test/java/com/softropic/skillars/platform/booking/service/SessionPauseResumeServiceTest.java
- src/test/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutServiceTest.java
- src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java
- src/test/java/com/softropic/skillars/platform/admin/service/GdprExportServiceTest.java
- src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java
- src/test/java/com/softropic/skillars/platform/development/service/TimelineQueryServiceTest.java
- _bmad-output/implementation-artifacts/deferred-work.md
- _bmad-output/implementation-artifacts/sprint-status.yaml
- _bmad-output/implementation-artifacts/skillars-deferred-159-homework-timeout-drop-gdpr-export-id-space-and-epic-12-integrity-hardening.md

### Change Log

- 2026-10-23: Implemented deferred-159 (AC1-AC7) and ledger bookkeeping; status ready-for-dev -> review.
- 2026-10-23: Code review (/bmad-code-review): 5 patches applied (mixed homework-list rejection test, import tidy, ledger heading/newline, sprint-status comment, mutation notes); status review -> done.
