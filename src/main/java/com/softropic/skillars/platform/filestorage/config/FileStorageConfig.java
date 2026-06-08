package com.softropic.skillars.platform.filestorage.config;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.service.OutboxPollerScheduler;
import com.softropic.skillars.platform.filestorage.service.ReplicatedStorageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class FileStorageConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
    public ReplicatedStorageService replicatedStorageService(
            StorageService storageService,
            FileStorageObjectRepository fileStorageObjectRepository,
            OutboxReplicationJobRepository outboxReplicationJobRepository,
            TransactionTemplate storageTransactionTemplate) {
        return new ReplicatedStorageService(storageService, fileStorageObjectRepository,
            outboxReplicationJobRepository, storageTransactionTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
    public OutboxPollerScheduler outboxPollerScheduler(
            @Qualifier("storageService") StorageService primaryStorageService,
            @Qualifier("backupStorageService") StorageService backupStorageService,
            OutboxReplicationJobRepository outboxReplicationJobRepository,
            BlobstoreProperties blobstoreProperties,
            TransactionTemplate storageTransactionTemplate,
            StorageMetrics storageMetrics) {
        return new OutboxPollerScheduler(primaryStorageService, backupStorageService,
            outboxReplicationJobRepository, blobstoreProperties, storageTransactionTemplate,
            storageMetrics);
    }
}
