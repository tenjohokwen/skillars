package com.softropic.skillars.infrastructure.blobstore.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3", matchIfMissing = true)
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3TransferManager transferManager;
    private final BlobstoreProperties properties;
    private final ExecutorService storageUploadExecutor;

    @Retryable(
        retryFor = SdkException.class,
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public void put(String key, InputStream data, long contentLength, String contentType) {
        String sanitizedKey = key.replaceAll("[^a-zA-Z0-9./_-]", "_");
        UploadRequest uploadRequest = UploadRequest.builder()
            .putObjectRequest(r -> r
                .bucket(properties.getBucket())
                .key(sanitizedKey)
                .contentType(contentType)
                .contentLength(contentLength))
            .requestBody(AsyncRequestBody.fromInputStream(data, contentLength, storageUploadExecutor))
            .build();
        transferManager.upload(uploadRequest).completionFuture().join();
    }

    @Recover
    public void recoverPut(SdkException ex, String sanitizedKey, InputStream data, long contentLength, String contentType) {
        throw new StorageProviderException("put", ex, sanitizedKey);
    }

    @Retryable(
        retryFor = SdkException.class,
        noRetryFor = {StorageObjectNotFoundException.class},
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public StorageObject get(String key) {
        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(r -> r.bucket(properties.getBucket()).key(key));
            GetObjectResponse sdkResponse = response.response();
            StorageObjectMetadata metadata = new StorageObjectMetadata(
                key,
                sdkResponse.contentType(),
                sdkResponse.contentLength(),
                sdkResponse.eTag(),
                sdkResponse.lastModified()
            );
            return new StorageObject(response, metadata);
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(key, e);
        }
    }

    @Recover
    public StorageObject recoverGetNotFound(StorageObjectNotFoundException ex, String key) {
        throw ex;
    }

    @Recover
    public StorageObject recoverGet(SdkException ex, String key) {
        if (ex instanceof NoSuchKeyException) {
            throw new StorageObjectNotFoundException(key, ex);
        }
        throw new StorageProviderException("get", ex, key);
    }

    @Retryable(
        retryFor = SdkException.class,
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public void delete(String key) {
        s3Client.deleteObject(r -> r.bucket(properties.getBucket()).key(key));
    }

    @Recover
    public void recoverDelete(SdkException ex, String key) {
        throw new StorageProviderException("delete", ex, key);
    }

    /**
     * skillars-deferred-164 AC11. {@code null}/empty is a no-op; a missing key is not an error (S3's
     * {@code DeleteObjects} never reports "key did not exist" in {@code errors()} — it is already
     * idempotent on a missing key, same as {@link #delete(String)}). A genuine per-key failure
     * (permission, etc.) in {@code errors()} IS surfaced, aggregated across every chunk, as one
     * {@link StorageProviderException} naming every failed key — not silently dropped.
     */
    @Retryable(
        retryFor = SdkException.class,
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public void deleteAll(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        List<String> failures = new ArrayList<>();
        for (int start = 0; start < keys.size(); start += 1000) {
            List<String> chunk = keys.subList(start, Math.min(start + 1000, keys.size()));
            List<ObjectIdentifier> objectIds = chunk.stream()
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .toList();
            DeleteObjectsResponse response = s3Client.deleteObjects(r -> r
                .bucket(properties.getBucket())
                .delete(d -> d.objects(objectIds)));
            if (response.hasErrors()) {
                response.errors().forEach(e ->
                    failures.add(e.key() + " (" + e.code() + ": " + e.message() + ")"));
            }
        }
        if (!failures.isEmpty()) {
            throw new StorageProviderException("deleteAll",
                new IllegalStateException("S3 reported per-key failures: " + failures),
                String.join(", ", failures));
        }
    }

    /**
     * Spring Retry routes a non-retryable exception to recovery too, and with no {@code @Recover}
     * matching {@link StorageProviderException} it would surface as {@code ExhaustedRetryException
     * ("Cannot locate recovery method")} instead — so the per-key-failure exception
     * {@link #deleteAll} throws is passed through unchanged, same as {@link #recoverGetNotFound}.
     */
    @Recover
    public void recoverDeleteAllPerKeyFailure(StorageProviderException ex, List<String> keys) {
        throw ex;
    }

    @Recover
    public void recoverDeleteAll(SdkException ex, List<String> keys) {
        throw new StorageProviderException("deleteAll", ex,
            keys == null ? "" : String.join(", ", keys));
    }

    @Retryable(
        retryFor = SdkException.class,
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(r -> r.bucket(properties.getBucket()).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Recover
    public boolean recoverExists(SdkException ex, String key) {
        throw new StorageProviderException("exists", ex, key);
    }

    @Retryable(
        retryFor = SdkException.class,
        noRetryFor = {StorageObjectNotFoundException.class},
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public StorageObjectMetadata stat(String key) {
        try {
            HeadObjectResponse r = s3Client.headObject(req -> req.bucket(properties.getBucket()).key(key));
            return new StorageObjectMetadata(key, r.contentType(), r.contentLength(), r.eTag(), r.lastModified());
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(key, e);
        }
    }

    @Recover
    public StorageObjectMetadata recoverStatNotFound(StorageObjectNotFoundException ex, String key) {
        throw ex;
    }

    @Recover
    public StorageObjectMetadata recoverStat(SdkException ex, String key) {
        if (ex instanceof NoSuchKeyException) {
            throw new StorageObjectNotFoundException(key, ex);
        }
        throw new StorageProviderException("stat", ex, key);
    }

    @Retryable(
        retryFor = SdkException.class,
        maxAttemptsExpression = "${app.storage.retry.max-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${app.storage.retry.backoff-initial-ms:1000}",
            multiplierExpression = "${app.storage.retry.backoff-multiplier:2.0}"
        )
    )
    @Override
    public void copy(String sourceKey, String destinationKey) {
        s3Client.copyObject(r -> r
            .sourceBucket(properties.getBucket())
            .sourceKey(sourceKey)
            .destinationBucket(properties.getBucket())
            .destinationKey(destinationKey));
    }

    @Recover
    public void recoverCopy(SdkException ex, String sourceKey, String destinationKey) {
        throw new StorageProviderException("copy", ex, sourceKey + "->" + destinationKey);
    }
}
