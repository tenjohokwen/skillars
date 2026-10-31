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
import com.softropic.skillars.platform.filestorage.contract.StorageObjectDto;
import com.softropic.skillars.platform.filestorage.contract.ValidationRequest;
import com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.filestorage.event.StorageObjectConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
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
import java.util.Set;

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

    /**
     * Entities whose key's {@code entityId} segment must be the uploader's own business id. Checked at sign time
     * by {@code StorageResource} and again at confirm time here (the confirm endpoint takes an arbitrary key).
     * skillars-deferred-151 AC3 / feat/coach-workflow-gaps D9.
     */
    public static final Set<String> OWNER_BOUND_ENTITIES = Set.of("coach_profile", "coach_branding", "coach_gallery");

    private String getOwnerId() {
        return securityUtil.requireCurrentBusinessId();
    }

    public SignUploadResponse signUpload(SignUploadRequest request) {
        String ownerId = getOwnerId();
        MDC.put("operation", "sign_upload");
        MDC.put("provider", storageProperties.getProvider());
        MDC.put("ownerId", ownerId);
        try {
            if (!fileStorageProperties.getValidation().getAllowedEntities()
                .contains(StorageKeyGenerator.sanitizeSegment(request.entity()))) {
                throw new StorageValidationException("upload entity not allowed: " + request.entity());
            }
            // Compare the SANITIZED entity: the key is built from it, so "coach profile" / "coach.profile" would
            // otherwise skip the check and still land in coach_profile/<someone-else>/.
            if (OWNER_BOUND_ENTITIES.contains(StorageKeyGenerator.sanitizeSegment(request.entity()))
                && !ownerId.equals(request.entityId())) {
                throw new AccessDeniedException("entityId must match authenticated user");
            }

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
        storageKeyGenerator.parse(key)
            .filter(parts -> OWNER_BOUND_ENTITIES.contains(parts.entity()))
            .filter(parts -> !parts.entityId().equals(StorageKeyGenerator.sanitizeSegment(ownerId)))
            .ifPresent(parts -> {
                throw new AccessDeniedException("key entityId must match authenticated user");
            });

        Optional<FileStorageObject> existing = fileStorageObjectRepository.findByKey(key);
        if (existing.isPresent()) {
            return reconfirm(existing.get(), ownerId);
        }
        // Only keys signUpload issued can be newly confirmed: a server-written key (reports/..., gdpr/exports/...)
        // has no FSO row, and confirming it would make the caller its recorded owner. 404, as for a missing object.
        if (!storageKeyGenerator.isGeneratedKey(key)) {
            throw new StorageObjectNotFoundException(key);
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
                discardRejectedUpload(key);
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
            // The presigned PUT does not bind the length, so re-apply the size and quota limits to the REAL size
            // here; sign-time checks only saw the client's declared size. The quota check itself runs inside the
            // insert transaction, under the owner's quota lock (see persistConfirmedWithinQuota).
            if (head.contentLength() > fileStorageProperties.getValidation().getMaxSizeBytes()) {
                discardRejectedUpload(key);
                throw new StorageValidationException("file exceeds maximum size: " + head.contentLength());
            }
            // Nor does the PUT bind the content: an "image/png" upload can be any bytes. Check the real header
            // of images and PDFs against the declared type.
            if (ContentSniffer.isChecked(head.contentType()) && !ContentSniffer.matches(head.contentType(), readHeader(key))) {
                discardRejectedUpload(key);
                throw new StorageValidationException(
                    "content does not match declared type " + head.contentType() + " for key " + key);
            }

            FileStorageObject saved;
            try {
                saved = transactionTemplate.execute(status -> persistConfirmedWithinQuota(key, ownerId, request, head));
                if (saved == null) {
                    // A parallel confirm of this same key committed while this one waited for the owner lock: the
                    // idempotent retry, not a second upload to charge against the quota.
                    FileStorageObject winner = fileStorageObjectRepository.findByKey(key)
                        .orElseThrow(() -> new StorageObjectNotFoundException(key));
                    return reconfirm(winner, ownerId);
                }
            } catch (QuotaExceededException over) {
                discardRejectedUpload(key);
                throw over;
            } catch (DataIntegrityViolationException race) {
                // A concurrent confirm of the same key won the unique index (idx_storage_objects_key): treat
                // this one as the idempotent retry it is. Any other constraint failure is a real error.
                if (!isDuplicateKeyViolation(race)) {
                    throw race;
                }
                FileStorageObject winner = fileStorageObjectRepository.findByKey(key).orElseThrow(() -> race);
                return reconfirm(winner, ownerId);
            }

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

    /** The first {@link ContentSniffer#HEADER_BYTES} bytes of the stored object (fewer for a shorter one). */
    private byte[] readHeader(String key) {
        try {
            return s3Client.getObjectAsBytes(r -> r
                .bucket(storageProperties.getBucket())
                .key(key)
                .range("bytes=0-" + (ContentSniffer.HEADER_BYTES - 1))).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new StorageObjectNotFoundException(key, e);
        }
    }

    /**
     * Removes the bytes of an upload confirm rejected. A rejected confirm writes no {@code file_storage_objects}
     * row, so nothing counts against the owner's quota; this stops the stored object lingering unreferenced.
     * Best-effort: a failed delete is logged, and the original rejection still reaches the caller.
     */
    private void discardRejectedUpload(String key) {
        try {
            s3Client.deleteObject(r -> r.bucket(storageProperties.getBucket()).key(key));
        } catch (Exception e) {
            log.warn("Could not delete rejected upload: key={}", key, e);
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
            // Only the uploader (or an admin) may mint a URL through this endpoint. Every legitimate cross-user read
            // (coach photo/gallery on a public profile, branding logo, report PDFs, GDPR exports) is served by its
            // owning module through signedDownloadUrl after that module's own authorization check.
            // 404 rather than 403 so the endpoint does not reveal whether someone else's key exists.
            if (!securityUtil.isAdmin() && !fso.getOwnerId().equals(getOwnerId())) {
                throw new StorageObjectNotFoundException(key);
            }
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

    /**
     * feat/coach-workflow-gaps D9: the confirmed, not-deleted storage object for {@code key}, if any. Lets a
     * domain module validate an uploaded file's real (HEAD-verified at confirm time) owner, content type and
     * size before linking it, instead of trusting what the client declared.
     */
    public Optional<StorageObjectDto> findActiveObject(String key) {
        return fileStorageObjectRepository.findByKeyAndDeletedAtIsNull(key)
            .map(fso -> new StorageObjectDto(
                fso.getKey(), fso.getOwnerId(), fso.getOriginalFilename(), fso.getContentType(),
                fso.getSizeBytes(), formatChecksum(fso.getChecksum()), fso.getTags(), fso.getProvider(),
                fso.getBucket(), fso.getUploadConfirmedAt()));
    }

    /** True for a key of the shape signUpload issues (a browser upload), false for server-written keys. */
    public boolean isClientUploadKey(String key) {
        return storageKeyGenerator.isGeneratedKey(key);
    }

    /**
     * Passes only when {@code key} is a confirmed, not-deleted upload recorded as owned by {@code expectedOwnerId}.
     * A key with no FSO row (a server-written object from {@link #storeBytes}, e.g. a report PDF or GDPR export,
     * or a key that was never confirmed) fails: absence of a record is not proof of ownership.
     */
    public void assertOwnership(String key, String expectedOwnerId) {
        FileStorageObject fso = fileStorageObjectRepository.findByKeyAndDeletedAtIsNull(key)
            .orElseThrow(() -> new AuthorizationException("File does not belong to caller", SecurityError.MISSING_RIGHTS));
        if (!expectedOwnerId.equals(fso.getOwnerId())) {
            throw new AuthorizationException("File does not belong to caller", SecurityError.MISSING_RIGHTS);
        }
    }

    /**
     * Idempotent confirm of an already-recorded key. Scoped to the owner (never hands another user's record to
     * the caller), refuses a soft-deleted object, and re-publishes {@link StorageObjectConfirmedEvent}: the first
     * confirm publishes after its commit, so if a listener (e.g. the coach-photo link) failed there, the client's
     * retry lands here and must re-drive it. Listeners are idempotent (they set a field to this key).
     */
    private ConfirmUploadResponse reconfirm(FileStorageObject fso, String ownerId) {
        // 404 (not 403) for someone else's key: do not reveal that it exists.
        if (!ownerId.equals(fso.getOwnerId()) || fso.getDeletedAt() != null) {
            throw new StorageObjectNotFoundException(fso.getKey());
        }
        // Re-publish only inside the upload window: that covers the client retrying a failed confirm, but a late
        // replay of an OLD key must not re-link it (e.g. revert the coach's profile photo to an earlier upload).
        Instant confirmedAt = fso.getUploadConfirmedAt();
        if (confirmedAt != null
            && confirmedAt.isAfter(Instant.now().minusSeconds(storageProperties.getUploadTtlSeconds()))) {
            applicationEventPublisher.publishEvent(new StorageObjectConfirmedEvent(this, fso));
        }
        return toConfirmUploadResponse(fso);
    }

    /** Is this the unique index on file_storage_objects.key (SQLState 23505), as opposed to any other constraint? */
    private static boolean isDuplicateKeyViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                return "23505".equals(cve.getSQLState())
                    && (cve.getConstraintName() == null || cve.getConstraintName().contains("idx_storage_objects_key"));
            }
            if (t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * C5: quota check and insert as one critical section per owner. Without the lock, two parallel confirms
     * by the same owner each read the pre-insert usage and both pass, overshooting the quota by up to one
     * file per concurrent request. The lock is the transaction's first statement; the usage sum is read
     * after it, so it includes every confirm that committed while this one waited.
     *
     * @return the new row, or {@code null} when a parallel confirm of the same key already committed it
     */
    private FileStorageObject persistConfirmedWithinQuota(String key, String ownerId, ConfirmUploadRequest request,
                                                          HeadObjectResponse head) {
        fileStorageObjectRepository.acquireOwnerQuotaLock(ownerId);
        if (fileStorageObjectRepository.findByKey(key).isPresent()) {
            return null;
        }
        long usage = fileStorageObjectRepository.sumSizeBytesByOwnerId(ownerId);
        if (usage + head.contentLength() > fileStorageProperties.getQuota().getDefaultBytes()) {
            throw new QuotaExceededException(ownerId, usage, head.contentLength());
        }
        return persistConfirmed(key, ownerId, request, head);
    }

    private FileStorageObject persistConfirmed(String key, String ownerId, ConfirmUploadRequest request,
                                               HeadObjectResponse head) {
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
            .uploadConfirmedAt(Instant.now())
            .build();

        // saveAndFlush so a duplicate key surfaces here as DataIntegrityViolationException, not at commit.
        FileStorageObject persistedFso = fileStorageObjectRepository.saveAndFlush(fso);

        // Nothing drains outbox_replication_jobs unless replication is enabled (OutboxPollerScheduler only exists
        // then), so enqueueing with it off would just pile up PENDING rows forever.
        if (storageProperties.getReplication().isEnabled()) {
            outboxReplicationJobRepository.save(OutboxReplicationJob.builder()
                .storageObject(persistedFso)
                .jobType(OutboxReplicationJob.ReplicationJobType.REPLICATE)
                .status(OutboxReplicationJob.ReplicationJobStatus.PENDING)
                .attemptCount(0)
                .build());
        }
        return persistedFso;
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
