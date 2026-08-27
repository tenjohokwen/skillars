# Story Deferred-77: SLU/Radar Robustness & Cross-Module Hardening

**Status:** ready-for-dev

## Story

As a platform maintainer continuing the `deferred-work.md` drawdown after Deferred-76's Deployment pass, I want to address the remaining actionable items in the SLU/Radar and Development modules — fixing genuine race conditions, adding missing validation and retry logic, restructuring transactions to keep DB connections free, and addressing small but real gaps in Booking/Video/Messaging — so that these modules' production robustness is improved and the backlog of small, high-impact hardening work is meaningfully reduced.

### Why this story exists

Deferred-76 closed many Deployment/SLU items but left ~76 open in those sections. Systematic re-audit identified:
- ~15 genuinely actionable items (not pre-existing patterns or accepted tradeoffs)
- Cross-module small improvements (Booking concurrency, Video null-checks, etc.)
- Transaction restructuring opportunities to release DB connections sooner
- Missing validation and retry logic at critical integration points

This story bundles them into a single, focused pass rather than spreading across multiple small stories.

## Acceptance Criteria

### AC1 — SluContributionService validation and exception handling

**Current behavior:**
- `SluContributionService.getCoachContributions` casts `row[0]` to UUID without guard; throws `IllegalArgumentException` if value is not a valid UUID string
- `coachProfileService.getDisplayNamesByIds` call has no exception wrapping; propagates as 500 on failure
- Double iteration of `rows` list (once to calculate percentages, once to build DTOs)

**Fix:**
1. Add UUID validation guard before cast:
   ```java
   if (!(row[0] instanceof UUID)) {
       throw new BadSqlGrammarException("coach_id from query is not a valid UUID", null);
   }
   ```
2. Wrap `coachProfileService.getDisplayNamesByIds` in try-catch, log error, return empty map or placeholder names:
   ```java
   Map<UUID, String> displayNames;
   try {
       displayNames = coachProfileService.getDisplayNamesByIds(coachIds);
   } catch (RuntimeException e) {
       log.error("Failed to fetch coach display names for contribution calculation", e);
       displayNames = new HashMap<>(); // Empty or use coach IDs as fallback
   }
   ```
3. Optimize double iteration: calculate percentages in-line during DTO building, not separately.

**Testing:** Extend `SluContributionServiceTest` with cases for UUID parse error and display-name fetch failure; verify fallback behavior.

---

### AC2 — ReportGenerationService transaction restructuring (S3 I/O + timeline orphaning)

**Current behavior:**
- S3 calls (logo download, PDF upload) execute inside `@Transactional generateReport` method, holding DB connection for the duration
- `writeTimelineEvent` runs inside its own `REQUIRES_NEW` transaction; if outer `generateReport` rolls back after this commits, timeline event orphans with a dead `referenceId`

**Fix:**

1. **Extract S3 I/O to async post-commit handler:**
   - Keep the PDF generation itself transactional (writes `report` row, commits)
   - Create a new `@Async` method `uploadPdfAndLogTimeline(reportId, pdfBytes)` 
   - Call it from a `@TransactionalEventListener(phase = AFTER_COMMIT)` listener triggered by `ReportGeneratedEvent`
   - If S3 upload fails, the event listener logs error but doesn't roll back the report row (eventual consistency)

2. **Fix timeline event orphaning:**
   - Move `writeTimelineEvent` call into the async post-commit handler (after PDF upload succeeds)
   - This ensures timeline event is only created if the full report generation succeeded
   - If async fails, timeline event is never created (no orphaned rows)

**Code structure:**
```java
@Transactional
public Report generateReport(...) {
    // ... existing PDF generation logic ...
    Report report = reportRepository.save(...);
    eventPublisher.publishEvent(new ReportGeneratedEvent(this, report.getId(), pdfBytes));
    return report;
}

@Async
@Slf4j
public void uploadPdfAndLogTimeline(UUID reportId, byte[] pdfBytes) {
    try {
        // Upload to S3
        s3Service.uploadReport(reportId, pdfBytes);
        // Write timeline event (inside its own REQUIRES_NEW transaction)
        writeTimelineEvent(reportId, ...);
    } catch (RuntimeException e) {
        log.error("Failed to upload PDF or write timeline for report {}", reportId, e);
        // Do NOT throw — this is eventual consistency
    }
}
```

**Testing:** Add IT verifying: (1) PDF uploaded successfully, (2) S3 failure doesn't rollback report row, (3) timeline event only exists when PDF upload succeeds.

---

### AC3 — ReportGenerationService stale branding logo validation

**Current behavior:**
- Coach tier downgrade from ACADEMY to lower tier leaves prior (now-invalid) logo key in `reportConfig`
- Re-upgrade reuses the stale key without re-validation
- `buildPdf` has graceful try/catch fallback that prevents crash, but serves stale logo

**Fix:**
1. Add logo key validation in `buildPdf` before using:
   ```java
   if (reportConfig.logoKey != null) {
       boolean logoStillValid = checkCoachLogoIsCurrentlyValid(coachId, reportConfig.logoKey);
       if (!logoStillValid) {
           reportConfig.logoKey = null; // Clear stale key
       }
   }
   ```
2. Implement `checkCoachLogoIsCurrentlyValid`: verify the logo key matches the coach's current tier (query `CoachProfile.tier` and confirm logo key is valid for that tier).

**Testing:** Add case: downgrade coach tier, generate report, verify stale logo is not served; then re-upgrade and verify correct logo is fetched.

---

### AC4 — PlayerProfileService parent email fetch optimization

**Current behavior:**
- `getParentEmailByPlayerId` executes 2 separate queries:
  1. `playerProfileRepository.getParentIdByPlayerId(playerId)` 
  2. `userRepository.findById(parentId)`
- TOCTOU gap: parent account could be deleted between the two calls

**Fix:**
1. Add new repository method `PlayerProfileRepository.findParentEmailByPlayerId(playerId)` using native query:
   ```sql
   SELECT u.email FROM main.user_account u
   JOIN main.player_profile p ON u.user_id = p.parent_id
   WHERE p.player_id = ?
   ```
2. Update `getParentEmailByPlayerId` to use the new single-query method.
3. Add null-check for cases where parent is not found.

**Testing:** Add test cases for player with parent, player without parent, parent account deleted mid-operation.

---

### AC5 — SluRepository column name verification + fallback

**Current behavior:**
- Native queries in `SluRepository` reference `slu_value` and `calculated_at` columns
- Column names assumed but not verified against migration files
- Runtime `BadSqlGrammarException` if schema doesn't match

**Fix:**
1. **Verification step:** Before finalizing, read `src/main/resources/db/migration/V46__development_module_init.sql` (and any later V5x migrations touching `player_skill_stats`)
2. **Add defensive validation:** Create a `@PostConstruct` method in `SluRepository` that runs a test query and catches/logs any `BadSqlGrammarException`:
   ```java
   @PostConstruct
   void validateSchemaMigration() {
       try {
           entityManager.createNativeQuery("SELECT slu_value, calculated_at FROM main.player_skill_stats LIMIT 0").getResultList();
           log.info("SluRepository schema validation passed");
       } catch (Exception e) {
           log.error("SluRepository schema validation failed — column names may not match migration", e);
           throw new AppSetupException("SluRepository schema mismatch");
       }
   }
   ```
3. **Document assumption:** Add comment in `SluRepository` linking to the exact migration and column definitions.

**Testing:** Verify schema validation passes on clean database; add IT that intentionally renames a column and confirms validation catches it.

---

### AC6 — SluRepository findLastSessionDate filtering

**Current behavior:**
- `findLastSessionDate` queries `MAX(calculated_at)` over all `player_skill_stats` rows without filtering
- If `RadarAssessmentService` writes SLU rows to `player_skill_stats`, radar assessments could reset timeline-access expiry window
- Contradicts design comment in `TimelineQueryService`

**Fix:**
1. **Verify:** Confirm whether `RadarAssessmentService` actually writes to `player_skill_stats` (re-audit code if not obvious)
2. **If yes:** Update `findLastSessionDate` to filter for session-only SLU rows:
   ```sql
   SELECT MAX(calculated_at) FROM main.player_skill_stats
   WHERE player_id = ? AND calculated_at >= (SELECT MAX(created_at) FROM main.player_sessions WHERE player_id = ?)
   ```
3. **Document:** Add comment explaining why radar assessments are excluded.

**Testing:** Add IT with both session SLU and radar assessment rows; verify `findLastSessionDate` returns only session-based max.

---

### AC7 — SluCalculationService ISO week boundary race handling

**Current behavior:**
- Session straddling ISO week boundary (Mon 00:00) can write SLU rows to one week and snapshot to another
- `now` captured before `saveAll`; failure between saveAll and snapshotBatchWriter.writeAll leaves snapshot stale

**Fix:**
1. **Capture timestamp once, reuse consistently:**
   ```java
   Instant processedAt = Instant.now(); // Capture once
   List<PlayerSkillStats> sluRows = calculateSluForSessions(..., processedAt);
   sluRepository.saveAll(sluRows);
   
   List<PlayerSkillSnapshot> snapshots = buildSnapshots(..., processedAt); // Use same timestamp
   snapshotBatchWriter.writeAll(snapshots);
   ```
2. **Add retry logic** (see AC8 below) to handle transient saveAll failures.

**Testing:** Add IT that simulates a session straddling midnight; verify SLU rows and snapshot have matching `calculated_at` timestamps.

---

### AC8 — SluCalculationService retry on saveAll failure

**Current behavior:**
- `sluRepository.saveAll` has no retry; transient DB errors permanently lose SLU data
- Dev notes provide recovery query, but requires manual ops intervention

**Fix:**
1. Wrap `saveAll` in a retry loop using Spring's `@Retryable`:
   ```java
   @Retryable(
       retryTemplate = retryTemplate(),
       recover = "recoverSluSaveFailure"
   )
   public void saveSluWithRetry(List<PlayerSkillStats> rows) {
       sluRepository.saveAll(rows);
   }
   
   @Recover
   public void recoverSluSaveFailure(RuntimeException ex, List<PlayerSkillStats> rows) {
       log.error("Failed to save SLU after retries — {} rows lost, manual recovery needed", rows.size(), ex);
       // Emit metric for ops alerting
   }
   ```
2. Configure `retryTemplate` with exponential backoff (e.g., 3 retries, 100ms initial delay).

**Testing:** Add IT that mocks transient DB error on first call, succeeds on second; verify retry succeeds.

---

### AC9 — Development module orphaned radar composites cascade

**Current behavior:**
- `player_radar_composites` has no FK to `player_profiles`
- Player deletion leaves orphaned composite rows

**Fix:**
1. **Schema migration V113:** Add FK with ON DELETE CASCADE:
   ```sql
   ALTER TABLE main.player_radar_composites 
   ADD CONSTRAINT fk_prc_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(player_id) ON DELETE CASCADE;
   ```
2. **Test:** Verify existing orphaned rows (if any) are identified and documented; create a separate cleanup task if needed.

**Testing:** Add IT verifying player deletion cascades to `player_radar_composites`.

---

### AC10 — Async composite calculation race condition & DLQ infrastructure

**Current behavior:**
- `RadarCompositeCalculationService.onRadarEntrySubmitted` uses `@Async` with no retry/dead-letter queue
- Concurrent submissions for same player both query aggregates before either upserts (last-writer-wins)
- Listener failure leaves composite stale until next submission

**Fix:**

**Phase 1:** Add pessimistic locking guard
1. Add query-then-upsert lock (pessimistic write on player row):
   ```java
   @Transactional
   public void recalculateComposite(Long playerId) {
       playerProfileRepository.findByIdForUpdate(playerId); // Lock to serialize concurrent recalculations
       // ... fetch aggregates and upsert composite ...
   }
   ```
2. Log composite calculation failures clearly for ops visibility.

**Phase 2:** Implement dead-letter queue infrastructure
1. Create Kafka topic `skillars.radar-composite-dlq` or polling queue table `async_task_dlq`
2. Wrap `@Async` listener in try-catch; on failure, emit to DLQ:
   ```java
   @Async
   @TransactionalEventListener(phase = AFTER_COMMIT)
   public void onRadarEntrySubmitted(RadarEntrySubmittedEvent event) {
       try {
           recalculateComposite(event.getPlayerId());
       } catch (Exception e) {
           log.error("Composite calculation failed, emitting to DLQ", e);
           dlqService.emitFailedCompositeCalculation(event.getPlayerId(), e);
       }
   }
   ```
3. Add DLQ consumer that replays failed calculations with exponential backoff
4. Emit metric `radar.composite.dlq.count` for ops alerting

**Testing:** 
- Phase 1: Add IT verifying concurrent submissions serialize on player lock; verify failure logging
- Phase 2: Add IT verifying failed calculations emit to DLQ; verify replay succeeds

---

### AC11 — VideoAccessGuard request-scoped cache in singleton (Video/Playback)

**Current behavior:**
- `VideoAccessGuard` (singleton) holds request-scoped `VideoAccessCache` reference
- Standard Spring proxy pattern handles this in web context
- Fails in bare unit tests (mitigated by mocking)

**Fix:**
1. Move `VideoAccessCache` lookup into a separate `@Component` with `@Scope("request")` if needed
2. Or inject `ApplicationContext` and use `getBean(VideoAccessCache.class)` lazily:
   ```java
   @Component
   public class VideoAccessGuard {
       @Autowired
       private ApplicationContext context;
       
       private VideoAccessCache getCache() {
           return context.getBean(VideoAccessCache.class);
       }
   }
   ```
3. Add test setup helper to inject cache in unit tests.

**Testing:** Verify unit tests pass without manual mocking; verify web integration tests still work.

---

### AC12 — VideoDeletionService cascadeDeleteForAccount atomicity

**Current behavior:**
- `cascadeDeleteForAccount` quota reset non-atomic: JVM crash after per-video commits but before `resetBytesForOwner()` leaves quota row permanently non-zero
- No retry path corrects this

**Fix:**
1. **Restructure as single transaction:**
   ```java
   @Transactional
   public void cascadeDeleteForAccount(Long accountId) {
       // Delete all videos in one batch DELETE statement
       videoRepository.deleteAllByOwnerId(accountId);
       
       // Then reset quota (single UPDATE statement)
       quotaRepository.resetBytesForOwner(accountId);
   }
   ```
2. **Add pre-delete quota audit:** Query and log total bytes before deletion for ops reconciliation.

**Testing:** Add IT verifying quota is reset atomically; simulate failure and verify rollback behavior.

---

### AC13 — VideoPlaybackService parent-play/PURGE race null check

**Current behavior:**
- `PlaybackService.authorizePlayback` called with potentially-null `providerAssetId` if video is concurrently purged between `@PreAuthorize` canPlay evaluation and the call

**Fix:**
1. Add defensive null check:
   ```java
   public PlaybackToken authorizePlayback(...) {
       Video video = videoRepository.findById(videoId)
           .orElseThrow(() -> new VideoNotFoundException(...));
       
       if (video.getProviderAssetId() == null) {
           throw new VideoNotFoundException("Video was purged during playback authorization");
       }
       // ... rest of logic ...
   }
   ```
2. Return clear 404 error (consistent with other not-found cases).

**Testing:** Add IT simulating concurrent purge during authorization; verify 404 returned cleanly.

---

### AC14 — BookingService.transition pessimistic lock (Booking/Availability)

**Current behavior:**
- No lock on `BookingService.transition()` state machine
- Concurrent callers can both pass validation on the same booking, causing state conflicts

**Fix:**
1. Add `@Lock(LockModeType.PESSIMISTIC_WRITE)` to internal `transitionInternal` method:
   ```java
   @Transactional(propagation = Propagation.REQUIRED)
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   private void transitionInternal(UUID bookingId, BookingEvent event) {
       // ... existing logic ...
   }
   ```
2. Or add lock at call site: `bookingRepository.findByIdForUpdate(bookingId)` before transition.
3. Document bounded timeout (5 seconds) for lock wait.

**Testing:** Add IT with concurrent transition attempts; verify only one succeeds, other gets 409 or waits correctly.

---

### AC15 — BookingService.isBookingPlannable status guard (Booking/Availability)

**Current behavior:**
- `isBookingPlannable` accepts `"UPCOMING"` status but no code path actually transitions a booking to this status
- Proactive future-proofing that may silently accept invalid state

**Fix:**
Add explicit guard against unexpected booking statuses:
   ```java
   public boolean isBookingPlannable(String status) {
       Set<String> supportedStates = Set.of("ACTIVE", "PENDING");
       if ("UPCOMING".equals(status)) {
           log.warn("isBookingPlannable called with UPCOMING status - no transition path exists yet");
           return false; // Guard: reject unsupported status
       }
       if (!supportedStates.contains(status)) {
           log.warn("Unexpected booking status for planning check: {}", status);
           return false;
       }
       return true;
   }
   ```

**Rationale:** `UPCOMING` status may be planned for future use but is not live yet. Explicit guard prevents silent acceptance of incomplete state transitions. If UPCOMING support is added later, this guard will need to be updated.

**Testing:** 
- Add test for UPCOMING status returning false with warning log
- Verify ACTIVE/PENDING return true
- Verify unknown statuses return false with warning

---

### AC16 — Ledger hygiene (deferred-work.md updates)

Apply the following tags to `deferred-work.md`:

1. Section "Deferred from: code review of skillars-5-6..." Items fixed by this story:
   - Item about SluContributionService: append `[CLOSED by skillars-deferred-77 AC1]`
   - Item about ReportGenerationService S3 I/O: append `[CLOSED by skillars-deferred-77 AC2]`
   - Item about stale logo: append `[CLOSED by skillars-deferred-77 AC3]`

2. Section "Deferred from: code review of skillars-5-5..." Items fixed:
   - R3-D1 (orphaned timeline event): append `[CLOSED by skillars-deferred-77 AC2]`
   - R3-D2 (findLastSessionDate filtering): append `[CLOSED by skillars-deferred-77 AC6]`
   - R3-D3 (stale logo): append `[CLOSED by skillars-deferred-77 AC3]`

3. Section "Deferred from: code review of skillars-5-4..." Items fixed:
   - R3-D2 findLastSessionDate: append `[CLOSED by skillars-deferred-77 AC6]`

4. Section "Deferred from: code review of skillars-5-1..." Items fixed:
   - W1 (no FK on radar baselines): append `[AUDIT 2026-08-27: remains open, accepted no-FK pattern per spec]`

5. Section "Deferred from: code review of skillars-5-2..." Items fixed:
   - DEF3/DEF4 (async composite race): append `[CLOSED by skillars-deferred-77 AC10 Phase 1 (pessimistic lock guard); Phase 2 DLQ infrastructure deferred]`
   - DEF6 (orphaned radar composites): append `[CLOSED by skillars-deferred-77 AC9]`

6. Section on SluCalculationService:
   - D6 (ISO week boundary race): append `[CLOSED by skillars-deferred-77 AC7]`
   - D1 (no retry on saveAll): append `[CLOSED by skillars-deferred-77 AC8]`

7. Section on VideoAccessGuard:
   - W1 (request-scoped cache): append `[CLOSED by skillars-deferred-77 AC11]`

8. Section on VideoDeletionService:
   - W8 (quota reset non-atomic): append `[CLOSED by skillars-deferred-77 AC12]`

9. Section on PlaybackService:
   - W10 (parent-play/PURGE race): append `[CLOSED by skillars-deferred-77 AC13]`

10. Section on BookingService:
    - W8 (isBookingPlannable UPCOMING status): append `[CLOSED by skillars-deferred-77 AC15]`
    - "No pessimistic lock on transition()": append `[CLOSED by skillars-deferred-77 AC14]`

---

## Tasks / Subtasks

- [ ] AC1: Add UUID validation guard and exception wrapping in SluContributionService; optimize double iteration
- [ ] AC2: Restructure ReportGenerationService to extract S3 I/O and timeline event creation to async post-commit handler
- [ ] AC3: Add stale branding logo validation in buildPdf
- [ ] AC4: Implement single-query fetch for parent email; add TOCTOU null-checks
- [ ] AC5: Add SluRepository schema validation in @PostConstruct; verify column names match migration
- [ ] AC6: Fix SluRepository.findLastSessionDate to filter for session-only rows; re-audit RadarAssessmentService usage
- [ ] AC7: Ensure SluCalculationService uses consistent timestamp for SLU rows and snapshots
- [ ] AC8: Add @Retryable wrapper with exponential backoff for sluRepository.saveAll
- [ ] AC9: Add FK with ON DELETE CASCADE from player_radar_composites to player_profiles (V113 migration)
- [ ] AC10 Phase 1: Add pessimistic write lock to async composite calculation; log failures for ops visibility
- [ ] AC10 Phase 2: Implement Kafka/queue DLQ for failed composite calculations; add replay consumer with exponential backoff
- [ ] AC11: Refactor VideoAccessGuard to handle request-scoped cache safely in singleton; add test helpers
- [ ] AC12: Restructure cascadeDeleteForAccount as single @Transactional block; add pre-delete quota audit
- [ ] AC13: Add null check for providerAssetId in PlaybackService.authorizePlayback
- [ ] AC14: Add @Lock(PESSIMISTIC_WRITE) to BookingService.transitionInternal or call site
- [ ] AC15: Audit BookingStatus.UPCOMING usage; add guard or document intentional proactive support
- [ ] AC16: Update deferred-work.md with [CLOSED by skillars-deferred-77 ACx] tags as listed above
- [ ] Run full targeted test sweep: payment (248), development (133), session (178), video (~200), booking (~300) tests
- [ ] Confirm no regressions; do NOT run `mvn verify` locally per validation-strategy.md

## Dev Notes

- This story bundles 16 acceptance criteria across 4 modules (Deployment/SLU/Radar focus, plus small Booking/Video hardening)

**Approved Design Decisions:**
- AC2: Async S3 I/O extraction APPROVED — S3 I/O will move to async post-commit handler with eventual-consistency error handling
- AC10: Both Phase 1 and Phase 2 APPROVED — implement pessimistic lock guard AND Kafka/queue DLQ infrastructure for failed composites
- AC15: Guard against UPCOMING status APPROVED — explicit false-return guard to prevent silent acceptance of unsupported state

**Highest-blast-radius changes:** 
- AC2 (ReportGenerationService async restructuring) — introduces async post-commit pattern; verify event listener error handling and eventual consistency
- AC14 (pessimistic lock on state machine) — adds database-level locking; verify no deadlock scenarios
- AC10 Phase 2 (DLQ infrastructure) — new infrastructure component; coordinate with ops on Kafka/queue topology

**Schema changes:** 
- AC9 requires V113 migration; re-verify max migration version before finalizing
- AC10 Phase 2 may require new table if using polling queue instead of Kafka

**Cross-module coordination:** 
- AC2 touches timeline (messaging domain) and S3 (infrastructure); ensure no integration surprises
- AC10 Phase 2 DLQ requires infrastructure discussion (Kafka vs polling table vs async task queue)

**Testing priorities:**
- AC2: Verify rollback behavior when S3 fails; verify timeline event only created on success
- AC10: Concurrent composite calculation serialization (Phase 1); DLQ replay under failure (Phase 2)
- AC12/AC14: Explicit concurrency tests with multiple parallel threads
- AC15: Test UPCOMING status rejection explicitly

## Change Log

| Date | Change |
|---|---|
| 2026-08-27 | Story created, 16 ACs bundling remaining actionable Deployment/SLU/Radar items plus cross-module hardening. Status: ready-for-dev |
