package com.softropic.skillars.config;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * S3-compatible object-storage container (SeaweedFS — see {@link SeaweedFsS3Container} and
 * {@link SharedContainers#STORAGE_IMAGE}) for the storage IT family only — deliberately kept out
 * of {@link TestConfig} so tests that never touch blob storage don't pay for a container and
 * bucket-creation on every context startup. Named {@code MinioTestConfig} until 2026-09-28, kept
 * that way briefly after the SeaweedFS migration to minimize the diff, then renamed once it was
 * clear "Minio" would only confuse a future reader — see {@link SharedContainers#STORAGE_IMAGE}'s
 * Javadoc for why it no longer is.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StorageTestConfig {

    static final String TEST_BUCKET = "test-storage";

    /*
     * The container @Bean this used to declare made the container Startable inside the
     * context, so Boot's TestcontainersLifecycleBeanPostProcessor stopped it whenever a context
     * closed. The container now lives in SharedContainers for the life of the JVM; only this
     * registrar (which is not Startable) remains in the context.
     *
     * SharedContainers.Storage is a lazy holder, so importing this class is still what decides
     * whether a JVM pays for this container at all -- the property this class's javadoc describes
     * is preserved.
     */
    @Bean
    DynamicPropertyRegistrar storagePropertyRegistrar() {
        final SeaweedFsS3Container storage = SharedContainers.storage();
        return registry -> {
            registry.add("app.storage.endpoint-url", storage::getS3URL);
            registry.add("app.storage.bucket", () -> TEST_BUCKET);
            registry.add("app.storage.s3.access-key", storage::getUserName);
            registry.add("app.storage.s3.secret-key", storage::getPassword);
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
