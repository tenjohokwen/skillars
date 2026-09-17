# Blind Hunter Review Prompt
**Reviewer Role:** Blind Hunter (no project context — diff only)
**Story:** skillars-deferred-119
**Date:** 2026-09-17

## Your Task

You are a cynical, adversarial code reviewer. Review the provided diff with zero project context — you have ONLY the code changes, no spec, no story, no architecture docs.

Find at least 10 issues. Look for:
- Logic errors, off-by-one bugs, race conditions
- Missing null checks, incomplete error handling
- Inconsistent naming, dead code, unreachable branches
- Incorrect type coercion, implicit behavior changes
- Concurrency issues, resource leaks
- Changes that contradict each other or the diff title

**DIFF CONTENT:**

```diff
diff --git a/src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObjectRepository.java b/src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObjectRepository.java
index 123..456 100644
--- a/src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObjectRepository.java
+++ b/src/main/java/com/softropic/skillars/platform/filestorage/repo/FileStorageObjectRepository.java
@@ -23,11 +23,15 @@ public interface FileStorageObjectRepository extends JpaRepository<FileStorageO
   @Query("SELECT f FROM FileStorageObject f WHERE f.id = :id AND f.deletedAt IS NULL")
   Optional<FileStorageObject> findByKeyAndDeletedAtIsNull(@Param("id") String id);
 
-  @Transactional
-  @Query(nativeQuery = true, value = "UPDATE file_storage_object SET physical_deleted_at = :ts WHERE id = :id")
-  void markPhysicallyDeleted(@Param("id") UUID id, @Param("ts") Instant ts);
+  @Query(nativeQuery = true, value = "UPDATE file_storage_object SET physical_deleted_at = :ts WHERE id = :id AND physical_deleted_at IS NULL")
+  int markPhysicallyDeleted(@Param("id") UUID id, @Param("ts") Instant ts);
 
   @Transactional
   @Query(nativeQuery = true, value = "DELETE FROM file_storage_object WHERE id = :id")
   void softDeleteByKey(@Param("id") UUID id);

diff --git a/src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java b/src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java
index 789..012 100644
--- a/src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java
+++ b/src/main/java/com/softropic/skillars/platform/filestorage/service/DeletionSchedulerService.java
@@ -1,6 +1,7 @@
 package com.softropic.skillars.platform.filestorage.service;
 
 import lombok.RequiredArgsConstructor;
+import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
 import org.springframework.scheduling.annotation.Scheduled;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.support.TransactionTemplate;
@@ -25,12 +26,25 @@ public class DeletionSchedulerService {
     this.logger = LoggerFactory.getLogger(getClass());
   }
 
-  @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
+  /**
+   * Deletes expired file storage objects. Runs on a 5-second cadence (fixedDelay: 5000ms).
+   * 
+   * Worst-case arithmetic for @SchedulerLock sizing:
+   * - Batch size: 10 objects (BlobstoreProperties.Poller.batchSize default)
+   * - Per-item cost: ~10s (S3 delete with retry backoff: maxAttempts=3, backoff initial=1000ms, multiplier=2.0 → ~3s backoff + 5-7s network latency)
+   * - Total: 10 items × ~10s/item ≈ 100s worst case
+   * - lockAtMostFor: PT5M (300s) gives ~3x safety margin
+   * - lockAtLeastFor: PT2S ensures this 5-second cadence is not suppressed (PT2M would suppress ~96% of legitimate ticks)
+   */
+  @SchedulerLock(name = "DeletionSchedulerService_processDeletions", lockAtMostFor = "PT5M", lockAtLeastFor = "PT2S")
   @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
   public void processDeletions() {
     List<FileStorageObject> eligible = fileStorageObjectRepository.findEligibleForPhysicalDeletion();
 
     for (FileStorageObject fso : eligible) {
+      /**
+       * Wrapped in a per-item transaction to isolate failures; the preceding @SchedulerLock ensures
+       * no two concurrent ticks can claim the same rows.
+       */
       transactionTemplate.execute(status -> {
         try {
           storageService.delete(fso.getKey());
-          outboxReplicationJobRepository.save(new OutboxReplicationJob(fso.getId(), OutboxReplicationJob.JobType.DELETE));
-          fileStorageObjectRepository.markPhysicallyDeleted(fso.getId(), Instant.now());
+          int affectedRows = fileStorageObjectRepository.markPhysicallyDeleted(fso.getId(), Instant.now());
+          if (affectedRows == 1) {
+            outboxReplicationJobRepository.save(new OutboxReplicationJob(fso.getId(), OutboxReplicationJob.JobType.DELETE));
+          }
           return null;
         } catch (Exception ex) {
           logger.error("Failed to physically delete file storage object {}", fso.getKey(), ex);

diff --git a/src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java b/src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java
index 345..678 100644
--- a/src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java
+++ b/src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java
@@ -1,6 +1,7 @@
 package com.softropic.skillars.platform.filestorage.service;
 
 import lombok.RequiredArgsConstructor;
+import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
 import org.springframework.scheduling.annotation.Scheduled;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.support.TransactionTemplate;
@@ -21,7 +22,23 @@ public class OutboxPollerScheduler {
     this.transactionTemplate = transactionTemplate;
   }
 
+  /**
+   * Polls and processes pending outbox replication jobs. Runs on a 5-second cadence (fixedDelay: 5000ms).
+   *
+   * Worst-case arithmetic for @SchedulerLock sizing:
+   * - Batch size: 10 objects (BlobstoreProperties.Poller.batchSize default)
+   * - Per-item cost: ~20s (REPLICATE branch worst case: full object stream copy to backup storage + retry)
+   * - Total: 10 items × ~20s/item ≈ 200s worst case
+   * - lockAtMostFor: PT10M (600s) gives ~3x safety margin
+   * - lockAtLeastFor: PT2S ensures this 5-second cadence is not suppressed
+   */
+  @SchedulerLock(name = "OutboxPollerScheduler_pollAndProcess", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2S")
   @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
   public void pollAndProcess() {
+    /**
+     * Wraps the claim (pollPending + markAsProcessing) and per-item processing inside
+     * the same transactionTemplate to hold the @SchedulerLock for the entire duration.
+     */
     transactionTemplate.execute(status -> {
       List<OutboxReplicationJob> pending = outboxReplicationJobRepository.pollPending();
       outboxReplicationJobRepository.markAsProcessing(pending.stream().map(OutboxReplicationJob::getId).collect(Collectors.toList()));

diff --git a/src/main/java/com/softropic/skillars/platform/outbox/service/OutboxService.java b/src/main/java/com/softropic/skillars/platform/outbox/service/OutboxService.java
index 901..234 100644
--- a/src/main/java/com/softropic/skillars/platform/outbox/service/OutboxService.java
+++ b/src/main/java/com/softropic/skillars/platform/outbox/service/OutboxService.java
@@ -116,7 +116,9 @@ public class OutboxService {
     return Math.floorMod((int) (hash % chunksPerDrain), chunksPerDrain);
   }
 
+  // lockAtMostFor sized well above MAX_CHUNKS_PER_DRAIN (200) x OutboxChunkProcessor.CHUNK_SIZE (25)
+  // = 5000 row-attempts worst case; correctness does not depend on this lock (see claimNextDue's
+  // PESSIMISTIC_WRITE + SKIP LOCKED below), so PT10M's margin needs no tighter derivation than this.
   @SchedulerLock(name = "OutboxService_sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
   @Scheduled(fixedDelayString = "${app.outbox.sweep.fixed-delay-ms:60000}")
   public void sweep() {
```

## Output Format

Provide your findings as a numbered list with this structure:
```
1. **[Category]**: Finding title
   - Description with specific code reference if applicable
   - Why this matters

2. **[Category]**: Next finding
   ...
```

Categories: Correctness, Safety, Logic, Error Handling, Testing, Documentation, Performance, Type Safety, Concurrency, Other

---
