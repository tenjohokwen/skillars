# Story skillars-deferred-171: Development-data erasure completeness (guards on every development write, homework rows, the last recap column)

Status: ready-for-dev

<!-- CREATED 2026-11-04 (/bmad-create-story equivalent, written by hand), right after skillars-deferred-170
     (#446, merged debd4dca) and the Dependabot batch #341-#348. Bundles the five still-open GDPR items
     skillars-deferred-162 (2026-10-26) and skillars-deferred-163 left in deferred-work.md.
     REVISED 2026-11-05 after /mto-story-review (report: story-review.md, checked against 576e96d6): D1 corrected,
     D3 now an owner exemption, D4 redesigned (the orphaned-PDF race), D5/D6 added (setTargets, radar preferences),
     V230 / test-constructor / dispatcher traps added. The owner decided D1=A, D3=A (exemption), D5=A. -->

## Story

As a **parent or player who asked Skillars to erase a player's development data**,
I want **no coach action or background job to write that data back afterwards, and erasure to remove
every development row it leaves behind today**,
so that **"erased" stays erased, and no personal data outlives the request**.

## Ledger mapping (so future passes do not re-pick this)

`deferred-work.md`, sections "Deferred from: skillars-deferred-162 (2026-10-26)" and "Deferred from:
skillars-deferred-163 (2026-10-26)":

| Ledger bullet (line at 3f6afc94) | AC |
|---|---|
| Coach-initiated development writes have no erasure check (`:3527`) | AC1, AC2 |
| `SluCalculationService.onBookingCompleted` has no erasure check (`:3528`) | AC3 |
| `session.homework_assignments` is not deleted by GDPR erasure (`:3529`) | AC4 |
| SCD `homework_drill_ids` survives `anonymizeByPlayerId` (`:3530`) | AC5 |
| `voice_note_text` drop, step 3 of 3 remains (`:3537`) | AC6 |

**Not in this story** (same section, left in the ledger): HOMEWORK_ASSIGNED check-then-insert (`:3532`,
serialized per player since deferred-162, a unique index is a separate risk), a busy profile lock drops a
development write (`:3533`, a deliberate tradeoff, **extended by this story's new guards, see D2**), and the
chargeback/refund webhook gap (needs its own design). **New ledger bullet written by this story (AC7):**
player-linked tables erasure still does not clear: `development.radar_composite_dlq` and
`booking.session_completion_data.plan_blocks_snapshot`.

## Background (as the code stands at 576e96d6; code content is 3f6afc94)

- **The guard.** `DevelopmentDataErasureGuard.isErasedUnderLock(playerId, caller)`
  (`security/service/DevelopmentDataErasureGuard.java`) takes the `player_profiles` row lock with NOWAIT
  (`findByIdForUpdate`, lock timeout 0) plus `PessimisticLockRetryer`, refreshes, and returns `true` when
  `development_data_erased_at` is set. It returns **`false`** (it does not throw) when the player row does not
  exist. When the lock stays busy it rethrows `PessimisticLockingFailureException` after 8 attempts (~3.2 s).
  It is `@Transactional(propagation = MANDATORY)`: it must run inside the caller's write transaction, so the
  lock is held until that write commits. Erasure stamps the marker under the same lock in the transaction
  that deletes the rows (`GdprErasureService.deletePlayerDevelopmentDataInDedicatedPool`, tombstone at
  `:1820`). Callers today: `TimelineEventListener.writeSessionCompleted` (`:156`), `writeHomeworkAssigned`
  (`:252`), `HomeworkAssignmentService.assignOne` (declared `:131`, guard call `:132`), and `BookingService`
  (`:645`). `RadarCompositeCalculationService.recalculateComposite` has its own equivalent check (`:230`, a
  plain field check after its own lock).
- **Radar.** `RadarAssessmentService.submitAssessment` (`:47`, `@Transactional` `:46`) saves the entries
  (`:89`) and publishes `RadarEntrySubmittedEvent` (`:92`). Its timeline row comes from
  `TimelineEventListener.onRadarEntrySubmitted` (`:275`, AFTER_COMMIT, `@Async`) through
  `self.writeTimelineEvent` (5-arg, `:289-290`, `REQUIRES_NEW`), which delegates to the 6-arg overload (`:300-301`,
  also `REQUIRES_NEW`, but reached by self-invocation so only the 5-arg's transaction is in effect). Neither has
  a guard. Nothing calls the 6-arg directly from outside.
- **Report.** `ReportGenerationService.generateReport` (`:155`, `@Transactional` `:153`) builds the PDF in
  memory, saves a `PENDING_UPLOAD` row (`:235`) and publishes `ReportGeneratedEvent`. `onReportGenerated`
  (AFTER_COMMIT, `@Async("reportExecutor")`, `@Transactional(REQUIRES_NEW)`, method `:254`) is **one transaction**
  that uploads the PDF, flips the row to `READY` (`:258`), writes the PERFORMANCE_REPORT timeline row through the
  unguarded 5-arg `writeTimelineEvent` (`:266`, in a swallowing `try/catch`) and notifies the parent (`:276`);
  the transaction stays open until after the notification. `updateStatusAndStorageKey` returns the update
  count (`PerformanceReportRepository.java:32`); nothing reads it. **For a really erased player
  `generateReport` never reaches a new guard:** erasure deletes the SLU and timeline rows, the coach's
  last-session lookup (`CoachPlayerLastSessionResolver.resolve`) reads exactly those, so
  `lastSession == null` and `generateReport` throws `AccessDeniedException` (403) near `:200`, before the PDF is built.
  The new guard there is reachable only when erasure commits after that read and before `saveAndFlush`.
- **SLU.** `SluCalculationService.onBookingCompleted` (AFTER_COMMIT, `@Async`, no transaction) computes the
  rows and hands them to `SluPersistenceDispatcher.dispatchSluPersistence` (`@Async("sluRetryExecutor")`),
  which calls `SluPersistenceRetrier.saveSluWithRetry` (`:125`) and then `SnapshotPersistenceRetrier`. The
  dispatcher (`:70-92`) skips the snapshot write only for `FAILED` and `ALREADY_PERSISTED`; any other outcome
  falls through to `writeAllWithRetry`.
  - `SluPersistenceRetrier` is deliberately **not** `@Transactional` (`:95`): its catch for the V47 unique
    collision (`DataIntegrityViolationException` whose cause is a Hibernate `ConstraintViolationException`
    named `uq_player_skill_stats_session_skill`) works only because `sluRepository.saveAll` (`:153`) opens and
    commits its own transaction, so the violation surfaces inside the `try`. `saveSluWithRetry` is
    `@Retryable` on `DataAccessException` (3 attempts); `PessimisticLockingFailureException` is a
    `DataAccessException`, and the `@Recover` returns `FAILED` ("rows lost ... manual recovery needed").
  - The weekly snapshot goes through `SnapshotBatchWriter.writeAll` (`repo/SnapshotBatchWriter.java:28`,
    `@Transactional`), which delegates to `writeAllDeltas` (`:42`, `@Transactional`). The outbox re-drive
    (`SluSnapshotOutboxHandler:44`) also calls `writeAllDeltas`, with no catch: an exception there is retried by
    the outbox processor and eventually raises `[OUTBOX_STUCK]`.
- **Erasure.** The fixed statements are 6 (group 1) + 3 (group 2, radar deletes, `:1722-1729`) + 3 (group 3,
  `:1732-1744`) = 12; the Javadoc counts "12 fixed delete/scan statements" (`:1583`). Nothing deletes
  `session.homework_assignments` (player id, coach id, drill id, booking id per row) nor
  `development.coach_radar_preferences`. `homework_completions.assignment_id` references assignments
  `ON DELETE CASCADE` (`V138:4567`); no other FK references `homework_assignments`, and its `player_id` has no FK.
- **Coach writers into tables erasure empties, with only a relationship check:**
  `SluTargetService.setTargets` (`:36-50`, `@Transactional`) upserts/deletes `player_slu_targets` (erased in
  group 1); `RadarDisplayService.savePreferences` (`:75-95`, `@Transactional`) upserts `coach_radar_preferences`
  (**not** erased today). The coach's booking relationship survives erasure, so both accept writes for an erased
  player.
- **Recap anonymisation.** `SessionCompletionDataRepository.anonymizeByPlayerId` (`:60`, native) nulls
  `voice_note_text, what_we_did, whats_next, what_went_well`; it leaves `homework_drill_ids`.
  `anonymizeByCoachId` (`:69`) nulls the **same four columns**. `voice_note_text` is unmapped by JPA since
  skillars-deferred-165 (step 2, #304, merged 604f7e67, when the latest migration on disk was V170); the two
  native queries and a comment in `GdprErasureService.java:470-476` still name it, and so does
  `BookingCompletionService.java:707` (a `{@code voiceNoteText}` Javadoc comment).

## Decisions (owner-approved 2026-11-05 unless marked)

- **D1 (owner: A): radar answers 404; the report's normal answer stays 403.** `submitAssessment` for an erased
  player throws `ResourceNotFoundException("Player not found", "player_profile")` (404): the coach's
  relationship survives erasure, so radar reaches the guard. `generateReport` for a really erased player
  already answers 403 (no session history, see Background), and the new guard there only fires in the race
  window, answering the same 404. The two paths differ (radar 404, report 403 in the normal case); that is
  accepted. **Do not claim "the same answer an unknown player gets":** an unrelated or unknown player gets 403
  from the relationship check, which runs first. No dedicated 409 code, no new translation, no frontend work.
- **D2: background writes skip quietly** (the guard logs INFO): timeline rows, SLU rows, weekly snapshots. **A
  busy profile lock is not skipped, it is the ledger's accepted tradeoff (`:3533`), now extended:** the guard
  throws `PessimisticLockingFailureException` after ~3.2 s. Consequences, all accepted and to be written into
  the ledger bullet and the Javadocs: synchronous submit and report answer 409 "busy" (`ApiAdvice:711`); the
  radar timeline row (`TimelineEventListener` catch `:283`) and the report timeline row (swallowing `try/catch`)
  are dropped; **an SLU detail save for a player who is not erased can now be lost** after the 3 retried
  attempts (`@Recover` logs ERROR, "manual recovery needed"); a weekly-snapshot write is retried and then falls
  to the outbox (not lost).
- **D3 (owner: A, explicit exemption, same call as V229): drop `voice_note_text` now, in `V230`.** The last code
  references (the two native queries) are removed in the same change as the drop, so rule 2 of
  `migration-conventions.md` is **not** met literally. This is an owner-approved exemption on the same grounds as
  V229 (PROD has never been deployed, `migration-rebaseline.md`). Keep `-- migration-lint: drop-prepared-in: V170`
  so the lint passes, but the header comment must state the exemption and the sequencing honestly. Rolling-deploy
  hazard (old pods running the old anonymise queries during the migration window fail with "column does not
  exist") is accepted for the same reason; list V230 in the provisioning checklist's "prod is not live" list.
  Any legacy coach-authored text in the column goes with it.
- **D4 (redesigned): an orphaned report PDF is deleted, and the upload step is serialized against erasure by the
  guard, not by the update count.** The previous design (enqueue only when `updateStatusAndStorageKey` returns 0)
  does not close the race: erasure's `findStorageKeysByPlayerId` (WHERE `storage_key IS NOT NULL`,
  `GdprErasureService:1741`) can run while the row is still `PENDING_UPLOAD` with a null key and skip it;
  `onReportGenerated` then updates to READY (returns 1); erasure's DELETE (`:1742`) blocks on that row lock and
  deletes the committed row after the handler commits; the PDF stays in storage with no key anywhere. See the
  Design, point 3.
- **D5 (owner: A): the two coach writers are guarded in this story, and erasure deletes radar preferences.**
  `SluTargetService.setTargets` and `RadarDisplayService.savePreferences` get the guard (404, as D1), and group 2
  of erasure also deletes `coach_radar_preferences`, so a guard is not a half fix. The other uncleared
  player-linked tables (`radar_composite_dlq`, `plan_blocks_snapshot`) are NOT in scope: one new ledger bullet.

## Design (pinned; do not re-derive)

1. **Radar and the two coach writers (AC1).**
   - `submitAssessment`: after the relationship and tier checks and before `radarRepository.saveAll` (`:89`):
     `if (erasureGuard.isErasedUnderLock(playerId, "RadarAssessmentService.submitAssessment")) throw new
     ResourceNotFoundException("Player not found", "player_profile")`. The method is `@Transactional`, so MANDATORY
     is satisfied and the lock is held until the entries commit.
   - `SluTargetService.setTargets`: same guard, after `requireCoachPlayerRelationship`, before the loop.
   - `RadarDisplayService.savePreferences`: same guard, after `requireCoachPlayerRelationshipForRead`, before the
     `findBy...`/`save`.
   - Each class gets the guard as a constructor argument (list the hand-built test sites in the Dev Notes).
2. **Timeline rows (AC1, AC2).** Put the guard at the top of the **six-argument** `writeTimelineEvent` (`:301`),
   returning without a write when erased. The 5-arg overload's own `REQUIRES_NEW` transaction is the one in effect
   when it delegates (self-invocation), and that is enough for MANDATORY. Both callers (radar `:277`, report
   `ReportGenerationService:266`) reach the 6-arg through the 5-arg. `writeSessionCompleted` and
   `writeHomeworkAssigned` keep their own guards; they call `saveTimelineEvent` or a repository insert, not
   `writeTimelineEvent`. Also fix the stale comment at `:224` (it says `writeHomeworkAssigned` uses the 5-arg).
3. **Report (AC2).**
   - `generateReport`: the guard before `reportRepository.saveAndFlush` (`:235`), throwing as in D1. Place it
     **after** the PDF is built (keep the lock short) and before the save.
   - `onReportGenerated`: **remove its own `@Transactional(REQUIRES_NEW)`** (keep `@TransactionalEventListener`
     and `@Async("reportExecutor")`) and restructure into three separate transactions, using a
     `TransactionTemplate` (`REQUIRES_NEW`) for the DB steps:
     1. Upload the PDF with no transaction open (as today, minus the held transaction).
     2. **Step A (template transaction):** `isErasedUnderLock(event.playerId(), "ReportGenerationService.onReportGenerated")`
        first, **then** `updateStatusAndStorageKey(READY, key)`.
        - If erased, **or** the update returns 0: `BlobDeletionOutboxSupport.enqueue(List.of(key))` then
          `requestDrainAfterCommit()` in this same transaction, log at INFO, return before the timeline row and the
          notification.
        - Because Step A holds the `player_profiles` lock until it commits, erasure (which holds the same lock for
          its whole transaction) cannot interleave: if erasure committed first the guard sees the marker and the key
          is enqueued; if Step A committed first, erasure's key scan sees a committed READY row with its key.
        - The enqueue branch must sit **outside** the existing `try`; an exception from `enqueue` inside it would be
          caught by the upload catch, mark UPLOAD_FAILED and leave the blob orphaned.
        - If Step A fails (for example `PessimisticLockingFailureException` after the upload): log ERROR, enqueue
          the key for deletion in a fresh template transaction, and best-effort mark the row `UPLOAD_FAILED`.
        - The `UPLOAD_FAILED` branch after a failed upload: update in its own template transaction; a 0 there needs
          no blob action (nothing was stored).
     3. **Step B (after Step A commits, no outer transaction):** the PERFORMANCE_REPORT timeline row via the
        guarded `writeTimelineEvent` and `notifyParent`, each in its existing swallowing `try/catch`. Step B has
        no outer lock, so the timeline guard cannot block on its caller (this replaces the Dev Notes' old
        "no self-deadlock" reasoning).
   - `development` may call `filestorage`: `ReportGenerationService` already imports it; module rules are
     pairwise tests (only `MarketplaceDoesNotDependOnPaymentTest` exists), none covering this pair.
     `ReportGenerationService` gains `BlobDeletionOutboxSupport` and a `PlatformTransactionManager` (or a
     `TransactionTemplate`) field.
4. **SLU rows (AC3).** In `SluPersistenceRetrier.saveSluWithRetry`, run the guard and `saveAll` in one
   `TransactionTemplate` (`REQUIRES_NEW`) transaction: `isErasedUnderLock(rows.get(0).getPlayerId(), ...)` then
   `saveAll`. Keep `existsBySessionId` (`:144`) outside, before the template. When erased, return a new outcome
   `SluSaveOutcome.PLAYER_ERASED`. **`SluPersistenceDispatcher.dispatchSluPersistence` gets an explicit
   `PLAYER_ERASED` branch** that logs at INFO and returns before `snapshotPersistenceRetrier.writeAllWithRetry`
   (today any other outcome falls through to the snapshot write); update the "tri-state" prose there and
   `SluSaveOutcome`'s Javadoc to four states. **Keep the V47 catch outside `template.execute(...)`:** the constraint
   now fires at the template's flush and commit, and the exception leaves `execute` after the rollback with the same
   translation (`DataIntegrityViolationException` caused by the named `ConstraintViolationException`), so the catch
   still sees it. Do **not** annotate the class or method `@Transactional`, and update its Javadoc (`:55-57`,
   `:95-97`) to say why a template is fine and an annotation is not. All rows in one call share one player
   (`SluCalculationService:179` builds every row from one event); assert it (`IllegalStateException` otherwise).
   The retrier needs a `PlatformTransactionManager` (see `GdprErasureService`'s `requiresNewTemplate` for the
   pattern). Document D2's lock-busy consequence in the class Javadoc.
5. **Weekly snapshots (AC3).** In `SnapshotBatchWriter.writeAllDeltas` (`:42`, `@Transactional`), guard each
   distinct `playerId` among the deltas, **in sorted order**, and drop that player's deltas when erased. Return
   normally (so an outbox re-drive completes instead of looping). This covers `writeAll` (it delegates,
   `:28-32`) and the outbox re-drive. `SnapshotBatchWriter` is a `@Transactional` writer component in the `repo`
   package, not a Spring Data interface; no `development.repo` class imports `security.service` yet, and no test
   forbids it. Inject the guard there (the guard's MANDATORY propagation needs exactly this transaction).
6. **Erasure deletes (AC4).**
   - **Group 3** (`:1732-1744`): after `homeworkCompletionRepository.deleteAllByPlayerId`, add
     `homeworkAssignmentRepository.deleteAllByPlayerId(playerId)` (new bulk `@Modifying` JPQL delete, same shape as
     the completions one, `HomeworkCompletionRepository.java:30-32`). Keep the explicit completions delete first.
     Group 3 goes from 3 to **4** statements (`nextGroupLockTimeoutSecondsOrThrow(..., 3, ...)` at `:1738`).
   - **Group 2** (`:1722-1729`): add `coachRadarPreferenceRepository.deleteAllByIdPlayerId(playerId)` (new bulk
     `@Modifying` JPQL delete on `CoachRadarPreference`, keyed on the embedded id's `playerId`) after the three radar
     deletes. Group 2 goes from 3 to **4**.
   - The fixed total becomes **14**: update "12 fixed" in the Javadoc (`:1583`), `:1585` (the list), `:1595` and `:1599`
     ("12 + M"), `:1663`, `:1669`, `:1747-1750`, and `ConfigBounds.java:644`. No test pins the group counts
     (`GdprErasureServiceTest` has comments only at `:331-335`; with a 20 s budget 20/4 = 5, still >= the 2 s
     minimum, so its arithmetic stays valid).
   - `GdprErasureService` gains two repository fields, which breaks `GdprErasureServiceTest:161` (a positional
     `new GdprErasureService(...)`): add the two `@Mock`s and arguments.
7. **Recap drill ids (AC5).** `anonymizeByPlayerId` also sets `homework_drill_ids = NULL`. Not
   `anonymizeByCoachId`: drill ids are the coach's own library references, not the player's data.
8. **`voice_note_text` (AC6).** `V230__drop_session_completion_data_voice_note_text.sql`, **with a leading header
   comment block** (the lint requires one for any drop: `MigrationLint.java:740`, `BARE_DROP_NO_HEADER`) that states
   the D3 exemption and the sequencing; then `SET LOCAL lock_timeout = '5s';`; then
   `-- migration-lint: drop-prepared-in: V170` directly above
   `ALTER TABLE booking.session_completion_data DROP COLUMN IF EXISTS voice_note_text;` (copy the shape of
   `V229__drop_marketplace_session_packs.sql`). In the same change:
   - remove `voice_note_text = NULL` from both native queries and reword their comments
     (`SessionCompletionDataRepository.java:47-60`, `:62-69`) and `GdprErasureService.java:470-476`;
   - reword `BookingCompletionService.java:707` (it names `voiceNoteText`; the lint's reference scan,
     `MigrationLint.referencesIn`, matches a file containing the table name and either `voice_note_text` or
     `voiceNoteText`, comments included, and that file also contains `"session_completion_data"` at `:574`/`:594`);
   - **rewrite the ITs that touch the column:** `GdprErasureIT` (INSERT at `:725`, `:733`, `:964`, `:972`;
     SELECT/assert at `:745-756`, `:984-995`, including the "Unrelated voice note" survival check) and
     `SessionCompletionResourceIT:291-293`. Keep the assertions that the other three recap columns are nulled and
     another player's rows are untouched;
   - re-grep `src/main` (not `db/migration`) for both spellings until the lint's reference scan is clean.
   Take the next free number at dev time (V230 at 3f6afc94).

## Acceptance Criteria

**AC1: a radar submission, a target change, or a preference save for an erased player writes nothing.**
- `submitAssessment`, `SluTargetService.setTargets` and `RadarDisplayService.savePreferences` for a player with
  `development_data_erased_at` set throw 404 `player_profile` and save nothing (unit tests with the guard mocked to
  `true`: `verify(never())` on `saveAll` / `upsert` / `save` and, for radar, on the event publish).
- `writeTimelineEvent` for an erased player writes no `player_timeline_events` row (IT: stamp the marker, call it
  through the proxy, count 0).
- A not-erased player behaves as today (existing tests stay green; a lenient `false` stub is unnecessary because an
  unstubbed Mockito `boolean` is `false`).
- At least one of the three guarded endpoints has a WebMvc/IT test that the erased player answers 404 end to end.

**AC2: a report for an erased player writes nothing, and no orphaned PDF survives.**
- `generateReport` with the guard mocked `true` throws 404 and saves no `performance_reports` row (unit test). The
  AC states the race path only; the normal erased-player answer is the existing 403, add or confirm a test for it.
- `onReportGenerated` Step A when the player is erased **while the row is still `PENDING_UPLOAD`** (IT: generate a
  report, stamp the marker and delete the rows as erasure would, then run the handler against a real
  `ReportGenerationServiceIT`/`BaseStorageIT` storage): the PDF key is in the blob-deletion outbox, no timeline row is
  written, the parent is not notified.
- A second case: erasure commits **after** Step A (READY row with its key): the key scan finds it and enqueues it
  (assert via the existing erasure path, not by re-implementing it).
- The normal path still writes READY, the timeline row and the notification (existing tests, updated for the
  restructured transaction scopes).
- If Step A fails with a lock-busy exception after the upload, the key is enqueued for deletion and the row is not
  left silently `PENDING_UPLOAD` (unit test).

**AC3: SLU and weekly snapshots skip an erased player.**
- `saveSluWithRetry` for an erased player saves no `player_skill_stats` row and returns `PLAYER_ERASED`; the
  dispatcher then writes no snapshot (unit tests, including the dispatcher branch).
- `writeAllDeltas` drops an erased player's deltas, still applies another player's, and returns normally.
- The V47 duplicate-delivery case still resolves as the idempotent no-op through the new template (the real-DB
  collision test around `SluCalculationServiceIT:294` stays green; if it uses `@MockitoBean FileStorageService`,
  do not add a new mock field name, it is part of the context-cache key).
- `SluPersistenceRetrierTest` cases that expect `SAVED` stub the mocked template to run its callback.

**AC4: erasure deletes the player's homework assignments and radar preferences.**
- After a player erasure, `session.homework_assignments` has no row for that player, `homework_completions` none
  either, and `development.coach_radar_preferences` none (extend `GdprErasureIT` or its development-data test with
  seeded assignment, completion and preference rows; another player's rows untouched).
- Groups 2 and 3 count 4 statements each; the literals listed in Design 6 are updated; `GdprErasureServiceTest`
  compiles with the new constructor arguments.

**AC5: anonymisation clears the recap's homework drill ids.** After `anonymizeByPlayerId`,
`homework_drill_ids` is NULL for that player's rows and untouched for another player's (IT).

**AC6: `voice_note_text` is gone.** A fully migrated test database has no such column (a small new IT or helper that
reads `information_schema`; no existing IT asserts column absence for this table).
`MigrationConventionLintTest` passes with V230 (header comment present, marker present, no `src/main` reference
left) and fails by hand with the marker removed (check once, do not commit). Both anonymise queries pass their
rewritten ITs.

**AC7: docs, ledger, checklist.**
- `docs/dev-docs/development/index.html` (or wherever the guard is described): one line listing the guarded writes
  (now including radar, timeline, report, SLU, snapshots, SLU targets, radar preferences).
  `docs/dev-docs/database/index.html` if it lists `session_completion_data` columns; `docs/dev-docs/booking/index.html`
  mentions `voice_note_text` (docs only, not a lint hit, but fix it). Rebuild the search index
  (`node docs/dev-docs/tools/build-search-index.mjs`); `check-docs` passes.
- `docs/deployment/provisioning-checklist.md`: a V230 entry (drop of a column whose last reader left in #304 and
  whose last references leave in this change; add to the "prod is not live" list per D3).
- Ledger: delete the five mapped bullets; extend the `:3533` bullet with D2's consequences (radar timeline, report
  timeline, SLU detail rows); add the one new bullet for `radar_composite_dlq` and `plan_blocks_snapshot`.

**AC8: `/pr-review`** on the final commit, run to a clean round or to an owner decision. Then open the PR
(not `no-build`).

## Tasks / Subtasks

- [x] T1 (AC1): radar guard; `setTargets` guard; `savePreferences` guard; 6-arg `writeTimelineEvent` guard; stale
      comment `:224`; tests.
- [x] T2 (AC2): report guard in `generateReport`; restructure `onReportGenerated` (drop its `@Transactional`,
      Step A guard-then-update with the enqueue branch outside the upload `try`, Step B timeline + notify);
      inject `BlobDeletionOutboxSupport` and a transaction manager; tests (including the race IT and the
      lock-busy case).
- [x] T3 (AC3): SLU template + `PLAYER_ERASED` outcome + dispatcher branch; snapshot guard (sorted); Javadocs
      (`:55-57`, `:95-97`, `SluSaveOutcome`, dispatcher prose); tests.
- [ ] T4 (AC4): `deleteAllByPlayerId` on homework assignments and radar preferences; groups 2 and 3 counts;
      all "12" literals; `GdprErasureServiceTest` constructor; IT.
- [ ] T5 (AC5): `homework_drill_ids = NULL`; IT.
- [ ] T6 (AC6): V230 with header, marker and exemption; both queries and all four comments; rewrite the two ITs;
      schema assertion; lint check by hand.
- [ ] T7 (AC7): docs, checklist, ledger.
- [ ] T8 (AC8): `/pr-review`, PR.

## Dev Notes

- Work in the worktree `../skillars-deferred-168`, branch
  `skillars-deferred-171-development-data-erasure-completeness`. No local `mvn verify` (CI is the gate);
  targeted tests with `-DskipFrontend`. Add files by name.
- **Hand-construction sites that need the new constructor arguments** (re-grep before editing, the list is from the
  review): `RadarAssessmentServiceTest:54`, `ReportGenerationServiceTest:99` and `:817`, `SluPersistenceRetrierTest:35`,
  `SluRetrierProxyRetryTest:69` (a `@Configuration` that needs a transaction manager or template bean),
  `SnapshotBatchWriterTest:28`, `GdprErasureServiceTest:161`; plus whatever builds `SluTargetService` and
  `RadarDisplayService` by hand. `TimelineEventListenerTest` already has the guard.
- **Unit tests that mock the guard:** an unstubbed Mockito `boolean` answers `false`, which is "not erased", so
  existing tests keep working without a stub.
- **Test contexts:** do not add a `@MockitoBean`/`@MockitoSpyBean` to an IT base class (context-cache gate, see
  the test-context-cache notes). Stamp `development_data_erased_at` with `jdbcTemplate` inside a
  `transactionTemplate` (bare IT writes do not commit: hikari auto-commit is off). There is no stub `FileStorage`:
  `ReportGenerationServiceIT` extends `BaseStorageIT` (real MinIO). `onReportGenerated` is `@Async`: use the event
  path the existing IT uses (around `:148`) with an await, not a direct call through the proxy.
- **Lock order:** every new guard call takes `player_profiles` first, then writes, the same order erasure uses.
  Do not call the guard after a write to the same transaction's other development rows.
- **Why Step A is its own transaction.** `writeTimelineEvent` is `REQUIRES_NEW` with a NOWAIT guard. If a caller
  already holds the `player_profiles` lock (Step A does, by design), the inner guard would block on its own caller
  every time and the row would always be lost. That is why the timeline write moves to Step B, after Step A
  commits. (The old reasoning "the outer transaction holds no profile lock" no longer applies once Step A takes it.)
- **Failure budget of the guard:** ~3.2 s of NOWAIT retries, then `PessimisticLockingFailureException`. Callers
  must decide (D2); do not let a new guard call silently swallow it.

### References

- `deferred-work.md` lines in the Ledger mapping.
- `skillars-deferred-162-*.md`: AC5 (the guard's design) and its Completion Notes.
- `docs/deployment/migration-conventions.md` rule 2; `V229__drop_marketplace_session_packs.sql` for the drop shape and
  the owner-exemption header.
- `story-review.md` (2026-11-05, against 576e96d6): the findings this revision applies (F1-F8).

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-04: created (ready-for-dev).
- 2026-11-05: revised after /mto-story-review. Owner decisions: D1=A (radar 404, report keeps its 403, rationale
  corrected), D3=A (V230 as an explicit owner exemption, like V229), D5=A (`setTargets` and `savePreferences` guarded,
  erasure deletes `coach_radar_preferences`). D4 redesigned (guard-then-update in its own transaction, timeline
  moved to Step B), dispatcher branch, V230 header and IT rewrites, lock-busy consequences (D2), constructor sites
  and the "12 fixed" literals added.
