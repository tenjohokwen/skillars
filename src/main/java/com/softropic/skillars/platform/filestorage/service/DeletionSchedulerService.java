package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(fixedDelayString = "${app.storage.poller.fixed-delay-ms:5000}")
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
                OutboxReplicationJob job = OutboxReplicationJob.builder()
                    .storageObject(fso)
                    .jobType(OutboxReplicationJob.ReplicationJobType.DELETE)
                    .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
                    .attemptCount(0)
                    .build();
                outboxReplicationJobRepository.save(job);
                fileStorageObjectRepository.markPhysicallyDeleted(fso.getId(), Instant.now());
                return null;
            });
        }
    }
}
