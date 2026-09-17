package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class OutboxPollerScheduler {

    private final StorageService primaryStorageService;
    private final StorageService backupStorageService;
    private final OutboxReplicationJobRepository outboxReplicationJobRepository;
    private final BlobstoreProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final StorageMetrics storageMetrics;

    /**
     * skillars-deferred-119 AC2 sizing basis: unlike {@code DeletionSchedulerService}, this
     * scheduler's claim (see {@code pollPending} + {@code markAsProcessing}, both inside one
     * {@code transactionTemplate.execute(...)} block above) was already correctly scoped — this lock
     * is added purely for consistency with the rest of the codebase's now-near-universal convention,
     * not to close a live correctness gap. Batch size is {@code BlobstoreProperties.Poller.batchSize}
     * (default 10). Per-item worst case is the {@code REPLICATE} branch — a {@code get} from primary
     * storage followed by a {@code put} to backup storage, each independently {@code @Retryable} with
     * the same config as {@code DeletionSchedulerService}'s sizing basis ({@code max-attempts=3},
     * {@code backoff-initial-ms=1000}, {@code multiplier=2.0}): {@code 3s} backoff + a generous 2s x 3
     * attempts = {@code 6s} call-latency estimate per operation ≈ {@code 9s}/operation, and REPLICATE
     * does two (get, then put) ≈ {@code 18s}/item, rounded up to 20s/item. Batch worst case:
     * {@code 10 x 20s = 200s ≈ 3.3 minutes}. {@code PT10M} gives real margin (~3x) above that.
     * Measure actual S3 get/put latency in the target environment (logs, metrics) and revisit this
     * sizing if it diverges materially from the illustrative estimate above.
     * {@code lockAtLeastFor} follows the same reasoning as {@code DeletionSchedulerService}: this
     * scheduler shares the identical 5-second {@code app.storage.poller.fixed-delay-ms} cadence, so
     * {@code PT2S} (not the 5-minute-siblings' {@code PT2M}) is used here too.
     */
    @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
    @SchedulerLock(name = "OutboxPollerScheduler_pollAndProcess",
                   lockAtMostFor = "PT10M", lockAtLeastFor = "PT2S")
    public void pollAndProcess() {
        int batchSize = properties.getPoller().getBatchSize();
        storageMetrics.updateQueueDepth(
            outboxReplicationJobRepository.countByStatus(OutboxReplicationJob.ReplicationJobStatus.PENDING));

        List<OutboxReplicationJob> claimed = transactionTemplate.execute(status -> {
            List<OutboxReplicationJob> pending = outboxReplicationJobRepository.pollPending(batchSize);
            if (pending == null || pending.isEmpty()) {
                return pending;
            }
            Instant now = Instant.now();
            pending.forEach(j -> {
                j.getStorageObject().getKey();
                outboxReplicationJobRepository.markAsProcessing(j.getId(), now);
            });
            return pending;
        });

        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        for (OutboxReplicationJob job : claimed) {
            processJob(job);
        }
    }

    private void processJob(OutboxReplicationJob job) {
        String key = job.getStorageObject().getKey();
        MDC.put("storageKey", key);
        MDC.put("operation", job.getJobType().name().toLowerCase());
        MDC.put("provider", "backup");
        try {
            switch (job.getJobType()) {
                case REPLICATE -> {
                    StorageObject obj = primaryStorageService.get(key);
                    try (InputStream data = obj.data()) {
                        backupStorageService.put(key, data,
                            obj.metadata().contentLength(),
                            obj.metadata().contentType());
                    }
                }
                case DELETE -> backupStorageService.delete(key);
            }
            transactionTemplate.execute(status -> {
                outboxReplicationJobRepository.markAsCompleted(job.getId());
                return null;
            });
        } catch (Exception e) {
            int newCount = job.getAttemptCount() + 1;
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.length() > 500) {
                errorMsg = errorMsg.substring(0, 500);
            }
            String finalErrorMsg = errorMsg;
            Instant now = Instant.now();
            log.warn("Replication job failed for key={} attempt={}: {}", key, newCount, errorMsg);
            if (newCount >= properties.getReplication().getMaxAttempts()) {
                transactionTemplate.execute(status -> {
                    outboxReplicationJobRepository.markAsFailed(job.getId(), newCount, now, finalErrorMsg);
                    return null;
                });
            } else {
                transactionTemplate.execute(status -> {
                    outboxReplicationJobRepository.markAsPendingForRetry(job.getId(), newCount, now, finalErrorMsg);
                    return null;
                });
            }
        } finally {
            MDC.remove("storageKey");
            MDC.remove("operation");
            MDC.remove("provider");
        }
    }
}
