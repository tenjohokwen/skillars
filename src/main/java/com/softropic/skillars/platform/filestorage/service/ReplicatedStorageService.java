package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;

@Slf4j
@RequiredArgsConstructor
public class ReplicatedStorageService implements StorageService {

    private final StorageService primaryStorageService;
    private final FileStorageObjectRepository fileStorageObjectRepository;
    private final OutboxReplicationJobRepository outboxReplicationJobRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void put(String key, InputStream data, long contentLength, String contentType) {
        primaryStorageService.put(key, data, contentLength, contentType);
        transactionTemplate.execute(status -> {
            fileStorageObjectRepository.findByKey(key).ifPresentOrElse(
                fso -> {
                    OutboxReplicationJob job = OutboxReplicationJob.builder()
                        .storageObject(fso)
                        .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
                        .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
                        .attemptCount(0)
                        .build();
                    outboxReplicationJobRepository.save(job);
                },
                () -> log.warn("No FSO found for key {}; skipping outbox insert", key)
            );
            return null;
        });
    }

    @Override
    public StorageObject get(String key) {
        return primaryStorageService.get(key);
    }

    @Override
    public void delete(String key) {
        primaryStorageService.delete(key);
    }

    @Override
    public boolean exists(String key) {
        return primaryStorageService.exists(key);
    }

    @Override
    public StorageObjectMetadata stat(String key) {
        return primaryStorageService.stat(key);
    }

    @Override
    public void copy(String sourceKey, String destinationKey) {
        primaryStorageService.copy(sourceKey, destinationKey);
    }
}
