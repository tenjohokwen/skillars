package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEvent;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignDownloadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.event.StorageObjectConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.softropic.skillars.platform.security.service.SecurityUtil;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final ValidationChain validationChain;
    private final FileStorageObjectRepository fileStorageObjectRepository;
    private final StorageKeyGenerator storageKeyGenerator;
    private final S3Presigner s3Presigner;
    private final BlobstoreProperties storageProperties;
    private final FileStorageProperties fileStorageProperties;
    private final S3Client s3Client;
    private final OutboxReplicationJobRepository outboxReplicationJobRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final StorageAccessEventRepository storageAccessEventRepository;
    private final StorageMetrics storageMetrics;
    private final SecurityUtil securityUtil;

    private String getOwnerId() {
        return securityUtil.requireCurrentBusinessId();
    }

    public SignUploadResponse signUpload(SignUploadRequest request) {
        String ownerId = getOwnerId();
        MDC.put("operation", "sign_upload");
        MDC.put("provider", storageProperties.getProvider());
        MDC.put("ownerId", ownerId);
        try {
            ValidationRequest validationRequest = ValidationRequest.builder()
                .originalFilename(request.entity() + "." + request.extension())
                .contentType(request.contentType())
                .extension(request.extension())
                .fileSizeBytes(request.fileSizeBytes())
                .checksum(request.checksum())
                .tags(request.tags())
                .build();

            validationChain.validate(validationRequest);

            long currentUsage = fileStorageObjectRepository.sumSizeBytesByOwnerId(ownerId);
            if (currentUsage + request.fileSizeBytes() > fileStorageProperties.getQuota().getDefaultBytes()) {
                throw new QuotaExceededException(ownerId, currentUsage, request.fileSizeBytes());
            }

            String key = storageKeyGenerator.generate(request.entity(), request.entityId(), request.extension());

            PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(storageProperties.getUploadTtlSeconds()))
                    .putObjectRequest(r -> r
                        .bucket(storageProperties.getBucket())
                        .key(key)
                        .contentType(request.contentType()))
                    .build());

            return new SignUploadResponse(key, presigned.url().toString(), presigned.expiration());
        } finally {
            MDC.remove("operation");
            MDC.remove("provider");
            MDC.remove("ownerId");
        }
    }

    public ConfirmUploadResponse confirmUpload(String key, ConfirmUploadRequest request) {
        String ownerId = getOwnerId();
        Optional<FileStorageObject> existing = fileStorageObjectRepository.findByKey(key);
        if (existing.isPresent()) {
            return toConfirmUploadResponse(existing.get());
        }

        MDC.put("storageKey", key);
        MDC.put("ownerId", ownerId);
        MDC.put("operation", "confirm_upload");
        MDC.put("provider", storageProperties.getProvider());
        long start = System.nanoTime();
        try {
            HeadObjectResponse head;
            try {
                head = s3Client.headObject(r -> r.bucket(storageProperties.getBucket()).key(key));
            } catch (NoSuchKeyException e) {
                throw new StorageObjectNotFoundException(key, e);
            }

            if (!request.contentType().equals(head.contentType())) {
                throw new StorageValidationException(
                    "contentType mismatch: declared=" + request.contentType() + ", actual=" + head.contentType());
            }
            if (request.fileSizeBytes() != head.contentLength()) {
                throw new StorageValidationException(
                    "fileSizeBytes mismatch: declared=" + request.fileSizeBytes() + ", actual=" + head.contentLength());
            }
            if (head.eTag() == null || head.eTag().isBlank()) {
                throw new StorageValidationException("upload incomplete: ETag absent for key " + key);
            }

            Instant confirmedAt = Instant.now();
            FileStorageObject saved = transactionTemplate.execute(status -> {
                FileStorageObject fso = FileStorageObject.builder()
                    .key(key)
                    .ownerId(ownerId)
                    .originalFilename(resolveOriginalFilename(request, key))
                    .contentType(head.contentType())
                    .sizeBytes(head.contentLength())
                    .checksum(request.checksum())
                    .tags(request.tags())
                    .provider(storageProperties.getProvider())
                    .bucket(storageProperties.getBucket())
                    .uploadConfirmedAt(confirmedAt)
                    .build();

                FileStorageObject persistedFso = fileStorageObjectRepository.save(fso);

                OutboxReplicationJob job = OutboxReplicationJob.builder()
                    .storageObject(persistedFso)
                    .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
                    .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
                    .attemptCount(0)
                    .build();
                outboxReplicationJobRepository.save(job);

                return persistedFso;
            });

            applicationEventPublisher.publishEvent(new StorageObjectConfirmedEvent(this, saved));

            storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY, storageProperties.getProvider(), "success", System.nanoTime() - start);
            storageMetrics.recordFileSizeBytes(saved.getSizeBytes());
            return toConfirmUploadResponse(saved);
        } catch (Exception ex) {
            storageMetrics.recordLatency(StorageMetrics.UPLOAD_LATENCY, storageProperties.getProvider(), "error", System.nanoTime() - start);
            storageMetrics.recordError("confirm_upload",
                ex instanceof ApplicationException ae ? ae.getErrorCode().getErrorCode() : "UNKNOWN");
            throw ex;
        } finally {
            MDC.remove("storageKey");
            MDC.remove("ownerId");
            MDC.remove("operation");
            MDC.remove("provider");
        }
    }

    public SignDownloadResponse signDownload(String key) {
        long start = System.nanoTime();
        FileStorageObject fso = fileStorageObjectRepository.findByKeyAndDeletedAtIsNull(key)
            .orElseThrow(() -> new StorageObjectNotFoundException(key));

        MDC.put("storageKey", key);
        MDC.put("ownerId", fso.getOwnerId());
        MDC.put("operation", "sign_download");
        MDC.put("provider", storageProperties.getProvider());
        try {
            transactionTemplate.execute(status -> {
                StorageAccessEvent event = StorageAccessEvent.builder()
                    .key(key)
                    .ownerId(fso.getOwnerId())
                    .sizeBytes(fso.getSizeBytes())
                    .build();
                storageAccessEventRepository.save(event);
                return null;
            });

            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(storageProperties.getPresignTtlSeconds()))
                    .getObjectRequest(r -> r
                        .bucket(storageProperties.getBucket())
                        .key(key))
                    .build());

            storageMetrics.recordLatency(StorageMetrics.DOWNLOAD_LATENCY, storageProperties.getProvider(), "success", System.nanoTime() - start);
            return new SignDownloadResponse(key, presigned.url().toString(), presigned.expiration());
        } catch (Exception ex) {
            storageMetrics.recordLatency(StorageMetrics.DOWNLOAD_LATENCY, storageProperties.getProvider(), "error", System.nanoTime() - start);
            storageMetrics.recordError("sign_download",
                ex instanceof ApplicationException ae ? ae.getErrorCode().getErrorCode() : "UNKNOWN");
            throw ex;
        } finally {
            MDC.remove("storageKey");
            MDC.remove("ownerId");
            MDC.remove("operation");
            MDC.remove("provider");
        }
    }

    public void softDelete(String key, String currentUserLogin) {
        long start = System.nanoTime();
        FileStorageObject fso = fileStorageObjectRepository.findByKeyAndDeletedAtIsNull(key)
            .orElseThrow(() -> new StorageObjectNotFoundException(key));

        MDC.put("storageKey", key);
        MDC.put("ownerId", fso.getOwnerId());
        MDC.put("operation", "soft_delete");
        MDC.put("provider", storageProperties.getProvider());
        try {
            if (!fso.getCreatedBy().equals(currentUserLogin)) {
                throw new AuthorizationException("User not authorized to delete this file", SecurityError.MISSING_RIGHTS);
            }

            int updated = fileStorageObjectRepository.softDeleteByKey(key, Instant.now());
            if (updated == 0) {
                throw new StorageObjectNotFoundException(key);
            }

            storageMetrics.recordLatency(StorageMetrics.DELETE_LATENCY, storageProperties.getProvider(), "success", System.nanoTime() - start);
        } catch (Exception ex) {
            storageMetrics.recordLatency(StorageMetrics.DELETE_LATENCY, storageProperties.getProvider(), "error", System.nanoTime() - start);
            storageMetrics.recordError("soft_delete",
                ex instanceof ApplicationException ae ? ae.getErrorCode().getErrorCode() : "UNKNOWN");
            throw ex;
        } finally {
            MDC.remove("storageKey");
            MDC.remove("ownerId");
            MDC.remove("operation");
            MDC.remove("provider");
        }
    }

    public String storeBytes(byte[] bytes, String storageKey, String contentType) {
        return storeBytes(bytes, storageKey, contentType, null);
    }

    public String storeBytes(byte[] bytes, String storageKey, String contentType, String contentDisposition) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Cannot store null or empty bytes for key: " + storageKey);
        }
        try {
            PutObjectRequest.Builder reqBuilder = PutObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(storageKey)
                .contentType(contentType)
                .contentLength((long) bytes.length);
            if (contentDisposition != null) {
                reqBuilder.contentDisposition(contentDisposition);
            }
            s3Client.putObject(reqBuilder.build(), RequestBody.fromBytes(bytes));
            log.info("Stored programmatic file: key={}, bytes={}", storageKey, bytes.length);
            return storageKey;
        } catch (Exception e) {
            log.error("Failed to store bytes: key={}", storageKey, e);
            throw new RuntimeException("Failed to store file: " + storageKey, e);
        }
    }

    public String signedDownloadUrl(String storageKey) {
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(storageProperties.getPresignTtlSeconds()))
                .getObjectRequest(r -> r.bucket(storageProperties.getBucket()).key(storageKey))
                .build());
        return presigned.url().toString();
    }

    public String signedDownloadUrl(String storageKey, Duration duration) {
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(r -> r.bucket(storageProperties.getBucket()).key(storageKey))
                .build());
        return presigned.url().toString();
    }

    public byte[] downloadBytes(String storageKey) {
        try {
            GetObjectRequest req = GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(storageKey)
                .build();
            return s3Client.getObjectAsBytes(req).asByteArray();
        } catch (Exception e) {
            log.error("Failed to download bytes: key={}", storageKey, e);
            throw new RuntimeException("Failed to download file: " + storageKey, e);
        }
    }

    public void deleteRawBytes(String storageKey) {
        try {
            s3Client.deleteObject(r -> r.bucket(storageProperties.getBucket()).key(storageKey));
            log.info("Deleted raw S3 object: key={}", storageKey);
        } catch (Exception e) {
            log.error("Failed to delete raw S3 object: key={}", storageKey, e);
            throw new RuntimeException("Failed to delete S3 object: " + storageKey, e);
        }
    }

    public void assertOwnership(String key, String expectedOwnerId) {
        fileStorageObjectRepository.findByKey(key)
            .ifPresent(fso -> {
                if (!expectedOwnerId.equals(fso.getOwnerId())) {
                    throw new AuthorizationException("File does not belong to caller", SecurityError.MISSING_RIGHTS);
                }
            });
    }

    private String resolveOriginalFilename(ConfirmUploadRequest request, String key) {
        if (request.originalFilename() != null) {
            return request.originalFilename();
        }
        int lastSlash = key.lastIndexOf('/');
        return lastSlash >= 0 ? key.substring(lastSlash + 1) : key;
    }

    private ConfirmUploadResponse toConfirmUploadResponse(FileStorageObject fso) {
        return new ConfirmUploadResponse(
            fso.getId(),
            fso.getKey(),
            fso.getSizeBytes(),
            fso.getContentType(),
            formatChecksum(fso.getChecksum()),
            fso.getUploadConfirmedAt()
        );
    }

    private String formatChecksum(String rawChecksum) {
        return rawChecksum != null && !rawChecksum.isBlank() ? "sha256:" + rawChecksum : null;
    }
}
