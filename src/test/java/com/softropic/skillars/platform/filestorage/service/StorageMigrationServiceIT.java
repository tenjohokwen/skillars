package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.service.S3StorageService;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.filestorage.contract.StorageObjectDto;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class StorageMigrationServiceIT extends BaseStorageIT {

    static final String DEST_BUCKET = "test-dest";

    @Container
    static final MinIOContainer destinationMinio =
        new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-01-13T07-53-03Z"));

    static StorageService destinationService;

    @Autowired
    StorageService storageService;

    @Autowired
    FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    BlobstoreProperties storageProperties;

    private final List<String> sourceKeysToCleanup = new ArrayList<>();
    private final List<String> destKeysToCleanup = new ArrayList<>();

    @BeforeAll
    static void setUpDestination() {
        S3Client destS3Client = S3Client.builder()
            .endpointOverride(URI.create(destinationMinio.getS3URL()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(destinationMinio.getUserName(), destinationMinio.getPassword())))
            .region(Region.of("us-east-1"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();

        try {
            destS3Client.headBucket(r -> r.bucket(DEST_BUCKET));
        } catch (NoSuchBucketException e) {
            destS3Client.createBucket(r -> r.bucket(DEST_BUCKET));
        }

        S3AsyncClient destAsyncClient = S3AsyncClient.builder()
            .endpointOverride(URI.create(destinationMinio.getS3URL()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(destinationMinio.getUserName(), destinationMinio.getPassword())))
            .region(Region.of("us-east-1"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
        S3TransferManager destTransferManager = S3TransferManager.builder().s3Client(destAsyncClient).build();

        BlobstoreProperties destProperties = new BlobstoreProperties();
        destProperties.setBucket(DEST_BUCKET);
        destProperties.setEndpointUrl(destinationMinio.getS3URL());
        BlobstoreProperties.S3 s3Props = new BlobstoreProperties.S3();
        s3Props.setPathStyleAccess(true);
        s3Props.setAccessKey(destinationMinio.getUserName());
        s3Props.setSecretKey(destinationMinio.getPassword());
        destProperties.setS3(s3Props);

        destinationService = new S3StorageService(destS3Client, destTransferManager,
            destProperties, Executors.newFixedThreadPool(2));
    }

    @BeforeEach
    void setUp() {
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
        sourceKeysToCleanup.clear();
        destKeysToCleanup.clear();
    }

    @AfterEach
    void tearDown() {
        sourceKeysToCleanup.forEach(key -> {
            try { storageService.delete(key); } catch (Exception ignored) {}
        });
        destKeysToCleanup.forEach(key -> {
            try { destinationService.delete(key); } catch (Exception ignored) {}
        });
    }

    @Test
    void migrate_streamsFileFromSourceToDestination() throws Exception {
        String key = "migrate-it/" + UUID.randomUUID() + ".txt";
        byte[] content = "hello migration".getBytes(StandardCharsets.UTF_8);
        storageService.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
        sourceKeysToCleanup.add(key);
        destKeysToCleanup.add(key);

        StorageMigrationService migrationService =
            new StorageMigrationService(storageService, destinationService, fileStorageObjectRepository);
        migrationService.migrate(key, key);

        assertThat(destinationService.exists(key)).isTrue();

        StorageObject destObj = destinationService.get(key);
        try (InputStream is = destObj.data()) {
            assertThat(is.readAllBytes()).isEqualTo("hello migration".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(storageService.exists(key)).isTrue();
    }

    @Test
    void migrateAll_skipsAlreadyMigratedKeys() {
        String ownerId = "tenant-migrate-it";
        String bucket = storageProperties.getBucket();
        String key1 = "tenant/migrate-it/file1.txt";
        String key2 = "tenant/migrate-it/file2.txt";

        fileStorageObjectRepository.save(FileStorageObject.builder()
            .key(key1).ownerId(ownerId).originalFilename("file1.txt")
            .contentType("text/plain").sizeBytes(5L).provider("s3").bucket(bucket)
            .uploadConfirmedAt(Instant.now()).status(EntityStatus.ACTIVE).build());
        fileStorageObjectRepository.save(FileStorageObject.builder()
            .key(key2).ownerId(ownerId).originalFilename("file2.txt")
            .contentType("text/plain").sizeBytes(5L).provider("s3").bucket(bucket)
            .uploadConfirmedAt(Instant.now()).status(EntityStatus.ACTIVE).build());

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        storageService.put(key1, new ByteArrayInputStream(data), data.length, "text/plain");
        storageService.put(key2, new ByteArrayInputStream(data), data.length, "text/plain");
        destinationService.put(key2, new ByteArrayInputStream(data), data.length, "text/plain");

        sourceKeysToCleanup.add(key1);
        sourceKeysToCleanup.add(key2);
        destKeysToCleanup.add(key1);
        destKeysToCleanup.add(key2);

        StorageMigrationService migrationService =
            new StorageMigrationService(storageService, destinationService, fileStorageObjectRepository);
        migrationService.migrateAll(ownerId);

        assertThat(destinationService.exists(key1)).isTrue();
        assertThat(destinationService.exists(key2)).isTrue();
    }

    @Test
    void exportMetadata_returnsAllNonDeletedObjects() {
        String ownerId = "tenant-export-it";
        String bucket = storageProperties.getBucket();

        fileStorageObjectRepository.save(FileStorageObject.builder()
            .key("export-it/a.txt").ownerId(ownerId).sizeBytes(1L)
            .provider("s3").bucket(bucket).status(EntityStatus.ACTIVE)
            .uploadConfirmedAt(Instant.now()).build());
        fileStorageObjectRepository.save(FileStorageObject.builder()
            .key("export-it/b.txt").ownerId(ownerId).sizeBytes(1L)
            .provider("s3").bucket(bucket).status(EntityStatus.ACTIVE)
            .uploadConfirmedAt(Instant.now()).build());
        fileStorageObjectRepository.save(FileStorageObject.builder()
            .key("export-it/c.txt").ownerId(ownerId).sizeBytes(1L)
            .provider("s3").bucket(bucket).status(EntityStatus.DELETED)
            .deletedAt(Instant.now()).uploadConfirmedAt(Instant.now()).build());

        StorageMigrationService migrationService =
            new StorageMigrationService(storageService, destinationService, fileStorageObjectRepository);
        List<StorageObjectDto> result = migrationService.exportMetadata(ownerId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(StorageObjectDto::key)
            .containsExactlyInAnyOrder("export-it/a.txt", "export-it/b.txt");
    }

    @Test
    void importMetadata_isIdempotent() {
        String ownerId = "tenant-import-it";
        String bucket = storageProperties.getBucket();

        List<StorageObjectDto> dtos = List.of(
            new StorageObjectDto("import-it/a.txt", ownerId, "a.txt", "text/plain", 10L,
                null, null, "s3", bucket, Instant.now()),
            new StorageObjectDto("import-it/b.txt", ownerId, "b.txt", "text/plain", 10L,
                null, null, "s3", bucket, Instant.now())
        );

        StorageMigrationService migrationService =
            new StorageMigrationService(storageService, destinationService, fileStorageObjectRepository);

        migrationService.importMetadata(dtos);
        assertThat(fileStorageObjectRepository.count()).isEqualTo(2);

        migrationService.importMetadata(dtos);
        assertThat(fileStorageObjectRepository.count()).isEqualTo(2);

        assertThat(fileStorageObjectRepository.findByKey("import-it/a.txt"))
            .isPresent()
            .get()
            .extracting(fso -> fso.getStatus())
            .isEqualTo(EntityStatus.ACTIVE);
        assertThat(fileStorageObjectRepository.findByKey("import-it/b.txt"))
            .isPresent()
            .get()
            .extracting(fso -> fso.getStatus())
            .isEqualTo(EntityStatus.ACTIVE);
    }
}
