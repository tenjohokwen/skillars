package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeletionSchedulerService {

    private final FileStorageObjectRepository fileStorageObjectRepository;
    private final OutboxReplicationJobRepository outboxReplicationJobRepository;
    private final StorageService storageService;
    private final BlobstoreProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * skillars-deferred-119 AC1 sizing basis: {@code findEligibleForPhysicalDeletion}'s {@code FOR
     * UPDATE SKIP LOCKED} claim (repository-method-level {@code @Transactional}) commits and releases
     * its row locks before this loop even starts, so without a lock here two concurrent ticks can
     * claim overlapping rows. Batch size is {@code BlobstoreProperties.Poller.batchSize} (default 10).
     * Per-item worst case is {@link StorageService#delete}'s own {@code @Retryable} config
     * ({@code max-attempts=3}, {@code backoff-initial-ms=1000}, {@code multiplier=2.0}): backoff alone
     * across two waits is {@code 1000ms + 2000ms = 3s}. Real S3 round-trip latency is not measured by
     * this story, so a generous per-attempt estimate of 2s x up to 3 attempts = 6s is added on top:
     * {@code 3s + 6s ≈ 9s}/item, rounded up to 10s/item for clean arithmetic. Batch worst case:
     * {@code 10 x 10s = 100s ≈ 1.67 minutes}. {@code PT5M} gives real margin (~3x) above that.
     * Measure actual S3 delete latency in the target environment (logs, metrics) and revisit this
     * sizing if it diverges materially from the illustrative estimate above.
     * {@code lockAtLeastFor} is deliberately NOT the {@code PT2M} used by the 5-minute-{@code
     * fixedDelay} siblings: this scheduler's own cadence ({@code
     * app.storage.poller.fixed-delay-ms}, default 5000ms = 5 seconds) is far tighter than theirs, and
     * a 2-minute floor would suppress roughly 24 of every 25 legitimate ticks. {@code PT2S} sits
     * comfortably below the 5-second cadence (so it never blocks the next scheduled tick under normal
     * operation) while still guarding the pathological fast-fail-and-immediately-refire edge case,
     * mirroring {@code RadarCompositeDlqProcessor}'s {@code PT30S}-under-60s-cadence precedent.
     */
    @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
    @SchedulerLock(name = "DeletionSchedulerService_processDeletions",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT2S")
    public void processDeletions() {
        Instant cutoff = Instant.now().minus(properties.getDeletion().getRetentionDays(), ChronoUnit.DAYS);
        List<FileStorageObject> eligible = fileStorageObjectRepository
            .findEligibleForPhysicalDeletion(cutoff, properties.getPoller().getBatchSize());

        for (FileStorageObject fso : eligible) {
            MDC.put("storageKey", fso.getKey());
            MDC.put("operation", "physical_delete");
            MDC.put("provider", properties.getProvider());
            try {
                storageService.delete(fso.getKey());
            } catch (Exception e) {
                log.warn("Physical deletion skipped for key={}, will retry: {}", fso.getKey(), e.getMessage());
                continue;
            } finally {
                MDC.remove("storageKey");
                MDC.remove("operation");
                MDC.remove("provider");
            }
            transactionTemplate.execute(status -> {
                // skillars-deferred-119 AC1: check the conditional update's affected-row count BEFORE
                // saving the OutboxReplicationJob, never the reverse — a losing race (0 rows affected,
                // because another tick already marked this row physically deleted) must skip the save
                // entirely rather than needing to be undone afterward.
                int updated = fileStorageObjectRepository.markPhysicallyDeleted(fso.getId(), Instant.now());
                if (updated == 1) {
                    OutboxReplicationJob job = OutboxReplicationJob.builder()
                        .storageObject(fso)
                        .jobType(OutboxReplicationJob.ReplicationJobType.DELETE)
                        .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
                        .attemptCount(0)
                        .build();
                    outboxReplicationJobRepository.save(job);
                }
                return null;
            });
        }
    }
}
