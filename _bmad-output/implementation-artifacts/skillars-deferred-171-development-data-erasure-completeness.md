# Story skillars-deferred-171: Development-data erasure completeness (guards on every development write, homework rows, the last recap column)

Status: ready-for-dev

<!-- CREATED 2026-11-04 (/bmad-create-story equivalent, written by hand), right after skillars-deferred-170
     (#446, merged debd4dca) and the Dependabot batch #341-#348. Bundles the five still-open GDPR items
     skillars-deferred-162 (2026-10-26) and skillars-deferred-163 left in deferred-work.md. Every citation
     re-read at origin/master 3f6afc94. Not yet reviewed: run /mto-story-review before dev. -->

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
development write (`:3533`, a deliberate tradeoff), and the chargeback/refund webhook gap (needs its own
design).

## Background (as the code stands at 3f6afc94)

- **The guard.** `DevelopmentDataErasureGuard.isErasedUnderLock(playerId, caller)`
  (`security/service/DevelopmentDataErasureGuard.java`) takes the `player_profiles` row lock with NOWAIT
  plus `PessimisticLockRetryer`, refreshes, and returns `true` when `development_data_erased_at` is set. It
  is `@Transactional(propagation = MANDATORY)`: it must run inside the caller's write transaction, so the
  lock is held until that write commits. Erasure stamps the marker under the same lock in the transaction
  that deletes the rows (`GdprErasureService.deletePlayerDevelopmentDataInDedicatedPool`, tombstone at
  `:1820`). Callers today: `TimelineEventListener.writeSessionCompleted` (`:156`), `writeHomeworkAssigned`
  (`:252`), `HomeworkAssignmentService.assignOne` (`:132`), and `BookingService`.
  `RadarCompositeCalculationService.recalculateComposite` has its own equivalent check.
- **Radar.** `RadarAssessmentService.submitAssessment` (`:47`, `@Transactional`) saves the entries
  (`:89`) and publishes `RadarEntrySubmittedEvent`. Its timeline row comes from
  `TimelineEventListener.onRadarEntrySubmitted` (`:275`, `@Async`) through `writeTimelineEvent` (`:290`,
  `REQUIRES_NEW`), which has **no** guard.
- **Report.** `ReportGenerationService.generateReport` (`:155`, `@Transactional`) builds the PDF in memory,
  saves a `PENDING_UPLOAD` row (`:235`) and publishes `ReportGeneratedEvent`. `onReportGenerated` (`:253`,
  AFTER_COMMIT, `@Async("reportExecutor")`, `REQUIRES_NEW`) uploads the PDF to storage, flips the row to
  `READY` (`:258`), writes the PERFORMANCE_REPORT timeline row through the same unguarded
  `writeTimelineEvent`, and notifies the parent (`:276`). `updateStatusAndStorageKey` returns the update
  count (`PerformanceReportRepository.java:32`); nothing reads it.
- **SLU.** `SluCalculationService.onBookingCompleted` (AFTER_COMMIT, `@Async`, no transaction) computes the
  rows and hands them to `SluPersistenceDispatcher.dispatchSluPersistence` (`@Async("sluRetryExecutor")`),
  which calls `SluPersistenceRetrier.saveSluWithRetry` (`:125`) and then `SnapshotPersistenceRetrier`.
  - `SluPersistenceRetrier` is deliberately **not** `@Transactional` (`:95`): its catch for the V47 unique
    collision works only because `sluRepository.saveAll` (`:153`) opens and commits its own transaction, so
    23505 surfaces inside the `try`.
  - The weekly snapshot goes through `SnapshotBatchWriter.writeAll` (`repo/SnapshotBatchWriter.java:28`,
    `@Transactional`), which applies the deltas via `writeAllDeltas` (`:42`, `@Transactional`). The outbox
    re-drive (`SluSnapshotOutboxHandler`) also calls `writeAllDeltas`.
- **Erasure, group 3** (`GdprErasureService.java:1737-1744`): budget sized for **3** statements: the report
  storage-key scan, the report delete, `homeworkCompletionRepository.deleteAllByPlayerId`. Nothing deletes
  `session.homework_assignments` (player id, coach id, drill id, booking id per row).
  `homework_completions.assignment_id` references it `ON DELETE CASCADE` (`V138:4567`). The Javadoc counts
  "12 fixed delete/scan statements" (`:1583`).
- **Recap anonymisation.** `SessionCompletionDataRepository.anonymizeByPlayerId` (`:60`, native) nulls
  `voice_note_text, what_we_did, whats_next, what_went_well`; it leaves `homework_drill_ids`.
  `anonymizeByCoachId` (`:69`) nulls `voice_note_text` too. `voice_note_text` is unmapped by JPA since
  skillars-deferred-165 (step 2, #304, merged 604f7e67, when the latest migration on disk was V170). The
  `GdprErasureService.java:470-476` comment explains why both queries still null it.

## Decisions (defaults pinned here; the owner may overturn any at /mto-story-review)

- **D1: a coach action on an erased player is refused, not silently skipped.** Radar submit and report
  generation are synchronous coach requests; a silent no-op would tell the coach "saved" when nothing was.
  Throw `ResourceNotFoundException("Player not found", "player_profile")` (404), the same answer an
  unknown player gets, so the coach learns nothing about the erasure. *Alternative:* a dedicated 409 code
  with a translated message, like booking's `PLAYER_NOT_BOOKABLE`. Choose it if the owner wants the coach
  told why.
- **D2: background writes skip quietly** (log at INFO, as the existing guard callers do): the timeline
  rows, SLU rows and weekly snapshots.
- **D3: drop `voice_note_text` now** (`V230`), with `-- migration-lint: drop-prepared-in: V170` (V170 was
  the latest migration when step 2, the last reader's removal, shipped). The table had a release in
  between, so rule 2 is met without the pre-launch exemption. Any legacy coach-authored text in the column
  goes with it, which is the point.
- **D4: an orphaned report PDF is deleted, not kept.** If erasure lands between `generateReport`'s commit
  and the upload, the row is gone when `onReportGenerated` updates it, and the uploaded PDF (name, age,
  skills, recap excerpts) would sit in storage with nothing pointing at it. Enqueue its key on the
  blob-deletion outbox instead.

## Design (pinned; do not re-derive)

1. **Radar (AC1).** In `submitAssessment`, after the relationship and tier checks and before
   `radarRepository.saveAll` (`:89`): `if (erasureGuard.isErasedUnderLock(playerId,
   "RadarAssessmentService.submitAssessment")) throw new ResourceNotFoundException(...)`. The method is
   `@Transactional`, so MANDATORY is satisfied and the lock is held until the entries commit.
2. **Timeline rows (AC1, AC2).** Put the guard at the top of the six-argument `writeTimelineEvent`
   (`:301`), returning without a write when erased. Both overloads and both callers (radar `:277`, report
   `ReportGenerationService:266`) go through it. `writeSessionCompleted` and `writeHomeworkAssigned` keep
   their own guards; they call `saveTimelineEvent` or a repository insert, not `writeTimelineEvent`.
3. **Report (AC2).**
   - `generateReport`: the guard before `reportRepository.saveAndFlush` (`:235`), throwing as in D1. Place
     it **after** the PDF is built (keep the lock short) and before the save.
   - `onReportGenerated`: after the upload, if `updateStatusAndStorageKey(..., READY, key)` returns 0 (the
     row was deleted by an erasure that committed after `generateReport`), enqueue `key` with
     `BlobDeletionOutboxSupport.enqueue(List.of(key))` in this same transaction, log at INFO, and return
     before the timeline row and the parent notification. Same for the `UPLOAD_FAILED` branch: a 0 there
     needs no blob action (nothing was stored).
   - The row cannot be deleted *between* the upload and the update without the update returning 0, so this
     closes the window. `development` may call `filestorage`: `ReportGenerationService` already imports it
     (`fileStorageService.storeBytes`), and module rules are pairwise tests (only
     `MarketplaceDoesNotDependOnPaymentTest` exists), none covering this pair.
4. **SLU rows (AC3).** In `SluPersistenceRetrier.saveSluWithRetry`, run the guard and `saveAll` in one
   `TransactionTemplate` (`REQUIRES_NEW`) transaction: `isErasedUnderLock(rows.get(0).getPlayerId(), ...)`
   then `saveAll`. When erased, return a new outcome (for example `SluSaveOutcome.PLAYER_ERASED`), and the
   dispatcher skips the snapshot write. **Keep the V47 23505 catch outside `template.execute(...)`:** the
   constraint now fires at the template's flush and commit, and the exception leaves `execute` after the
   rollback, so the catch still sees it. Do **not** annotate the class or method `@Transactional`, and
   update its Javadoc (`:55-57`, `:95-97`) to say why a template is fine and an annotation is not. All rows
   in one call share one player (one session, one player); assert it (`IllegalStateException` otherwise).
5. **Weekly snapshots (AC3).** In `SnapshotBatchWriter.writeAllDeltas` (`:42`, `@Transactional`), guard
   each distinct `playerId` among the deltas and drop that player's deltas when erased. This covers
   `writeAll` (it delegates, `:28-32`) and the outbox re-drive. `SnapshotBatchWriter` is a `@Transactional`
   writer component in the `repo` package, not a Spring Data interface; no `development.repo` class imports
   `security.service` yet, and no test forbids it. Inject the guard there (the guard's MANDATORY propagation
   needs exactly this transaction).
6. **Homework assignments (AC4).** In group 3, after `homeworkCompletionRepository.deleteAllByPlayerId`,
   add `homeworkAssignmentRepository.deleteAllByPlayerId(playerId)` (new bulk `@Modifying` JPQL delete,
   same shape as the completions one). Keep the explicit completions delete first (the cascade would cover
   it, but explicit keeps the per-statement lock arithmetic honest). Group 3's statement count goes from 3
   to **4** (`:1738`), and the Javadoc's "12 fixed" (`:1583`) and any "12 + M" arithmetic become 13. Search
   the class and `GdprErasureIT`/lock-budget tests for the literal counts.
7. **Recap drill ids (AC5).** `anonymizeByPlayerId` also sets `homework_drill_ids = NULL`. Not
   `anonymizeByCoachId`: drill ids are the coach's own library references, not the player's data.
8. **`voice_note_text` (AC6).** `V230__drop_session_completion_data_voice_note_text.sql`:
   `SET LOCAL lock_timeout = '5s';`, then `-- migration-lint: drop-prepared-in: V170` directly above
   `ALTER TABLE booking.session_completion_data DROP COLUMN IF EXISTS voice_note_text;`. In the same
   change, remove `voice_note_text = NULL` from both native queries and reword their comments
   (`SessionCompletionDataRepository.java:47-60`, `:62-69`) and `GdprErasureService.java:470-476`. The
   lint's reference scan must then find no `voice_note_text` in `src/main` outside `db/migration`. Take the
   next free number at dev time (V230 at 3f6afc94).

## Acceptance Criteria

**AC1: a radar submission for an erased player writes nothing.**
- `submitAssessment` for a player with `development_data_erased_at` set throws 404 `player_profile` and
  saves no `radar_assessment_entries` row (unit test with the guard mocked to `true`, `verify(never())`
  on `saveAll` and on the event publish).
- `writeTimelineEvent` for an erased player writes no `player_timeline_events` row (IT: stamp the marker,
  call it, count 0).
- A not-erased player behaves as today (existing radar tests stay green, with a lenient `false` stub where
  the guard is mocked).

**AC2: a report for an erased player writes nothing, and an orphaned PDF is deleted.**
- `generateReport` for an erased player throws 404 and saves no `performance_reports` row (unit test).
- `onReportGenerated` when the row was deleted after `generateReport` (IT: generate, delete the row as
  erasure would, then run the handler with the stub storage): the PDF key is in the blob-deletion outbox,
  no timeline row is written, the parent is not notified.
- The normal path still writes READY, the timeline row and the notification (existing tests).

**AC3: SLU and weekly snapshots skip an erased player.**
- `saveSluWithRetry` for an erased player saves no `player_skill_stats` row and returns the erased
  outcome; the dispatcher then writes no snapshot (unit tests).
- `writeAllDeltas` drops an erased player's deltas and still applies another player's (IT or unit).
- The V47 duplicate-delivery case still resolves as the idempotent no-op (the existing collision test
  stays green; add one if none exercises the catch through the new template).

**AC4: erasure deletes the player's homework assignments.**
- After a player erasure, `session.homework_assignments` has no row for that player, and
  `homework_completions` none either (extend `GdprErasureIT` or its development-data test with seeded
  assignment and completion rows).
- Group 3's lock-budget arithmetic counts 4 statements; any test pinning the statement count is updated.

**AC5: anonymisation clears the recap's homework drill ids.** After `anonymizeByPlayerId`,
`homework_drill_ids` is NULL for that player's rows and untouched for another player's (IT).

**AC6: `voice_note_text` is gone.** A fully migrated test database has no such column (one assertion in
an existing IT that reads `information_schema`); `MigrationConventionLintTest` passes, and fails by hand
with the marker removed (check once, do not commit). Both anonymise queries still pass their ITs.

**AC7: docs and ledger.**
- `docs/dev-docs/development/index.html` (or wherever the guard is described): one line listing the guarded
  writes. `docs/dev-docs/database/index.html` if it lists `session_completion_data` columns. Rebuild the
  search index; `check-docs` passes.
- `docs/deployment/provisioning-checklist.md`: a V230 entry (drop of a column whose last reader left in
  #304).
- Delete the five mapped ledger bullets.

**AC8: `/pr-review`** on the final commit, run to a clean round or to an owner decision. Then open the PR
(not `no-build`).

## Tasks / Subtasks

- [ ] T1 (AC1): radar guard; `writeTimelineEvent` guard; tests.
- [ ] T2 (AC2): report guard; orphaned-PDF branch in `onReportGenerated`; module-rule check; tests.
- [ ] T3 (AC3): SLU template + outcome; snapshot guard; Javadoc; tests.
- [ ] T4 (AC4): `deleteAllByPlayerId` on homework assignments; group 3 count; IT.
- [ ] T5 (AC5): `homework_drill_ids = NULL`; IT.
- [ ] T6 (AC6): V230; both queries and comments; schema assertion; lint check by hand.
- [ ] T7 (AC7): docs, checklist, ledger.
- [ ] T8 (AC8): `/pr-review`, PR.

## Dev Notes

- Work in the worktree `../skillars-deferred-168`, branch
  `skillars-deferred-171-development-data-erasure-completeness`. No local `mvn verify` (CI is the gate);
  targeted tests with `-DskipFrontend`. Add files by name.
- **Unit tests that mock the guard:** an unstubbed Mockito `boolean` answers `false`, which is "not
  erased", so existing tests keep working without a stub. Tests that construct services by hand need the
  new constructor argument.
- **Test contexts:** do not add a `@MockitoBean`/`@MockitoSpyBean` to an IT base class (context-cache gate,
  see the test-context-cache notes). Stamp `development_data_erased_at` with `jdbcTemplate` inside a
  `transactionTemplate` (bare IT writes do not commit: hikari auto-commit is off).
- **Lock order:** every new guard call takes `player_profiles` first, then writes, the same order erasure
  uses. Do not call the guard after a write to the same transaction's other development rows.
- **`writeTimelineEvent` is `REQUIRES_NEW`**, so its guard runs in its own transaction, which is what the
  guard needs. `onReportGenerated` calls it from inside its own `REQUIRES_NEW` transaction: that is a
  second transaction while the first holds no `player_profiles` lock, so there is no self-deadlock (see the
  REQUIRES_NEW row-lock note: the hazard is a caller that already wrote the same row).

### References

- `deferred-work.md` lines in the Ledger mapping.
- `skillars-deferred-162-*.md`: AC5 (the guard's design) and its Completion Notes.
- `docs/deployment/migration-conventions.md` rule 2; `V229__drop_marketplace_session_packs.sql` for the
  drop shape.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-04: created (ready-for-dev).
