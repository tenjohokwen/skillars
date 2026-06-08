package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
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
