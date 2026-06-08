package com.softropic.skillars.config;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@TestConfiguration(proxyBeanMethods = false)
public class MinioContainerConfig {

    static final String TEST_BUCKET = "test-storage";

    @Bean
    MinIOContainer minioContainer() {
        return new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-01-13T07-53-03Z"));
    }

    @Bean
    DynamicPropertyRegistrar minioPropertyRegistrar(MinIOContainer minioContainer) {
        return registry -> {
            registry.add("app.storage.endpoint-url", minioContainer::getS3URL);
            registry.add("app.storage.bucket", () -> TEST_BUCKET);
            registry.add("app.storage.s3.access-key", minioContainer::getUserName);
            registry.add("app.storage.s3.secret-key", minioContainer::getPassword);
            registry.add("app.storage.s3.path-style-access", () -> "true");
        };
    }

    @Bean
    ApplicationRunner createTestBucket(S3Client s3Client, BlobstoreProperties storageProperties) {
        return args -> {
            String bucket = storageProperties.getBucket();
            try {
                s3Client.headBucket(r -> r.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3Client.createBucket(r -> r.bucket(bucket));
            }
        };
    }
}
