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
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.InputStream;
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
