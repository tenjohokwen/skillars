# Story Deferred-77: SLU/Radar Robustness & Cross-Module Hardening

**Status:** done

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
- `SluContributionService.getCoachContributions` executes `UUID.fromString(row[0].toString())` at **two call sites** (lines 43-46, 62) without guard; throws `IllegalArgumentException` if value is not a valid UUID string
- `coachProfileService.getDisplayNamesByIds` call has no exception wrapping; propagates as 500 on failure
- Double iteration of `rows` list (once to calculate percentages, once to build DTOs)

**Context:** `coach_id` is a native Postgres UUID column, so pgjdbc driver returns it as `java.util.UUID` already; the `UUID.fromString` call is largely defensive (unlikely to fail under schema constraints) but reasonable defense-in-depth.

**Fix:**
1. Add UUID validation guard **at both call sites** (lines 43-46 and 62):
   ```java
   // At first call site (building coachIds map):
   if (!(row[0] instanceof UUID)) {
       throw new BadSqlGrammarException("coach_id from query is not a valid UUID", null);
   }
   UUID coachId = UUID.fromString(row[0].toString());
   
   // At second call site (DTO building loop):
   if (!(row[0] instanceof UUID)) {
       throw new BadSqlGrammarException("coach_id from query is not a valid UUID", null);
   }
   UUID coachId = UUID.fromString(row[0].toString());
   ```
   OR refactor to convert once into a `Map<Object, UUID>` up front to avoid duplication.

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

3. **Double iteration optimization:** The AC's proposal to "calculate percentages in-line during DTO building" is not achievable without buffering all rows first — ISO week rows are not guaranteed to be fully seen before starting percentage calculation unless you rely on the query's `ORDER BY skill_code` (fragile, implicit contract) or still do two passes. Either drop this sub-item or specify the grouping approach explicitly (buffer-first-then-calculate vs. ORDER BY guarantee).

**Testing:** Extend `SluContributionServiceTest` with cases for UUID parse error and display-name fetch failure; verify fallback behavior; verify guard fires at both call sites.

---

### AC2 — ReportGenerationService transaction restructuring (S3 I/O + timeline orphaning)

**Current behavior:**
- S3 calls (logo download, PDF upload) execute inside `@Transactional generateReport` method, holding DB connection for the duration
- `writeTimelineEvent` runs inside its own `REQUIRES_NEW` transaction; **re-audit shows timeline-event orphaning is likely unreachable** in current code (try/catch + exception swallowing in `generateReport` and downstream `notifyParent` means outer rollback won't trigger after REQUIRES_NEW commit), but S3 I/O inside DB transaction is still a connection-holding anti-pattern

**Caution:** The proposed async restructuring introduces a **new, critical user-facing race condition**. Today, S3 upload happens *before* the report row is saved; if DB save fails, the code cleans up orphaned S3 objects. Inverting this (publish event and save row, *then* async S3 upload) means `listReports()` can serve signed URLs to PDFs that don't exist yet (or ever, if async fails without cleanup). This breaks user experience (404 on a "ready" report).

**Fix:**

If timeline-event orphaning re-audit confirms it's unreachable, the story should either drop this AC or **redesign to avoid the broken-link race:**

1. **Add report status field** (e.g., `PENDING_UPLOAD` → `READY`):
   - Save report row with `status = PENDING_UPLOAD` (not visible to `listReports`)
   - Async S3 upload completes, then transitions `status = READY` in a separate update
   - `listReports` only returns `READY` reports, never leaves orphans visible to users

2. **Keep async S3 I/O extraction** (valid DB-connection improvement):
   - Extract S3 upload to async post-commit handler via `ReportGeneratedEvent`
   - Create separate cleanup logic if async upload fails (either retry DLQ or mark report as failed)

**Proposed code structure:**
```java
@Transactional
public Report generateReport(...) {
    // ... existing PDF generation logic, but save with status = PENDING_UPLOAD ...
    Report report = reportRepository.save(newReport().status(ReportStatus.PENDING_UPLOAD).build());
    eventPublisher.publishEvent(new ReportGeneratedEvent(this, report.getId(), pdfBytes));
    return report;
}

@Async
@Slf4j
public void uploadPdfAndLogTimeline(UUID reportId, byte[] pdfBytes) {
    try {
        s3Service.uploadReport(reportId, pdfBytes);
        // Update status to READY
        reportRepository.updateStatusById(reportId, ReportStatus.READY);
        writeTimelineEvent(reportId, ...); // Only after upload succeeds
    } catch (RuntimeException e) {
        log.error("Failed to upload PDF for report {}", reportId, e);
        reportRepository.updateStatusById(reportId, ReportStatus.UPLOAD_FAILED);
        // Consider DLQ for retry
    }
}
```

**Testing:** Add IT verifying: (1) report starts in PENDING_UPLOAD; (2) S3 failure transitions to UPLOAD_FAILED, not visible to `listReports`; (3) successful S3 upload transitions to READY and timeline event is created; (4) listReports never returns broken links.

---

### AC3 — ReportGenerationService stale branding logo validation [DROPPED — False Premise]

**Issue:** This AC targets the wrong method and its premise doesn't hold.

**Analysis:** 
- `buildPdf` only receives branding data when `tier == ACADEMY` (line 120-122: conditional fetch `tier == CoachSubscriptionTier.ACADEMY ? brandingRepository.findById(...) : Optional.empty()`). By definition, if `buildPdf` sees non-empty branding, the tier check already passed — the "downgrade then re-upgrade reuses stale key" scenario cannot occur through this call site.
- `CoachBranding` entity has no tier field and no history of upload tier — only `coachId`, `logoKey`, `brandColour`, `updatedAt`. A proposed `checkCoachLogoIsCurrentlyValid(coachId, logoKey)` would have nothing to compare against except the coach's current tier, which is guaranteed ACADEMY at the only call site that uses branding.

**Actual gap (different):** The stale-tier exposure exists in `ReportGenerationService.getBranding()` (lines 196-202), which returns logo/color with **no tier check at all** — but that's a different method than the one this AC targets. If left unfixed, `getBranding()` could serve stale data to non-ACADEMY coaches.

**Recommendation:** Either drop AC3, or retarget it at `getBranding()` with a concrete definition of what "invalid" means for a logoKey (currently, logo S3 objects are never deleted on tier downgrade, so "staleness" needs a specific invalidation strategy before a fix can be written).

---

### AC4 — PlayerProfileService parent email fetch optimization

**Current behavior:**
- `getParentEmailByPlayerId` executes 2 separate queries:
  1. `playerProfileRepository.getParentIdByPlayerId(playerId)` (returns `null` for self-registered adult with no parent)
  2. `userRepository.findById(parentId)` — **throws `IllegalArgumentException` if passed `null`**
- TOCTOU gap: parent account could be deleted between the two calls
- Self-registered adult players (those with no parent) hit the `findById(null)` case and fail with an unhandled exception

**Fix:**
1. Add new repository method `PlayerProfileRepository.findParentEmailByPlayerId(playerId)` using native query:
   ```sql
   SELECT u.email FROM main.user_account u
   JOIN main.player_profile p ON u.user_id = p.parent_id
   WHERE p.player_id = ?
   ```
   This automatically returns no row (null result) for self-registered adults with `parentId == null`, avoiding the two-call race entirely.

2. Update `getParentEmailByPlayerId` to use the new single-query method.

3. Root cause: self-registered adult players (with `parentId == null`) are the defect trigger — they reach the bare `findById(null)` call site. The JOIN query sidesteps this.

**Testing:** Add test cases for: (1) player with parent; (2) self-registered adult player without parent (returns null gracefully); (3) parent account deleted mid-operation (returns null gracefully).

---

### AC5 — SluRepository column name verification + fallback [CRITICAL FIX NEEDED]

**CRITICAL BUG:** The proposed schema query uses wrong schema qualifier. Will crash app startup.

**Current behavior:**
- Native queries in `SluRepository` reference `slu_value` and `calculated_at` columns in `player_skill_stats`
- Table is in `development` schema (confirmed: `PlayerSkillStat.java` and migration `V46__development_module_init.sql` both show `development.player_skill_stats`)
- Column names assumed but not verified against migration files

**Fix:**
1. **Verification step:** Verify table and column names against `src/main/resources/db/migration/V46__development_module_init.sql` (and any later V5x migrations touching `player_skill_stats`). Confirmed table is `development.player_skill_stats`, not `main.player_skill_stats`.

2. **Add defensive validation:** Create a `@PostConstruct` method in `SluRepository` that runs a test query and catches/logs any `BadSqlGrammarException`. **Use correct schema qualifier:**
   ```java
   @PostConstruct
   void validateSchemaMigration() {
       try {
           // CRITICAL: use 'development' schema, NOT 'main'
           entityManager.createNativeQuery("SELECT slu_value, calculated_at FROM development.player_skill_stats LIMIT 0").getResultList();
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

### AC6 — SluRepository findLastSessionDate filtering [DROPPED — False Premise]

**False Positive:** RadarAssessmentService does not write to `player_skill_stats`. 

**Analysis:**
- `RadarAssessmentService.java:87` writes to `development.radar_assessment_entries` table (confirmed in migration `V50__radar_assessment_entries.sql`)
- Only `SluCalculationService.onBookingCompleted` writes to `player_skill_stats`, and only for completed sessions (`BookingCompletedEvent`)
- The two tables are completely disjoint — no shared writes, no filtering needed
- `TimelineQueryService` design comment already correctly states: *"Report generation and radar assessments do NOT reset the access clock"* — this invariant is already upheld by the table separation
- The AC asks for a schema change that isn't needed

**Recommendation:** Drop AC6. If re-audit of RadarAssessmentService confirms it's out of scope for this story, add a one-line comment to `findLastSessionDate` documenting why no filtering is needed: *"Radar assessments write to radar_assessment_entries, not player_skill_stats; only session-driven SLU rows appear here."*

---

### AC7 — SluCalculationService ISO week boundary race handling [DROPPED — Already Implemented]

**False Positive:** Code already does exactly what this AC proposes.

**Analysis:**
- `SluCalculationService.java` line 153 captures `Instant now = Instant.now()` **once**
- Same `now` value is reused for **both** `PlayerSkillStat.calculatedAt` (line 167) **and** ISO week/year derivation for snapshot (lines 182-185)
- There is no second, independent timestamp capture to unify — the code already does this correctly

**Actual underlying concern (separate from timestamp):** The failure window between `saveAll` and `snapshotBatchWriter.writeAll` is real as a *reliability* concern (either could fail independently), but it's not a timestamp-consistency bug — it's a transactional coupling gap that requires retry/compensation logic (covered by AC8, not timestamp changes). The AC's own proposed fix doesn't address the coupling gap either.

**Status:** Already correctly implemented. Drop AC7.

---

### AC8 — SluCalculationService retry on saveAll failure

**Current behavior:**
- `sluRepository.saveAll` has no retry; transient DB errors permanently lose SLU data
- Dev notes provide recovery query, but requires manual ops intervention
- `spring-retry` dependency is not currently in `pom.xml`

**Critical Implementation Notes:**
1. **Self-invocation defeats @Retryable:** `@Retryable` is an AOP proxy-based annotation (like `@Transactional`). If `saveSluWithRetry` is called from `onBookingCompleted` within the same class via `this.saveSluWithRetry(...)`, the call bypasses the Spring proxy and **the retry logic will never fire**. This codebase has existing patterns for this (see `BookingService.acceptAndInitiatePayment`'s doc comment and `TimelineEventListener`'s `@Lazy @Autowired self` field).
   - **Solution:** Place `saveSluWithRetry` in a **separate `@Component`/bean**, not the same class as the caller
   - Inject it into `SluCalculationService` and invoke via injection, not `this.`

2. **New dependency:** Add `spring-retry` to `pom.xml` (not currently present; this AC introduces it as a new third-party dependency).

**Fix:**
1. Create separate `@Component` (e.g., `SluPersistenceRetrier`):
   ```java
   @Component
   public class SluPersistenceRetrier {
       @Autowired private SluRepository sluRepository;
       
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
   }
   ```

2. Inject into `SluCalculationService` and invoke through injection (not self):
   ```java
   @Autowired private SluPersistenceRetrier sluRetrier;
   
   // In onBookingCompleted:
   sluRetrier.saveSluWithRetry(sluRows); // NOT this.saveSluWithRetry(...)
   ```

3. Configure `retryTemplate` with exponential backoff (e.g., 3 retries, 100ms initial delay).

**Testing:** Add IT that mocks transient DB error on first call, succeeds on second; verify retry succeeds. Verify self-invocation test case would fail (confirm proxy is being used).

---

### AC9 — Development module orphaned radar composites cascade [CRITICAL FIXES NEEDED]

**CRITICAL BUG:** The migration SQL as written has two concrete errors and will fail at migration time.

**Current behavior:**
- `player_radar_composites` has no FK to `player_profiles` (confirmed in `V50__radar_assessment_entries.sql`)
- Player deletion leaves orphaned composite rows
- Sibling table `player_radar_baselines` has the identical missing-FK problem on `player_id` (confirmed in `V51__radar_display_correlation.sql`), but is NOT addressed by this AC

**Critical Issues in Proposed Migration:**
1. **Wrong schema:** Table is `development.player_radar_composites`, not `main` (confirmed by entity `@Table(schema = "development", name = "player_radar_composites")` and existing V98 migrations). Query against `main.player_radar_composites` will fail — table doesn't exist.
2. **Wrong FK target column:** `main.player_profiles` primary key is `id`, not `player_id` (confirmed by existing FK pattern in `V22__parent_player_shadow_accounts.sql`: `REFERENCES main.player_profiles(id)`). No unique/PK constraint exists on `player_profiles(player_id)`.

**Fix:**
1. **Schema migration V113:** Add FK with ON DELETE CASCADE to `development` schema and correct column:
   ```sql
   ALTER TABLE development.player_radar_composites 
   ADD CONSTRAINT fk_prc_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;
   ```

2. **Scope clarification:** `player_radar_baselines` has the exact same missing-FK problem and is tracked in deferred-work.md's skillars-5-4 section (W1: "No FK from `player_radar_baselines.player_id`..."). Is this AC in-scope or out-of-scope for `baselines`?
   - If **in-scope:** Also add in same migration or V114:
     ```sql
     ALTER TABLE development.player_radar_baselines 
     ADD CONSTRAINT fk_prb_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id) ON DELETE CASCADE;
     ```
   - If **out-of-scope:** Document explicitly in AC16 ledger tag (don't leave as implicit oversight).

3. **Test:** Verify existing orphaned rows (if any) are identified and documented; create a separate cleanup task if needed.

**Testing:** Add IT verifying player deletion cascades to both `player_radar_composites` (and `player_radar_baselines` if in-scope).

---

### AC10 — Async composite calculation race condition & DLQ infrastructure

**Current behavior:**
- `RadarCompositeCalculationService.onRadarEntrySubmitted` uses `@Async` with no retry/dead-letter queue
- Concurrent submissions for same player both query aggregates before either upserts (last-writer-wins)
- Listener failure leaves composite stale until next submission

**Fix:**

**Phase 1:** Add pessimistic locking guard
1. Add query-then-upsert lock (pessimistic write on player row). **Use the existing codebase pattern** (see `BookingService.java:244-253`, `:337-349`, `:675-680` and `PlaybackService.java:130-136`):
   ```java
   @Transactional
   public void recalculateComposite(Long playerId) {
       // Use PessimisticLockRetryer pattern (codebase standard) — NOT a bare findByIdForUpdate
       lockRetryer.withBoundedRetry(() -> {
           PlayerProfile playerProfile = playerProfileRepository.findByIdForUpdate(playerId);
           entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE); // Critical: refresh after lock
           // ... fetch aggregates and upsert composite ...
       });
   }
   ```
   **Critical:** A bare `findByIdForUpdate` without explicit `entityManager.refresh()` can silently return a stale in-memory instance if the entity was already loaded (Hibernate bypasses re-reading from DB). The codebase standard pattern pairs the lock call with an explicit refresh.

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

### AC11 — VideoAccessGuard request-scoped cache in singleton [DROPPED — False Positive]

**False Positive:** The current design is correct and already follows Spring best practices.

**Analysis:**
- `VideoAccessCache` is annotated with `@RequestScope` (Spring's standard request-scope annotation), which auto-generates a CGLIB proxy with default `ScopedProxyMode.TARGET_CLASS`
- This proxy mechanism makes it safe to inject request-scoped beans into singletons — Spring transparently delegates to the correct per-request instance
- This is **standard, correct Spring practice**, not a workaround needing "fixing"
- deferred-work.md's own source item (W1, skillars-6-5 section) already correctly identified this: *"standard Spring proxy pattern; correct in web context; only fails in bare non-web unit tests (mocked there anyway)"*

**Current state:** Expected, already-mitigated behavior. The test-setup friction (manual mocking in unit tests outside web context) is not a production defect.

**Recommendation:** Drop AC11. Implementing the proposed `ApplicationContext.getBean()` lookup would step backwards (loses Spring's built-in proxy caching/thread-safety guarantees) to solve a problem that's already solved.

---

### AC12 — VideoDeletionService cascadeDeleteForAccount atomicity [HIGH-SEVERITY FIX REQUIRED]

**CRITICAL ISSUE:** The proposed fix as written bypasses the entire deletion pipeline.

**Current behavior:**
- `cascadeDeleteForAccount` quota reset is non-atomic: JVM crash after per-video commits but before `resetBytesForOwner()` leaves quota row permanently non-zero
- Additionally, quota reset happens **unconditionally after the deletion loop**, even if some deletions failed — leaving deleted-but-not-reset rows
- No retry path corrects this

**Critical Issue with Proposed Fix:** The AC proposes replacing per-video `deleteVideo(...)` calls with a batch `videoRepository.deleteAllByOwnerId(...)`. This is **unsafe** — `deleteVideo()` is not a simple row DELETE:
1. It's a **soft-delete state transition** (sets `operationalState = PURGED`)
2. It writes `VideoDeletionLog` audit rows per video (for compliance/reconciliation)
3. It inserts `VideoDeletionOutbox` rows (async processor uses these to call Bunny.net provider's `deleteAsset()` for remote asset cleanup)
4. It publishes `VideoPurgedEvent` after-commit (downstream listeners expect this)

A raw batch DELETE skips **all three**, meaning:
- Remote Bunny.net assets are **never cleaned up** (no outbox row, provider never called)
- No deletion audit trail (compliance gap)
- No `VideoPurgedEvent` published (downstream listeners don't fire)

**Correct Fix:** Keep the per-video `deleteVideo()` calls (they handle all the pipeline correctly) and instead make **quota reset conditional**:
```java
@Transactional
public void cascadeDeleteForAccount(Long accountId) {
    List<Long> failedVideoIds = new ArrayList<>();
    
    for (Video video : videoRepository.findByOwnerId(accountId)) {
        try {
            deleteVideo(video.getId()); // Soft-delete with full pipeline
        } catch (Exception e) {
            log.error("Failed to delete video {} for account {}", video.getId(), accountId, e);
            failedVideoIds.add(video.getId());
        }
    }
    
    // Reset quota ONLY if all deletions succeeded
    if (failedVideoIds.isEmpty()) {
        quotaRepository.resetBytesForOwner(accountId);
    } else {
        log.warn("Account {} deletion incomplete; {} videos failed. Quota NOT reset for reconciliation.",
            accountId, failedVideoIds.size());
        // Emit metric for ops alerting
    }
}
```

**Alternative:** If a single-transaction batch approach is needed for performance reasons, add compensating cleanup:
```java
@Transactional
public void cascadeDeleteForAccountAtomic(Long accountId) {
    // Batch delete with condition to keep pipeline
    List<Video> videos = videoRepository.findByOwnerId(accountId);
    for (Video v : videos) {
        deleteVideo(v.getId()); // Still invokes full pipeline
    }
    
    // Single quota reset at the end (atomic with deletions)
    quotaRepository.resetBytesForOwner(accountId);
    
    // Add pre-delete audit log
    log.info("Account {} cascade deletion complete; {} videos purged", accountId, videos.size());
}
```

**Testing:** 
- Verify single-deletion failure doesn't reset quota (new conditional branch)
- Verify all-success path does reset quota atomically
- Verify outbox/audit/event pipeline is fully invoked
- Verify Bunny.net cleanup is triggered (outbox entries created)

---

### AC13 — VideoPlaybackService parent-play/PURGE race null check

**Current behavior:**
- `PlaybackService.authorizePlayback` called with potentially-null `providerAssetId` if video is concurrently purged between `@PreAuthorize` canPlay evaluation and the call

**Pre-implementation verification required:** Confirm the race window actually exists. Analysis suggests it may already be closed:
- `VideoDeletionService.deleteVideo` sets `operationalState = PURGED` synchronously (same transaction)
- `PlaybackService.authorizePlayback` re-reads video fresh via `findById` (line 67-68) and **checks `operationalState` before using `providerAssetId`** (rejects `PURGED`/`DELETED` at lines 70-101)
- `providerAssetId` is nulled later, only in async outbox processing (`completeRowWithNullAsset()`), strictly **after** `operationalState` is set to `PURGED`
- If `providerAssetId` is null, `operationalState` is guaranteed to be `PURGED` (already checked and rejected)

**Before implementing:** Verify `completeRowWithNullAsset()` ordering (5-minute check) to confirm `operationalState` transition always precedes `providerAssetId` nulling. If confirmed, this AC adds defensive belt-and-braces code for an already-unreachable path (low value).

**Fix (if race still exists after verification):**
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

**Testing:** Add IT simulating concurrent purge during authorization; verify 404 returned cleanly. If implementing, also document the ordering assumption (`operationalState` precedes `providerAssetId` nulling).

---

### AC14 — BookingService.transition pessimistic lock (Booking/Availability)

**Context:** Gap exists and is real, but the proposed fix code is invalid Spring/JPA and the risk framing overstates the problem.

**Current behavior:**
- No pessimistic lock on `BookingService.transition()` state machine
- Concurrent callers can both pass validation on the same booking
- **However:** `Booking` already carries `@Version` for **optimistic locking** (confirmed `Booking.java:54`), and most `transition()` call sites already wrap calls in try-catch for `OptimisticLockingFailureException`, returning clean 409-equivalent errors (visible at `declineBooking`, `cancelDueToPause`, `cancelBookingAsCoach`, `recordNoShowPlayer`, `recordNoShowCoach`, `acceptBooking`)
- Concurrent state corruption does **not** occur today; what pessimistic locking changes is UX (fail-fast-and-retry vs. blocking)

**Critical Issue with Primary Fix:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a **private method in a service class** has no effect:
- `@Lock` is a **Spring Data repository-interface annotation** — it only works on methods declared in `JpaRepository`/`Repository` interfaces, where Spring Data generates the query implementation and attaches lock hints
- It has no meaning on arbitrary service class methods
- Even if it did, **AOP-based annotations (like `@Transactional`) cannot apply to private methods** for any framework — self-invocation bypasses proxies

**Correct Fix:** Use the codebase's established `PessimisticLockRetryer` pattern (not `@Lock` decorator):
```java
@Transactional
public BookingTransitionResult transition(UUID bookingId, BookingEvent event) {
    // Use established codebase pattern (see BookingService:337-349, :675-680)
    return lockRetryer.withBoundedRetry(() -> {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId);
        entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE); // Critical: explicit refresh
        return transitionInternal(booking, event);
    });
}
```

**Reframe "Current behavior":** Optimistic locking is already active. Concurrent losers get clean 409-equivalent errors (not silent corruption). Pessimistic locking is a UX optimization (blocking instead of fail-fast), not a correctness fix — state explicitly as the motivation.

**Testing:** 
- Add IT with concurrent transition attempts; verify one succeeds and the other is blocked then either succeeds on retry or gets clean error
- Document that the motivation is UX (blocking vs. retry), not preventing silent corruption (already prevented by `@Version`)
- Verify no deadlock scenarios under heavy concurrent load

---

### AC15 — SessionPlanService/SessionTemplateService isBookingPlannable status guard [CRITICAL FIXES REQUIRED]

**CRITICAL ISSUES:** This AC targets the **wrong file/module**, uses **non-existent statuses**, and **misses a duplicate copy**.

**Issue 1 — Wrong file and module:**
- `isBookingPlannable` does **NOT** exist in `BookingService` (booking module)
- It exists as **two separate, independent private methods**:
  1. `SessionPlanService.java:207` (session module)
  2. `SessionTemplateService.java:169` (session module)
- Both have identical bodies accepting `"CONFIRMED"` and `"UPCOMING"` statuses
- deferred-work.md's own source item (W8, skillars-4-4/4-5 session-builder review) correctly attributes it to `SessionPlanService`, not `BookingService`

**Issue 2 — Non-existent status set breaks confirmed-booking session planning:**
The AC's proposed fix:
```java
Set<String> supportedStates = Set.of("ACTIVE", "PENDING");
```
- `"ACTIVE"` and `"PENDING"` are **not booking statuses** used anywhere in this codebase
- Authoritative list: `BookingService.ACTIVE_SLOT_STATUSES` = `REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, PAUSED`
- The actual current, working statuses accepted by `isBookingPlannable` are `"CONFIRMED"` and `"UPCOMING"`
- **If implemented literally, this would return `false` for `"CONFIRMED"`**, breaking session-plan and session-template creation for every confirmed booking today

**Issue 3 — Misses duplicate copy:**
Two independent copies of this method exist; a fix needs to update **both** or deduplicate them into one shared location. The AC's single-method code sample gives no indication.

**Correct Fix:**
1. **Update both copies** in `SessionPlanService.java:207` and `SessionTemplateService.java:169`:
   ```java
   // If guarding against UPCOMING (status with no transition path):
   private boolean isBookingPlannable(String status) {
       return "CONFIRMED".equals(status); // Accept only CONFIRMED, reject UPCOMING explicitly
   }
   ```
   OR if wanting explicit guard with logging:
   ```java
   private boolean isBookingPlannable(String status) {
       if ("UPCOMING".equals(status)) {
           log.warn("isBookingPlannable called with UPCOMING status; no transition path exists");
           return false;
       }
       if ("CONFIRMED".equals(status)) {
           return true;
       }
       log.warn("isBookingPlannable called with unexpected status: {}", status);
       return false;
   }
   ```

2. **OR deduplicate** into a shared utility if this pattern recurs elsewhere.

**Rationale:** `UPCOMING` is a status Booking can transition to, but `isBookingPlannable` documents that it shouldn't be used for session planning (it has no onward state transition). If UPCOMING support is later added, both copies need updating.

**Testing:** 
- Add test for `CONFIRMED` returning true (the live status coaches use today)
- Add test for `UPCOMING` returning false with warning log
- Add test for unknown statuses returning false
- **Verify both `SessionPlanService` and `SessionTemplateService` copies are updated**
- Verify existing session-plan creation for confirmed bookings is not broken

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

4. Section "Deferred from: code review of skillars-5-4..." Items fixed:
   - W1 (no FK on radar baselines): append `[AUDIT 2026-08-27: AC9 addresses player_radar_composites FK; player_radar_baselines scope clarified in AC9]`
   - (Note: AC9 targets skillars-5-4, not skillars-5-1; skillars-5-1's W1 is about negative SluFormula metadata fields, already closed by deferred-76 AC10)

5. Section "Deferred from: code review of skillars-5-2..." Items fixed:
   - DEF3/DEF4 (async composite race): append `[CLOSED by skillars-deferred-77 AC10 Phase 1 (pessimistic lock guard); Phase 2 DLQ infrastructure deferred]`
   - DEF6 (orphaned radar composites): append `[CLOSED by skillars-deferred-77 AC9]`

6. Section on SluCalculationService:
   - Note: AC7 is dropped (false positive — timestamp reuse already implemented); **do NOT apply AC7 tag**
   - If consolidating ISO week boundary work: W1, W2, and D6 all address the saveAll→writeAll failure gap — document consolidated resolution approach
   - D1 (no retry on saveAll): append `[CLOSED by skillars-deferred-77 AC8]`

7. Section on VideoAccessGuard:
   - W1 (request-scoped cache): append `[CLOSED by skillars-deferred-77 AC11]`

8. Section on VideoDeletionService:
   - W8 (quota reset non-atomic): append `[CLOSED by skillars-deferred-77 AC12]`

9. Section on PlaybackService:
   - W10 (parent-play/PURGE race): append `[CLOSED by skillars-deferred-77 AC13]`

10. Section on BookingService:
    - "No pessimistic lock on transition()": append `[CLOSED by skillars-deferred-77 AC14]`

    (Note: W8 isBookingPlannable is NOT in BookingService — it's in SessionPlanService.java:207 and SessionTemplateService.java:169, under skillars-4-4/4-5 session-builder/template review sections. Apply tag there instead, not in BookingService section.)

---

## Tasks / Subtasks

**Dropped ACs (false positives — no implementation needed):**
- [x] AC3: DROPPED — False premise (targets wrong method); getBranding() stale-tier gap filed as a new deferred-work.md item (not fixed — needs a design decision on what "invalid" means for a post-downgrade logoKey)
- [x] AC6: DROPPED — False premise (RadarAssessmentService doesn't write player_skill_stats); comment added to SluRepository.findLastSessionDate explaining table disjointness
- [x] AC7: DROPPED — Already implemented (timestamp reuse is current code); deferred-work.md D6/W2 marked AUDIT, not CLOSED, per Dev Notes instruction
- [x] AC11: DROPPED — False positive (current Spring proxy pattern is correct)

**ACs requiring fixes before implementation:**
- [x] AC1: Added UUID validation guard consolidated into a single `Map<Object, UUID>` conversion pass (buffer-first approach) shared by both the batch-lookup and DTO-building loops in SluContributionService; wrapped `getDisplayNamesByIds` in try-catch with empty-map fallback
- [x] AC2: **Redesign shipped:** Added `ReportStatus` (PENDING_UPLOAD/READY/UPLOAD_FAILED) to `performance_reports` (V115); `generateReport` now saves PENDING_UPLOAD and publishes `ReportGeneratedEvent`; new async `onReportGenerated` (`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`) does the S3 upload, flips to READY/UPLOAD_FAILED, writes the timeline event, and notifies the parent — none of which now happen inside the original DB transaction. `listReports` only queries READY reports.
- [x] AC4: Implemented `PlayerProfileRepository.findParentEmailByPlayerId` single-query JOIN; self-registered adults and a since-deleted parent both fall through to no row instead of the old `findById(null)` crash
- [x] AC5: Verified schema was already correct (`development.player_skill_stats`, confirmed against V46) — the AC's premise of a `main`-schema bug was itself stale; added `DevelopmentConfig.validateSluRepositorySchema()` `@PostConstruct` guard so future drift fails loud at startup
- [x] AC8: Separate `@Component` `SluPersistenceRetrier` with `@Retryable`/`@Recover`, injected into `SluCalculationService` (no self-invocation); spring-retry was already a pom.xml dependency with `@EnableRetry` already active — the AC's premise that it needed adding was stale
- [x] AC9: V113 migration adds `ON DELETE CASCADE` FK to `main.player_profiles(id)` for both `player_radar_composites` AND `player_radar_baselines` (correct schema/column throughout, no `main`/`player_id`-vs-`id` bugs — the AC's cited bugs weren't present in the actual query I wrote)
- [x] AC10 Phase 1: `RadarCompositeCalculationService.recalculateComposite` acquires a `PessimisticLockRetryer`-backed lock on the player's row (`findByIdForUpdate` + explicit `entityManager.refresh`) via a new `@Lazy @Autowired self` field (mirrors `TimelineEventListener`) to avoid the self-invocation pitfall
- [x] AC10 Phase 2: New `development.radar_composite_dlq` table (V114) + `RadarCompositeDlqService`/`RadarCompositeDlqProcessor`, mirroring `VideoDeletionOutboxProcessor`'s claim/backoff/dead-letter shape (no Kafka in this codebase); `radar.composite.dlq.count` Micrometer counter added
- [x] AC12: Per-video `deleteVideo()` calls were already correct (no batch-DELETE bug present); made quota reset conditional on `failedIds.isEmpty()`, with a warning log on partial failure
- [x] AC13: Verified `completeRowWithNullAsset()` ordering — `operationalState=PURGED` is always set synchronously before the outbox row that triggers `providerAssetId` nulling even exists; race confirmed unreachable, no defensive code added (matches this codebase's convention against guarding scenarios that can't happen)
- [x] AC14: `BookingService.transitionInternal` now uses the established `PessimisticLockRetryer` + `findByIdForUpdate` + `entityManager.refresh` pattern; confirmed with the project owner this is a deliberate UX improvement (blocking vs. fail-fast), not a correctness fix — `@Version` already prevented silent corruption
- [x] AC15: Confirmed with the project owner to implement as specified — `isBookingPlannable` now accepts only `"CONFIRMED"` and explicitly rejects `"UPCOMING"` with a warning log, in both `SessionPlanService.java` and `SessionTemplateService.java` (not `BookingService`, which doesn't contain this method)

**Shared work:**
- [x] AC16: deferred-work.md updated — corrected section references and tags applied per Dev Notes, plus a new "Deferred from: code review of skillars-deferred-77" section for newly-discovered items not fixed by this story (getBranding() gap, a VideoDeletionService self-invocation issue, and pre-existing unrelated test failures found during the regression sweep)
- [x] Ran full targeted test sweep: booking (373/373), development (150/150), session (181/181), video (283/283, 4 pre-existing unrelated skips), payment (248 total — 2 pre-existing unrelated failures from skillars-deferred-76, confirmed via git log and reproduced on master with this story's diff stashed), marketplace+security (275 total — 12 pre-existing unrelated LoginAttemptsServiceTest failures, confirmed reproduced on master with this story's diff stashed)
- [x] Confirmed no regressions; did NOT run `mvn verify` locally per validation-strategy.md

## Dev Notes

**Critical Issues Requiring Fixes Before Implementation:**

1. **AC5 — CRITICAL (app startup crash):** Schema qualifier is wrong (`main` instead of `development`). Will throw `AppSetupException` at startup in every environment if implemented as written. **Must fix schema to `development.player_skill_stats` before coding.**

2. **AC15 — CRITICAL (breaks live functionality):** (a) Targets wrong file/module (SessionPlanService/SessionTemplateService, not BookingService); (b) Proposed statuses (`"ACTIVE"`, `"PENDING"`) don't exist — would reject `"CONFIRMED"` and break confirmed-booking session planning today; (c) Misses second duplicate copy in SessionTemplateService. **Must correct file, statuses, and both copies.**

3. **AC9 — CRITICAL (migration fails):** (a) Wrong schema (`main` vs. `development`); (b) Wrong FK target (`player_profiles(player_id)` vs. `player_profiles(id)`). Migration will fail at runtime. **Must fix schema and column references.**

4. **AC2 — HIGH (user-facing broken links):** Proposed async restructuring introduces new race condition — report rows saved before PDF upload, so `listReports()` can serve signed URLs to non-existent PDFs. **Requires redesign: add report status field (PENDING_UPLOAD → READY) to hide unreachable PDFs from users.**

5. **AC12 — HIGH (breaks external-asset cleanup):** Proposed batch `deleteAllByOwnerId` bypasses the entire soft-delete pipeline (outbox, audit log, VideoPurgedEvent). Remote Bunny.net assets would orphan. **Must keep per-video `deleteVideo()` calls and make quota reset conditional.**

**False Positives (drop without implementation):**
- AC3: Targets wrong method (`buildPdf` is gated, can't see stale data); real gap is in `getBranding()` (separate effort)
- AC6: RadarAssessmentService doesn't write player_skill_stats; tables are disjoint (no filtering needed)
- AC7: Already implemented (timestamp reuse is current code)
- AC11: Current Spring proxy pattern is correct; implementing ApplicationContext.getBean() would step backwards

**Approved Design Decisions (to preserve from pre-review):**
- AC2: Async S3 I/O extraction pattern APPROVED (once redesigned to avoid broken-link race with status field)
- AC10: Both Phase 1 (pessimistic lock) and Phase 2 (DLQ infrastructure) APPROVED
- AC14: Pessimistic locking for UX improvement APPROVED (fail-fast vs. blocking, not correctness fix)

**Implementation Constraints:**
- AC8: `@Retryable` must live in separate `@Component` (SluPersistenceRetrier), NOT self-invoked from SluCalculationService — add spring-retry dependency
- AC10 Phase 1: Use codebase's established `PessimisticLockRetryer` pattern with explicit `entityManager.refresh()`, not bare `findByIdForUpdate`
- AC14: Use `PessimisticLockRetryer` pattern (not invalid `@Lock` annotation on private method)
- AC15: Update **both** SessionPlanService and SessionTemplateService (not just one)

**Highest-blast-radius changes (post-fixes):**
- AC2 (async + status field) — introduces async post-commit + visibility gating; verify event listener error handling and eventual consistency
- AC12 (conditional quota reset) — changes delete flow; verify all deletion paths (soft-delete, quota, outbox, audit, events) complete correctly
- AC14 (pessimistic lock) — adds database-level locking; verify no deadlock scenarios

**Schema changes:** 
- AC5, AC9 require migrations; verify correct schemas (`development`, not `main`) and column names before finalizing
- AC10 Phase 2 may require new DLQ table if using polling queue

**Cross-module coordination:** 
- AC2 touches timeline (messaging domain) and S3 (infrastructure)
- AC10 Phase 2 DLQ requires infrastructure discussion (Kafka vs. polling table)

**Testing priorities:**
- AC2: Verify report status gates visibility; broken links never reached by users
- AC5, AC9: Schema validation tests (ensure migration succeeds)
- AC8: Verify retry fires only when bean is injected, not on self-invocation
- AC10: Concurrent serialization (Phase 1); DLQ replay on async failure (Phase 2)
- AC12: Verify all soft-delete side effects (audit log, outbox, event) fire before quota reset
- AC14: Concurrent state machine under lock; no deadlock under heavy load
- AC15: Verify `"CONFIRMED"` accepted and `"UPCOMING"` rejected in both SessionPlanService and SessionTemplateService

## Dev Agent Record

### Completion Notes

All 16 ACs addressed: 4 dropped as confirmed false positives (AC3, AC6, AC7, AC11 — no code change, documented in deferred-work.md), 12 implemented (AC1, AC2, AC4, AC5, AC8, AC9, AC10 Phase 1+2, AC12, AC13 [verification only, no code change], AC14, AC15), plus AC16 ledger hygiene and the full targeted test sweep.

Two product decisions were confirmed directly with the project owner during implementation (both already anticipated as open questions in the story's own Dev Notes):
- **AC15**: `isBookingPlannable` now rejects `UPCOMING` (accepts only `CONFIRMED`), a real behavior change — a coach can no longer create a session plan for the first time during the ~24h pre-session window once `BookingReminderScheduler` has flipped a booking to `UPCOMING`. Confirmed as the desired behavior rather than treating it as a false positive.
- **skillars-deferred-77 not yet in sprint-status.yaml**: confirmed with the project owner to proceed with this story despite the tracking gap (story file already existed with `ready-for-dev` status, matched the current branch and most recent commits); added the missing entry.

Notable deviations from the story's own proposed fixes, found during implementation (not just at story-creation time):
- **AC1**: consolidated the UUID guard into one `Map<Object, UUID>` conversion pass shared by both loops (the story's own "OR refactor to convert once" alternative), rather than duplicating the guard at both call sites.
- **AC5/AC9**: the story's own critical-fix corrections (schema `development` not `main`; FK target `player_profiles(id)` not `player_profiles(player_id)`) were verified against the live migrations before writing any code, so the code I wrote used the correct schema/column from the start rather than needing a later fix.
- **AC8**: spring-retry was already a `pom.xml` dependency with `@EnableRetry` already active in `SkillarsApplication` — the story's premise that it needed adding was stale; only the separate-bean/`@Retryable` wiring itself was net-new.
- **AC9**: implemented the FK for **both** `player_radar_composites` and `player_radar_baselines` in one migration (the story left this as an open scope question) — both tables have the identical missing-FK problem and the fix is identical, so there was no reason to split it.
- **AC10 Phase 2**: this codebase has no Kafka; built the DLQ as a Postgres polling-queue table mirroring `VideoDeletionOutboxProcessor`'s already-established claim/backoff/dead-letter shape (one of the story's own suggested alternatives).
- **AC14**: introduced a real, wide blast radius — 13 `BookingServiceTest` tests needed a new `findByIdForUpdate` stub added alongside their existing `findById` stub, since `transitionInternal` now acquires a lock before the state-machine validation those tests exercise. Fixed all 13; full booking-module suite (373 tests) green afterward.
- **AC2**: kept the existing "S3 first, cleanup-on-DB-failure-after" ordering's *spirit* but flipped it — DB row now saves first (PENDING_UPLOAD, no S3 dependency to clean up if that save fails), S3 upload happens async afterward. This is simpler than the story's own proposed code sample and needed no orphan-cleanup logic at all, since nothing is ever written to S3 before the row exists.

Two real, pre-existing bugs were found but **not fixed** (out of this story's AC scope) and filed as new deferred-work.md items instead:
- `ReportGenerationService.getBranding()` has no tier check at all (a coach downgraded from ACADEMY still gets served their old branding) — AC3's own investigation surfaced this as the *actual* gap behind the ledger item AC3 targeted at the wrong method.
- `VideoDeletionService.cascadeDeleteForAccount` calls `deleteVideo()` via plain self-invocation, bypassing its `@Transactional` proxy — contradicts the method's own doc comment. Found during AC12 work; AC12 only required making quota reset conditional, not fixing this separate mechanism.

Regression sweep also surfaced two categories of **pre-existing, unrelated** test failures (confirmed via `git log` + reproduced identically on master with this story's entire diff stashed, including untracked files) — not fixed, filed to deferred-work.md: `BookingPaymentPersistenceServiceTest`/`StripeWebhookVerificationTest` (both from `skillars-deferred-76`'s payment-alerting counters), and `LoginAttemptsServiceTest` (order-dependent, fails only when run in the same Surefire invocation as the rest of the marketplace+security modules).

`mvn verify` was not run locally per `docs/validation-strategy.md` — GitHub CI is the sole full-verification gate.

### File List

**New files:**
- `src/main/java/com/softropic/skillars/platform/development/contract/ReportGeneratedEvent.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/ReportStatus.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqEntry.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessor.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrier.java`
- `src/main/resources/db/migration/V113__radar_composite_baseline_player_fk.sql`
- `src/main/resources/db/migration/V114__radar_composite_dlq.sql`
- `src/main/resources/db/migration/V115__performance_report_status.sql`
- `src/test/java/com/softropic/skillars/platform/development/config/DevelopmentConfigTest.java`
- `src/test/java/com/softropic/skillars/platform/development/repo/RadarCompositeBaselinePlayerFkIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqProcessorTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeDlqServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluContributionServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrierTest.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/service/PlayerProfileServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepositoryIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionServiceTest.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PerformanceReport.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PerformanceReportRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/SluRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluContributionService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/PlayerProfileService.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoDeletionService.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculatorTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/session/service/SessionPlanServiceTest.java`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

| Date | Change |
|---|---|
| 2026-08-27 | Story marked done per project owner instruction; staged, committed, pushed, and opened as a PR for CI-gated merge. |
| 2026-08-27 | Dev-story implementation complete, status ready-for-dev → review. All 16 ACs addressed (12 implemented, 4 confirmed-dropped false positives); full targeted test sweep green (booking 373/373, development 150/150, session 181/181, video 283/283); 2 product decisions confirmed with project owner (AC15 UPCOMING-rejection behavior change; proceeding with story despite sprint-status.yaml tracking gap); 2 pre-existing, unrelated bugs found and filed to deferred-work.md without fixing (ReportGenerationService.getBranding() tier gap; VideoDeletionService self-invocation); 3 categories of pre-existing unrelated test failures found during regression sweep and confirmed via git-stash reproduction on master, not fixed. Full detail in Dev Agent Record above. |
| 2026-08-27 | Senior dev review completed; critical defects found (AC5 schema crash, AC15 wrong file/statuses, AC9 migration errors, AC2/AC12 pipeline issues). Story updated with corrections: 4 ACs dropped as false positives, 5 ACs flagged for design fixes before implementation, remaining ACs updated with corrected code/schema/file references. Status: awaiting implementation team review and fixes. |
| 2026-08-27 | Story created, 16 ACs bundling remaining actionable Deployment/SLU/Radar items plus cross-module hardening. Status: ready-for-dev |
