package com.softropic.skillars.config;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * MinIO container for the storage/video IT families only — deliberately kept out of
 * {@link TestConfig} so tests that never touch blob storage don't pay for a MinIO container
 * and bucket-creation on every context startup.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MinioTestConfig {

    static final String TEST_BUCKET = "test-storage";

    /*
     * The MinIOContainer @Bean this used to declare made the container Startable inside the
     * context, so Boot's TestcontainersLifecycleBeanPostProcessor stopped it whenever a context
     * closed. The container now lives in SharedContainers for the life of the JVM; only this
     * registrar (which is not Startable) remains in the context.
     *
     * SharedContainers.Minio is a lazy holder, so importing this class is still what decides
     * whether a JVM pays for MinIO at all -- the property this class's javadoc describes is
     * preserved.
     */
    @Bean
    DynamicPropertyRegistrar minioPropertyRegistrar() {
        final MinIOContainer minio = SharedContainers.minio();
        return registry -> {
            registry.add("app.storage.endpoint-url", minio::getS3URL);
            registry.add("app.storage.bucket", () -> TEST_BUCKET);
            registry.add("app.storage.s3.access-key", minio::getUserName);
            registry.add("app.storage.s3.secret-key", minio::getPassword);
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
