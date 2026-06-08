package com.softropic.skillars.infrastructure.blobstore.config;

import com.softropic.skillars.infrastructure.blobstore.service.LocalFileSystemStorageService;
import com.softropic.skillars.infrastructure.blobstore.service.S3StorageService;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(BlobstoreProperties.class)
public class BlobstoreConfig {

    private AwsCredentialsProvider credentialsProvider(BlobstoreProperties properties) {
        BlobstoreProperties.S3 s3 = properties.getS3();
        if (s3.getAccessKey() != null && !s3.getAccessKey().isBlank()
            && s3.getSecretKey() != null && !s3.getSecretKey().isBlank()) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    private AwsCredentialsProvider backupCredentialsProvider(BlobstoreProperties props) {
        BlobstoreProperties.Replication.Backup b = props.getReplication().getBackup();
        if (b.getAccessKey() != null && !b.getAccessKey().isBlank()
            && b.getSecretKey() != null && !b.getSecretKey().isBlank()) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(b.getAccessKey(), b.getSecretKey()));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService storageUploadExecutor(BlobstoreProperties properties) {
        BlobstoreProperties.Executor cfg = properties.getExecutor();
        return new ThreadPoolExecutor(
                cfg.getPoolSize(),
                cfg.getPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.getQueueCapacity()),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3Client s3Client(BlobstoreProperties properties) {
        return S3Client.builder()
            .endpointOverride(URI.create(properties.getEndpointUrl()))
            .credentialsProvider(credentialsProvider(properties))
            .region(Region.of(properties.getS3().getRegion()))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.getS3().isPathStyleAccess())
                .build())
            .httpClient(ApacheHttpClient.builder()
                .connectionAcquisitionTimeout(Duration.ofMillis(properties.getS3().getConnectionTimeoutMs()))
                .build())
            .overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofMillis(properties.getS3().getRequestTimeoutMs())))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3AsyncClient s3AsyncClient(BlobstoreProperties properties) {
        return S3AsyncClient.builder()
            .endpointOverride(URI.create(properties.getEndpointUrl()))
            .credentialsProvider(credentialsProvider(properties))
            .region(Region.of(properties.getS3().getRegion()))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.getS3().isPathStyleAccess())
                .build())
            .overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofMillis(properties.getS3().getRequestTimeoutMs())))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3Presigner s3Presigner(BlobstoreProperties properties) {
        return S3Presigner.builder()
            .endpointOverride(URI.create(properties.getEndpointUrl()))
            .credentialsProvider(credentialsProvider(properties))
            .region(Region.of(properties.getS3().getRegion()))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.getS3().isPathStyleAccess())
                .build())
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder().s3Client(s3AsyncClient).build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
    public StorageService storageService(S3Client s3Client,
                                         S3TransferManager transferManager,
                                         BlobstoreProperties properties,
                                         ExecutorService storageUploadExecutor) {
        return new S3StorageService(s3Client, transferManager, properties, storageUploadExecutor);
    }

    @Bean("localStorageService")
    @ConditionalOnProperty(name = "app.storage.provider", havingValue = "local")
    public StorageService localStorageService(BlobstoreProperties properties) {
        Path baseDir = Path.of(properties.getLocal().getBaseDir());
        return new LocalFileSystemStorageService(baseDir);
    }

    @Bean("backupS3Client")
    @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
    public S3Client backupS3Client(BlobstoreProperties properties) {
        BlobstoreProperties.Replication.Backup b = properties.getReplication().getBackup();
        return S3Client.builder()
            .endpointOverride(URI.create(b.getEndpointUrl()))
            .credentialsProvider(backupCredentialsProvider(properties))
            .region(Region.of(b.getRegion()))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(b.isPathStyleAccess())
                .build())
            .httpClient(ApacheHttpClient.builder()
                .connectionAcquisitionTimeout(Duration.ofMillis(properties.getS3().getConnectionTimeoutMs()))
                .build())
            .overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofMillis(properties.getS3().getRequestTimeoutMs())))
            .build();
    }

    @Bean("backupS3AsyncClient")
    @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
    public S3AsyncClient backupS3AsyncClient(BlobstoreProperties properties) {
        BlobstoreProperties.Replication.Backup b = properties.getReplication().getBackup();
        return S3AsyncClient.builder()
            .endpointOverride(URI.create(b.getEndpointUrl()))
            .credentialsProvider(backupCredentialsProvider(properties))
            .region(Region.of(b.getRegion()))
            .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(b.isPathStyleAccess())
                .build())
            .overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofMillis(properties.getS3().getRequestTimeoutMs())))
            .build();
    }

    @Bean("backupS3TransferManager")
    @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
    public S3TransferManager backupS3TransferManager(@Qualifier("backupS3AsyncClient") S3AsyncClient backupS3AsyncClient) {
        return S3TransferManager.builder().s3Client(backupS3AsyncClient).build();
    }

    @Bean("backupStorageService")
    @ConditionalOnProperty(name = "app.storage.replication.enabled", havingValue = "true")
    public StorageService backupStorageService(@Qualifier("backupS3Client") S3Client backupS3Client,
                                               @Qualifier("backupS3TransferManager") S3TransferManager backupTransferManager,
                                               BlobstoreProperties properties,
                                               ExecutorService storageUploadExecutor) {
        return new S3StorageService(backupS3Client, backupTransferManager, properties, storageUploadExecutor);
    }

    @Bean
    public TransactionTemplate storageTransactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
