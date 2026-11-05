# Story review: skillars-deferred-171 (development-data erasure completeness)

- **Audit date:** 2026-11-05
- **Real HEAD (git rev-parse / git log, run before reading the story):** `576e96d6 docs(story): create skillars-deferred-171 ...` (worktree `skillars-deferred-168`, branch `skillars-deferred-171-development-data-erasure-completeness`; code content is `3f6afc94` plus the story commit).
- Every citation below was checked against this HEAD, not against any SHA the story names.
- Method: four read-only verification agents (citations, ledger/precedent, mechanistic, corner cases), then my own adversarial re-read of the findings that matter (section 6).

## 1. Citation verification (Layer 1, 15 items)

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| Guard `isErasedUnderLock`, MANDATORY, retryer, refresh (`DevelopmentDataErasureGuard:44-55`) | MATCH | `:44` MANDATORY, `:49` `withBoundedRetry`, `:55` `refresh(..., PESSIMISTIC_WRITE)`; NOWAIT is the repo's `findByIdForUpdate` lock hint |
| Erasure tombstone `:1820` | MATCH | `setDevelopmentDataErasedAt(Instant.now())` |
| Callers `:156`, `:252`, `HomeworkAssignmentService:132`, `BookingService` | MATCH (one drift) | `assignOne` is declared at `:131`; the guard call is `:132` |
| `RadarCompositeCalculationService` "own equivalent check" | MATCH | `:230` plain field check after its own lock, not the guard |
| `RadarAssessmentService:47`, saveAll `:89` | MATCH | `@Transactional` `:46`, `saveAll` `:89`, publish `:92` |
| `TimelineEventListener` `:275` / `:290` / `:301` / `:277` | MATCH | Both `writeTimelineEvent` overloads are `REQUIRES_NEW`; neither has a guard. 5-arg `:289-290` delegates to 6-arg `:300-301` |
| `ReportGenerationService` `:155`, `:235`, `:253`, `:258`, `:266`, `:276` | MATCH (minor drift) | `@Transactional` is `:153`; `onReportGenerated` annotations `:249-253`, method `:254` |
| `updateStatusAndStorageKey` returns `int`, unread | MATCH | `PerformanceReportRepository:32`; both main callers discard it |
| SLU chain, `saveSluWithRetry :125`, `saveAll :153`, Javadoc `:55-57`, `:95-97` | MATCH | |
| `SnapshotBatchWriter :28 / :42`; no `development.repo` import of `security.service` | MATCH | |
| Group 3 `:1737-1744`, "12 fixed" `:1583`, comment `:470-476` | MATCH | |
| V138 `:4567` cascade | MATCH | `homework_completions_assignment_id_fkey ... ON DELETE CASCADE` |
| `anonymizeByPlayerId` / `anonymizeByCoachId` | MATCH, **story wording wrong** | `anonymizeByCoachId` nulls the same four columns as the player query, not "voice_note_text too" |
| deferred-165 step 2, `604f7e67`, latest migration V170; V230 next free | MATCH | V229 is the latest on disk today |
| V229 shape, rule 2; module-rule tests | MATCH | Only `MarketplaceDoesNotDependOnPaymentTest` exists; nothing forbids `development` to `filestorage` or `development.repo` to `security.service` |

## 2. Ledger and precedent attribution (Layer 2)

| Claim | Sources checked | Verdict |
|---|---|---|
| Ledger `:3527-:3530`, `:3537` say what the mapping table says, tagged `[PICKED UP by skillars-deferred-171]` | deferred-work.md, 162/163 story files, code | CONFIRMED. No other open erasure bullet was missed |
| Not-in-story bullets `:3532`, `:3533`, chargeback | ledger, `TimelineEventListener`, guard | CONFIRMED |
| 162 AC5 is the guard's design; 165 step 1 / step 2 | story files, `BookingCompletionService:707`, `git cat-file 604f7e67` | CONFIRMED (step 1 = 163 AC6.1) |
| `PLAYER_NOT_BOOKABLE` exists; "existing guard callers log INFO and skip" | `BookingError:167`, callers | CODE EXISTS; **wording imprecise**: the INFO line is written by the guard, only the background callers skip, `BookingService` throws and `GdprRequestService` throws 409. 409 status mapping not traced |
| `drop-prepared-in: V170` valid for DROP COLUMN | `MigrationLint` | Mechanically valid (170 < 230); see finding F5 for truthfulness |
| REQUIRES_NEW row-lock note | `SecurityUtil:205/254`, `RefreshTokenRevocationService:46` etc. | CONFIRMED in code |

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict | Evidence |
|---|---|---|
| Guard is MANDATORY, takes NOWAIT lock, held to caller commit; erasure stamps under the same lock | HOLDS | Guard returns **false** (not throws) for a missing player row (`:51-54`); lock exhaustion rethrows `PessimisticLockingFailureException` after 8 tries (~3.2 s) |
| `submitAssessment` is `@Transactional`, nothing writes before `:89` | HOLDS | Guard slot between the tier check and `saveAll` is right |
| Guard at the top of the 6-arg `writeTimelineEvent` covers both callers | HOLDS | Callers use the 5-arg through the proxy (`self.` / injected bean); the 5-arg's own `REQUIRES_NEW` tx satisfies MANDATORY; the 5-arg to 6-arg hop is self-invocation, harmless. A stale comment at `:224` says `writeHomeworkAssigned` uses the 5-arg; it does not |
| `onReportGenerated`: "the row cannot be deleted between upload and update without the update returning 0" | **PARTIAL: true but does not close the window** | See F1 |
| No self-deadlock between `onReportGenerated`'s tx and `writeTimelineEvent`'s guard | HOLDS **for the design as written only** | `performance_reports.player_id` has no FK to `player_profiles`, so the outer UPDATE takes no profile lock. Stops holding the moment F1's fix puts a guard in the outer tx |
| `BlobDeletionOutboxSupport.enqueue(Collection<String>)` usable in the 0-row path | HOLDS | No `@Transactional` of its own; `requestDrainAfterCommit()` is a separate call (F7) |
| Retrier: not `@Transactional`, 23505 caught in the `try`, catch can stay outside `template.execute` | HOLDS | Catch is `DataIntegrityViolationException` whose cause is Hibernate `ConstraintViolationException` named `uq_player_skill_stats_session_skill`; the same translation applies on template commit |
| "Dispatcher skips the snapshot write" on the new outcome | **PARTIAL** | Dispatcher (`SluPersistenceDispatcher:70-92`) skips only on `FAILED` / `ALREADY_PERSISTED` and falls through to `writeAllWithRetry` otherwise; a `PLAYER_ERASED` value needs an explicit branch (F6) |
| `writeAllDeltas` guard per distinct player | HOLDS | Erased player: return quietly so the outbox row completes. Lock-busy there is retried (3 tries then outbox), not lost |
| Group 3 arithmetic 3 to 4 | HOLDS | Literal `3` at `:1738`; helper is `min(timeout, remaining / n)`; no test pins the group counts. Other "12" literals: `:1585`, `:1595`, `:1599`, `:1663`, `:1669`, `:1747-1750`, `ConfigBounds:644` |
| `homework_assignments`: only incoming FK is the completions cascade | HOLDS | `player_id` has no FK; `pack_id` is an outgoing `SET NULL` |

## 4. Findings that survived re-verification

### F1 (HIGH): D4 does not close the orphaned-PDF race
`onReportGenerated` runs in one `REQUIRES_NEW` tx: upload, `updateStatusAndStorageKey(READY, key)` (`ReportGenerationService:257-258`), timeline write, `notifyParent`. The tx stays open until after the notification. Erasure group 3 does a plain SELECT `findStorageKeysByPlayerId` (WHERE `storage_key IS NOT NULL`, `GdprErasureService:1741`) and then `deleteAllByPlayerId` (`:1742`), under READ COMMITTED.

Trigger: erasure's scan runs while the row is still `PENDING_UPLOAD` with a null key, so nothing is enqueued. `onReportGenerated` updates to READY (returns **1**). Erasure's DELETE blocks on that row lock, then deletes the committed READY row after the handler commits. The PDF (name, age, skills, recap excerpts) stays in storage with no key anywhere. The story's `0`-row branch never fires. The window is update to commit, which includes the timeline write and the notification.

Fix direction: run `isErasedUnderLock` in `onReportGenerated` after the upload and before the update, so the update is serialized against erasure on the `player_profiles` lock; if erased, enqueue the key and return. **Consequence the story must also decide:** the outer tx then holds the profile lock, so the inner `REQUIRES_NEW` `writeTimelineEvent` guard (NOWAIT) will always fail against its own caller's lock. The timeline row for the report must then be written inside the outer tx without a second guard, or the order restructured. Dev Notes `:220-223` ("no self-deadlock") must be rewritten. (This race pre-exists; the defect is that the story claims to close it.)

### F2 (HIGH): V230 as specified fails CI in three ways
1. **Lint reference scan.** `MigrationLint.referencesIn` (`MigrationLint.java:1027-1043`) matches a file that contains the table name and either `voice_note_text` or the camelCase `voiceNoteText`; comments are not stripped. `BookingCompletionService.java:707` has `{@code voiceNoteText}` and the same file has `"session_completion_data"` at `:574` and `:594`. The story lists only `SessionCompletionDataRepository` and `GdprErasureService:470-476`.
2. **ITs use the column.** `GdprErasureIT` (INSERT at `:725`, `:733`, `:964`, `:972`; SELECT and assert at `:745-756`, `:984-995`, including "Unrelated voice note") and `SessionCompletionResourceIT:291-293`. They fail with "column does not exist". AC6's "both anonymise queries still pass their ITs" cannot hold; no task rewrites them.
3. **Header comment.** `MigrationLint.java:740` requires a leading header comment on any migration that drops a column (`BARE_DROP_NO_HEADER`). Design 8 specifies only `SET LOCAL` plus the marker.

### F3 (MEDIUM): D1's premise is wrong for reports
For a really erased player, `generateReport` never reaches the new guard. Erasure deletes the SLU rows and the timeline rows, and `CoachPlayerLastSessionResolver.resolve` is computed from exactly those (`sluRepository.findLastSessionDate`, SESSION_COMPLETED timeline lookup). So `lastSession == null` and the method throws `AccessDeniedException` (403) at `ReportGenerationService:~200` before the PDF is built. The guard is reachable only in the race where erasure commits after the resolver read. AC2's unit test (guard mocked `true`) passes while saying nothing about the real path. Radar behaves differently (relationship survives, reaches the guard, 404), so an erased player gets 403 on reports and 404 on radar. "Same answer an unknown player gets" is also wrong: an unrelated player gets 403 from the relationship check first. State the real behaviour in D1 and test the race separately.

### F4 (MEDIUM): coach-initiated writers still unguarded after this story
- `SluTargetService.setTargets` (`:36-50`) upserts `player_slu_targets` after only `requireCoachPlayerRelationship`. Erasure empties that table, but the coach's booking relationship survives, so an erased child can get new rows. `NeglectedSkillDetectionService` is driven by that table and can then write `neglected_skill_flags`.
- `RadarDisplayService.savePreferences` writes `coach_radar_preferences`, unguarded.
Both are the same class as the ledger bullet at `:3527` that AC1/AC2 close. Either add them to AC1 or add an explicit ledger bullet and name them as out of scope.

### F5 (MEDIUM): D3's "met without the pre-launch exemption" is not accurate, and there is a rolling-deploy hazard
Step 2 (#304) left the two native queries naming the column, so the last code reference is removed in the same change as the drop (the compress-removal-and-drop case rule 2 exists to prevent). The V170 marker passes the lint mechanically but is not truthful. Old pods keep running `anonymizeByPlayerId` / `anonymizeByCoachId` with `voice_note_text = NULL` during the migration window; an erasure in that window throws "column does not exist" and surfaces as an `UNCLASSIFIED_FAILURE` alert. V229 handled this with an explicit owner-exemption header and a checklist entry. Adopt the same: header stating the exemption, owner decision recorded, V230 entry in the "prod is not live" list.

### F6 (MEDIUM): behaviour the story does not specify
- Dispatcher needs an explicit `PLAYER_ERASED` branch (see section 3); update the "tri-state" prose and `SluSaveOutcome` Javadoc.
- **Lock-busy:** the guard throws `PessimisticLockingFailureException` after ~3.2 s. In `saveSluWithRetry` that is a `DataAccessException`, so it is retried 3 times and then `@Recover` returns `FAILED` ("rows lost ... manual recovery needed", ERROR). A non-erased player now loses detail rows to a transient profile-lock collision, which cannot happen today. `onRadarEntrySubmitted` (catch at `:283`) and the report timeline (catch `:265-273`) drop the row; submit and `generateReport` return 409 (`ApiAdvice:711`). The ledger bullet `:3533` accepts this tradeoff only for SESSION_COMPLETED / HOMEWORK_ASSIGNED; extend it to RADAR_ASSESSMENT, PERFORMANCE_REPORT and SLU, and decide whether the SLU ERROR is acceptable.
- Multi-player batches in `writeAllDeltas`: pin a sorted order (in practice one player per session, so low).

### F7 (LOW-MEDIUM): implementation traps
- `GdprErasureServiceTest:161` calls `new GdprErasureService(...)` positionally; the new `HomeworkAssignmentRepository` breaks compilation until a mock and argument are added. Eight hand-construction sites need updating in total: `RadarAssessmentServiceTest:54`, `ReportGenerationServiceTest:99` and `:817`, `SluPersistenceRetrierTest:35`, `SluRetrierProxyRetryTest:69` (a `@Configuration` needing a tx manager or template bean), `SnapshotBatchWriterTest:28`, `GdprErasureServiceTest:161`. `ReportGenerationService` also gains a `BlobDeletionOutboxSupport` field.
- Call `BlobDeletionOutboxSupport.requestDrainAfterCommit()` after `enqueue`, or the key waits for the 5-minute sweep.
- Place the new `0`-row branch outside the existing `try`; an exception from `enqueue` inside it falls into `catch (Exception)` at `:259`, which marks UPLOAD_FAILED and leaves the blob orphaned.
- Test recipe: there is no stub `FileStorage`. `ReportGenerationServiceIT` extends `BaseStorageIT` (real MinIO); `SluCalculationServiceIT:80` uses a `@MockitoBean` whose field name is part of the context-cache key. `onReportGenerated` is `@Async`, so the IT needs an await or the event path used at `ReportGenerationServiceIT:~148`. For AC6 there is no existing `information_schema` assertion IT to extend; a small new one is simpler.
- Group-3 Javadoc/literal updates listed in section 3.

### F8 (LOW): player data erasure still does not clear
`development.coach_radar_preferences`, `development.radar_composite_dlq` (player id, `last_error` text), and `booking.session_completion_data.plan_blocks_snapshot` (V170; drill/block detail, at least as strong as `homework_drill_ids`). Ledger them or name them out of scope.

## 5. Corrections to the story text
- `anonymizeByCoachId` nulls all four columns (Background, last bullet).
- `assignOne` is `:131` (guard call `:132`); `generateReport` annotation `:153`; `onReportGenerated` method `:254`.
- D2: the INFO line comes from the guard, and only background callers skip.
- D1: drop "same answer an unknown player gets / learns nothing".
- Stale comment at `TimelineEventListener:224` (pre-existing; fix opportunistically).

## 6. What did not survive re-verification, or was downgraded

- **L3 "possible lock wait between erasure's `performance_reports` DELETE and the inner guard's NOWAIT retry"**: real but pre-existing and bounded (~3.2 s, then the inner catch swallows it). Not a story defect; folded into F1's note.
- **L4 "erasure may hit `CHILD_DELETE_LOCK_TIMEOUT` because the handler holds the report row lock"**: depends on timing against the 5 s per-statement timeout; I did not reproduce it. Kept only as a reason to prefer the F1 fix, not as its own finding.
- **L4 "no PENDING_UPLOAD sweeper, so a retry cannot re-upload after erasure"**: this is a reassurance, not a defect. `DevelopmentConfig:95` says abandoned uploads stay PENDING_UPLOAD. Dropped.
- **L1 row 1 "NOWAIT location not checked"**: L3 found it in `PlayerProfileRepository` (`lock.timeout=0`). Closed.
- **L2 "D3 rule-2 wording is a judgement call"**: upgraded to F5 once L4 showed the queries still name the column at step 2.
- **L4 "radar and report 404 vs 403 disclosure"**: kept inside F3 only; the disclosure to a coach who already has a relationship is minor and not a standalone finding.

## 7. Recommendation (per claim, not blanket)

- **Independently re-read by me against HEAD:** F1 (onReportGenerated body and erasure group 3 statements), F2 items 1-3 (`BookingCompletionService` hits, the IT file list, the `hasHeaderComment` rule), F3 (report pre-guard flow and `CoachPlayerLastSessionResolver`), the F4 `setTargets` body, the F6 dispatcher branches, the F7 constructor site. High confidence on these.
- **Taken from the agents, not re-read by me:** the line numbers inside `GdprErasureIT` / `SessionCompletionResourceIT`, the `ApiAdvice:711` 409 mapping, `RadarDisplayService.savePreferences` and the F8 tables, the exact list of eight hand-construction sites, the neglected-skill cascade in F4. Medium confidence; the dev should re-grep before editing.
- **Not checked by anyone:** the `PLAYER_NOT_BOOKABLE` HTTP status, actual lock timings under load, and whether `provisioning-checklist.md` already has a V230 placeholder.
- **Verdict:** do not start implementation as written. F1 changes the design of AC2 and the Dev Notes; F2 makes AC6/T6 incomplete and CI-red; F3 and F5 need an owner call (404 vs 403 wording, exemption for V230). F4 needs a scope decision. F6-F8 are fixes to the task list. The remaining ACs (AC1 radar guard, AC3 core mechanics, AC4, AC5) are sound.
