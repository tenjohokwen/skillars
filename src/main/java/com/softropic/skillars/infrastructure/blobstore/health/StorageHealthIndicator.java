package com.softropic.skillars.infrastructure.blobstore.health;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Object storage (S3 / SeaweedFS), which holds every uploaded photo and file, on the admin health
 * page and in the overall status. Owner decision 2026-11-07 (Pitchside OPS-04/06, option A): a
 * storage outage must be visible to ops, and it counts towards the overall status. Not part of the
 * {@code smoke} group the container healthcheck uses, so a storage blip never restarts the app.
 *
 * <p>A HEAD on the configured bucket: cheap, needs only read access, and bounded by the client's own
 * request timeout ({@code app.storage.s3.request-timeout-ms}).
 */
@Component("storage")
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
public class StorageHealthIndicator extends AbstractHealthIndicator {

    private final S3Client s3Client;
    private final BlobstoreProperties properties;

    public StorageHealthIndicator(@Qualifier("s3Client") S3Client s3Client, BlobstoreProperties properties) {
        super("Object storage health check failed");
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
        builder.up().withDetail("bucket", properties.getBucket());
    }
}
