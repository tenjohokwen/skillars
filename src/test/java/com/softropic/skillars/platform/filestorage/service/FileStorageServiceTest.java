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

    private final StorageKeyGenerator keyGenerator = new StorageKeyGenerator();
    private final BlobstoreProperties blobstoreProperties = new BlobstoreProperties();
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        blobstoreProperties.setBucket("b");
        blobstoreProperties.setEndpointUrl("http://s3");
        service = new FileStorageService(validationChain, repo, keyGenerator, presigner, blobstoreProperties,
            new FileStorageProperties(), s3Client, outboxRepo, publisher, transactionTemplate, accessEventRepo,
            metrics, securityUtil);
        lenient().when(securityUtil.requireCurrentBusinessId()).thenReturn(OWNER);
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
        when(repo.sumSizeBytesByOwnerId(OWNER)).thenReturn(new FileStorageProperties().getQuota().getDefaultBytes());

        assertThatThrownBy(() -> service.confirmUpload(key, confirmRequest()))
            .isInstanceOf(com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException.class);
        verify(transactionTemplate, never()).execute(any());
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
