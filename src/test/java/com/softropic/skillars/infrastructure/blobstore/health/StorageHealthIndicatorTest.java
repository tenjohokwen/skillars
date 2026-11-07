package com.softropic.skillars.infrastructure.blobstore.health;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Owner decision 2026-11-07 (Pitchside OPS-04/06): object storage appears on the health page.
class StorageHealthIndicatorTest {

    private final S3Client s3 = mock(S3Client.class);
    private final BlobstoreProperties properties = new BlobstoreProperties();
    private final StorageHealthIndicator indicator;

    StorageHealthIndicatorTest() {
        properties.setBucket("skillars-test");
        indicator = new StorageHealthIndicator(s3, properties);
    }

    @Test
    void reachableBucket_isUpWithItsName() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "skillars-test");
    }

    @Test
    void unreachableStorage_isDownWithTheError() {
        when(s3.headBucket(any(HeadBucketRequest.class)))
            .thenThrow(SdkClientException.create("Unable to execute HTTP request: Connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void forbidden_isUpWithANote_becauseTheStorageAnswered() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(
            (Throwable) software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).message("Access Denied").build());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("note");
    }

    @Test
    void missingBucket_isDown() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(
            (Throwable) software.amazon.awssdk.services.s3.model.NoSuchBucketException.builder().statusCode(404).message("NoSuchBucket").build());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
