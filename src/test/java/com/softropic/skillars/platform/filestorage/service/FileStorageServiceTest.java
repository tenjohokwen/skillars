package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.blobstore.service.StorageMetrics;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.event.StorageObjectConfirmedEvent;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.repo.ScanStatus;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 (fix/filestorage-access-and-hygiene): owner scoping of sign-download and confirm, the confirm retry
 * re-driving {@link StorageObjectConfirmedEvent}, {@code assertOwnership} failing closed for unrecorded keys, and
 * no replication outbox rows while replication is disabled. Real-storage coverage of the same paths lives in
 * {@link FileStorageConfirmUploadIT} / {@link FileStorageDownloadIT}.
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    private static final String OWNER = "1001";
    private static final String OTHER = "2002";

    @Mock private ValidationChain validationChain;
    @Mock private FileStorageObjectRepository repo;
    @Mock private S3Presigner presigner;
    @Mock private S3Client s3Client;
    @Mock private OutboxReplicationJobRepository outboxRepo;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private StorageAccessEventRepository accessEventRepo;
    @Mock private StorageMetrics metrics;
    @Mock private SecurityUtil securityUtil;
    @Mock private MediaScanPolicy mediaScanPolicy;

    private final StorageKeyGenerator keyGenerator = new StorageKeyGenerator();
    private final BlobstoreProperties blobstoreProperties = new BlobstoreProperties();
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        blobstoreProperties.setBucket("b");
        blobstoreProperties.setEndpointUrl("http://s3");
        service = new FileStorageService(validationChain, repo, keyGenerator, presigner, blobstoreProperties,
            new FileStorageProperties(), s3Client, outboxRepo, publisher, transactionTemplate, accessEventRepo,
            metrics, securityUtil, mediaScanPolicy);
        lenient().when(securityUtil.requireCurrentBusinessId()).thenReturn(OWNER);
        lenient().when(mediaScanPolicy.initialStatus(any())).thenReturn(ScanStatus.NOT_REQUIRED);
    }

    // ---- S-18: no URL for an object without a clean abuse-media verdict ----

    @Test
    void signedDownloadUrl_withheldForPendingFlaggedAndQuarantined_servedForCleanAndNotRequired() throws Exception {
        for (ScanStatus withheld : List.of(ScanStatus.PENDING, ScanStatus.FLAGGED, ScanStatus.QUARANTINED)) {
            when(repo.findByKey("k-" + withheld)).thenReturn(Optional.of(scanned("k-" + withheld, withheld)));
            assertThat(service.signedDownloadUrl("k-" + withheld)).isNull();
            assertThat(service.signedDownloadUrl("k-" + withheld, java.time.Duration.ofMinutes(1))).isNull();
        }
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));

        when(repo.findByKey("k-clean")).thenReturn(Optional.of(scanned("k-clean", ScanStatus.CLEAN)));
        stubPresign();
        assertThat(service.signedDownloadUrl("k-clean")).isEqualTo("http://s3/b/x");
        // A server-written object has no row: not scanned, still served.
        assertThat(service.signedDownloadUrl("server-written")).isEqualTo("http://s3/b/x");
    }

    @Test
    void downloadBytes_withheldObject_isRefused() {
        when(repo.findByKey("k")).thenReturn(Optional.of(scanned("k", ScanStatus.PENDING)));

        assertThatThrownBy(() -> service.downloadBytes("k")).isInstanceOf(RuntimeException.class);
        verify(s3Client, never()).getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class));
    }

    @Test
    void signDownload_ownerOfFlaggedObject_gets404_ownerOfPendingObject_getsUrl() throws Exception {
        when(repo.findByKeyAndDeletedAtIsNull("flagged")).thenReturn(Optional.of(scanned("flagged", ScanStatus.FLAGGED)));
        when(securityUtil.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> service.signDownload("flagged")).isInstanceOf(StorageObjectNotFoundException.class);

        when(repo.findByKeyAndDeletedAtIsNull("pending")).thenReturn(Optional.of(scanned("pending", ScanStatus.PENDING)));
        stubTransactionTemplate();
        stubPresign();
        assertThat(service.signDownload("pending").key()).isEqualTo("pending");
    }

    private static FileStorageObject scanned(String key, ScanStatus status) {
        return FileStorageObject.builder().key(key).ownerId(OWNER).provider("s3").bucket("b").sizeBytes(3)
            .contentType("image/png").uploadConfirmedAt(Instant.now()).scanStatus(status).build();
    }

    // ---- #1 sign/download is owner-or-admin only ----

    @Test
    void signDownload_nonOwnerNonAdmin_is404AndMintsNoUrl() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKeyAndDeletedAtIsNull(key)).thenReturn(Optional.of(fso(key, OTHER)));
        when(securityUtil.isAdmin()).thenReturn(false);

        // 404, not 403: must not reveal that another user's key exists
        assertThatThrownBy(() -> service.signDownload(key)).isInstanceOf(StorageObjectNotFoundException.class);
        verify(presigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
        verify(accessEventRepo, never()).save(any());
    }

    @Test
    void signDownload_owner_getsUrl() throws Exception {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKeyAndDeletedAtIsNull(key)).thenReturn(Optional.of(fso(key, OWNER)));
        stubTransactionTemplate();
        stubPresign();

        assertThat(service.signDownload(key).downloadUrl()).isEqualTo("http://s3/b/x");
    }

    @Test
    void signDownload_admin_getsUrlForAnyOwner_evenWithoutBusinessId() throws Exception {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKeyAndDeletedAtIsNull(key)).thenReturn(Optional.of(fso(key, OTHER)));
        when(securityUtil.isAdmin()).thenReturn(true);
        lenient().when(securityUtil.requireCurrentBusinessId())
            .thenThrow(new org.springframework.security.authentication.InsufficientAuthenticationException("none"));
        stubTransactionTemplate();
        stubPresign();

        assertThat(service.signDownload(key).downloadUrl()).isEqualTo("http://s3/b/x");
    }

    // ---- #2 / #6 confirm's idempotent path ----

    @Test
    void confirm_existingRecordOfAnotherOwner_is404AndNotReturned() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.of(fso(key, OTHER)));

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(StorageObjectNotFoundException.class);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirm_retryOfOwnRecord_republishesConfirmedEvent() {
        String key = keyGenerator.generate("coach_profile", OWNER, "png");
        FileStorageObject existing = fso(key, OWNER);
        when(repo.findByKey(key)).thenReturn(Optional.of(existing));

        assertThat(service.confirmUpload(key, confirmRequest()).key()).isEqualTo(key);

        verify(publisher).publishEvent(any(StorageObjectConfirmedEvent.class));
        verify(s3Client, never()).headObject(any(Consumer.class));
    }

    @Test
    void confirm_lateReplayOfOldOwnRecord_doesNotRepublish() {
        // a replay long after the upload window must not re-link an old key (e.g. revert the profile photo)
        String key = keyGenerator.generate("coach_profile", OWNER, "png");
        FileStorageObject old = FileStorageObject.builder().key(key).ownerId(OWNER).provider("s3").bucket("b")
            .sizeBytes(3).uploadConfirmedAt(Instant.now().minusSeconds(86_400)).build();
        when(repo.findByKey(key)).thenReturn(Optional.of(old));

        assertThat(service.confirmUpload(key, confirmRequest()).key()).isEqualTo(key);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void confirm_retryOfSoftDeletedRecord_is404() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        FileStorageObject deleted = FileStorageObject.builder().key(key).ownerId(OWNER).provider("s3").bucket("b")
            .sizeBytes(3).deletedAt(Instant.now()).build();
        when(repo.findByKey(key)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(StorageObjectNotFoundException.class);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirm_serverWrittenKey_is404WithoutTouchingStorage() {
        assertThatThrownBy(() -> service.confirmUpload(
            "reports/3f1c2a4e-1b2c-4d5e-8f90-123456789abc/report.pdf", confirmRequest()))
            .isInstanceOf(StorageObjectNotFoundException.class);
        verify(s3Client, never()).headObject(any(Consumer.class));
    }

    @Test
    void confirm_ownerBoundKeyOfAnotherUser_isDenied() {
        String key = keyGenerator.generate("coach_branding", OTHER, "png");

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(AccessDeniedException.class);
        verify(repo, never()).findByKey(any());
    }

    @Test
    void confirm_concurrentDuplicateInsert_fallsBackToIdempotentPath() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty(), Optional.of(fso(key, OWNER)));
        stubHead();
        when(transactionTemplate.execute(any())).thenThrow(new DataIntegrityViolationException("dup key",
            new java.sql.SQLException("duplicate key value violates unique constraint", "23505")));

        assertThat(service.confirmUpload(key, confirmRequest()).key()).isEqualTo(key);
        verify(publisher).publishEvent(any(StorageObjectConfirmedEvent.class));
    }

    @Test
    void confirm_otherConstraintViolation_isNotMaskedAsIdempotentSuccess() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        when(transactionTemplate.execute(any())).thenThrow(new DataIntegrityViolationException("too long",
            new java.sql.SQLException("value too long", "22001")));

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(DataIntegrityViolationException.class);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void confirm_realSizeOverQuota_isRejectedAtConfirm() {
        // the presigned PUT does not bind length: sign with a tiny declared size, upload more, confirm the real size
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        stubTransactionTemplate();
        when(repo.sumSizeBytesByOwnerId(OWNER)).thenReturn(new FileStorageProperties().getQuota().getDefaultBytes());

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    // C5: the usage sum must be read under the owner's quota lock, or parallel confirms both pass it.
    @Test
    void confirm_quotaIsCheckedUnderTheOwnersLock_thenInserted() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        stubTransactionTemplate();
        when(repo.saveAndFlush(any(FileStorageObject.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmUpload(key, confirmRequest());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repo);
        order.verify(repo).acquireOwnerQuotaLock(OWNER);
        order.verify(repo).sumSizeBytesByOwnerId(OWNER);
        order.verify(repo).saveAndFlush(any(FileStorageObject.class));
    }

    // C5: a parallel confirm of the SAME key that committed while this one waited for the lock is the
    // idempotent retry — it must not be charged against the quota a second time or inserted twice.
    @Test
    void confirm_sameKeyCommittedWhileWaitingForTheLock_isTheIdempotentPath() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty(), Optional.of(fso(key, OWNER)));
        stubHead();
        stubTransactionTemplate();

        assertThat(service.confirmUpload(key, confirmRequest()).key()).isEqualTo(key);
        verify(repo, never()).sumSizeBytesByOwnerId(any());
        verify(repo, never()).saveAndFlush(any());
    }

    // The PUT does not bind content: a "PDF" that is really something else is rejected and its bytes removed,
    // and no row is written, so it cannot count against the owner's quota.
    @Test
    @SuppressWarnings("unchecked")
    void confirm_contentNotMatchingDeclaredType_isRejectedAndTheObjectDeleted() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        stubHeader("MZ\u0090\u0000 not a pdf".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException.class)
            .hasMessageContaining("content does not match");
        verify(s3Client).deleteObject(any(Consumer.class));
        verify(repo, never()).saveAndFlush(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirm_realSizeOverQuota_deletesTheRejectedObject() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        stubTransactionTemplate();
        when(repo.sumSizeBytesByOwnerId(OWNER)).thenReturn(new FileStorageProperties().getQuota().getDefaultBytes());

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException.class);
        verify(s3Client).deleteObject(any(Consumer.class));
    }

    @Test
    void signUpload_entityOutsideTheAllowList_isRejected() {
        var request = new com.softropic.skillars.platform.filestorage.contract.SignUploadRequest(
            "reports", OWNER, "application/pdf", "pdf", 3L, null, null);

        assertThatThrownBy(() -> service.signUpload(request))
            .isInstanceOf(com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException.class)
            .hasMessageContaining("entity not allowed");
        verify(presigner, never()).presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class));
    }

    @Test
    void signUpload_ownerBoundEntitySpelledToSanitizeIntoIt_isDenied() {
        // "coach profile" sanitizes to coach_profile, so it must get the same owner check
        var request = new com.softropic.skillars.platform.filestorage.contract.SignUploadRequest(
            "coach profile", OTHER, "image/png", "png", 3L, null, null);

        assertThatThrownBy(() -> service.signUpload(request)).isInstanceOf(AccessDeniedException.class);
        verify(presigner, never()).presignPutObject(
            any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class));
    }

    // ---- #5 no replication outbox rows while replication is disabled ----

    @Test
    void confirm_replicationDisabled_savesNoOutboxJob() {
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        stubTransactionTemplate();
        when(repo.saveAndFlush(any(FileStorageObject.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmUpload(key, confirmRequest());

        verify(outboxRepo, never()).save(any());
        verify(publisher).publishEvent(any(StorageObjectConfirmedEvent.class));
    }

    @Test
    void confirm_replicationEnabled_savesReplicateJob() {
        blobstoreProperties.getReplication().setEnabled(true);
        String key = keyGenerator.generate("documents", "42", "pdf");
        when(repo.findByKey(key)).thenReturn(Optional.empty());
        stubHead();
        stubTransactionTemplate();
        when(repo.saveAndFlush(any(FileStorageObject.class))).thenAnswer(inv -> inv.getArgument(0));

        service.confirmUpload(key, confirmRequest());

        verify(outboxRepo).save(any(OutboxReplicationJob.class));
    }

    // ---- #3 assertOwnership fails closed ----

    @Test
    void assertOwnership_keyWithNoRecord_fails() {
        when(repo.findByKeyAndDeletedAtIsNull("reports/x/report.pdf")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertOwnership("reports/x/report.pdf", OWNER))
            .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void assertOwnership_otherOwner_fails_ownRecord_passes() {
        when(repo.findByKeyAndDeletedAtIsNull("k1")).thenReturn(Optional.of(fso("k1", OTHER)));
        when(repo.findByKeyAndDeletedAtIsNull("k2")).thenReturn(Optional.of(fso("k2", OWNER)));

        assertThatThrownBy(() -> service.assertOwnership("k1", OWNER)).isInstanceOf(AuthorizationException.class);
        assertThatCode(() -> service.assertOwnership("k2", OWNER)).doesNotThrowAnyException();
    }

    private static FileStorageObject fso(String key, String owner) {
        return FileStorageObject.builder().key(key).ownerId(owner).provider("s3").bucket("b").sizeBytes(3)
            .contentType("application/pdf").uploadConfirmedAt(Instant.now()).build();
    }

    private static ConfirmUploadRequest confirmRequest() {
        return new ConfirmUploadRequest("application/pdf", 3L, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private void stubHead() {
        when(s3Client.headObject(any(Consumer.class))).thenReturn(
            HeadObjectResponse.builder().contentType("application/pdf").contentLength(3L).eTag("\"e\"").build());
        stubHeader("%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @SuppressWarnings("unchecked")
    private void stubHeader(byte[] bytes) {
        lenient().when(s3Client.getObjectAsBytes(any(Consumer.class))).thenReturn(
            software.amazon.awssdk.core.ResponseBytes.fromByteArray(
                software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(), bytes));
    }

    @SuppressWarnings("unchecked")
    private void stubTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
    }

    private void stubPresign() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("http://s3/b/x").toURL());
        when(presigned.expiration()).thenReturn(Instant.now().plusSeconds(300));
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
    }
}
