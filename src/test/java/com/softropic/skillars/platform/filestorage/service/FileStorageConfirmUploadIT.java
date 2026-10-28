package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.blobstore.config.BlobstoreProperties;
import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJob;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockPrincipal(username = "queb@yahoo.com", businessId = "675373350208068096")
class FileStorageConfirmUploadIT extends BaseStorageIT {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private BlobstoreProperties blobstoreProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private jakarta.persistence.EntityManager em;

    @BeforeEach
    void setUp() {
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
    }

    @Test
    void confirmUpload_afterRealPut_savesRecordAndReturnsResponse() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);

        ConfirmUploadResponse response = fileStorageService.confirmUpload(signResponse.key(), confirmRequest);

        assertThat(response.id()).isNotNull();
        assertThat(response.key()).isEqualTo(signResponse.key());
        assertThat(response.sizeBytes()).isEqualTo(content.length);
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.uploadedAt()).isNotNull();

        assertThat(fileStorageObjectRepository.findByKey(signResponse.key())).isPresent();

        // B3 #5: replication is disabled (as in every profile), so nothing would ever drain a REPLICATE job.
        assertThat(outboxReplicationJobRepository.findAll()).isEmpty();
    }

    @Test
    void confirmUpload_replicationEnabled_enqueuesPendingReplicateJob() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        blobstoreProperties.getReplication().setEnabled(true);
        try {
            fileStorageService.confirmUpload(signResponse.key(),
                new ConfirmUploadRequest("application/pdf", (long) content.length, null, null, null));
        } finally {
            blobstoreProperties.getReplication().setEnabled(false);
        }

        List<OutboxReplicationJob> jobs = outboxReplicationJobRepository.findAll();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getJobType()).isEqualTo(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        assertThat(jobs.get(0).getStatus()).isEqualTo(OutboxReplicationJob.ReplicationJobStatus.PENDING);
    }

    // B3 #2: the idempotent short-circuit used to hand any caller the existing record for a key.
    @Test
    void confirmUpload_idempotentPathOfAnotherOwnersRecord_isDenied() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);
        fileStorageService.confirmUpload(signResponse.key(), confirmRequest);
        reassignOwner(signResponse.key(), "someone-else");

        // 404, not 403: the endpoint must not reveal that another user's key exists.
        assertThatThrownBy(() -> fileStorageService.confirmUpload(signResponse.key(), confirmRequest))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    // B3 #3 (hardening): a server-written object (storeBytes, no FSO row) must not be confirmable — confirming it
    // would make the caller its recorded owner and so pass assertOwnership / sign-download for it.
    @Test
    void confirmUpload_serverWrittenKey_isNotFoundAndCreatesNoRecord() {
        byte[] pdf = new byte[]{1, 2, 3};
        String reportKey = "reports/" + java.util.UUID.randomUUID() + "/report.pdf";
        fileStorageService.storeBytes(pdf, reportKey, "application/pdf");

        assertThatThrownBy(() -> fileStorageService.confirmUpload(reportKey,
            new ConfirmUploadRequest("application/pdf", (long) pdf.length, null, null, null)))
            .isInstanceOf(StorageObjectNotFoundException.class);
        assertThat(fileStorageObjectRepository.findByKey(reportKey)).isEmpty();
    }

    private void reassignOwner(String key, String ownerId) {
        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.ownerId = :owner WHERE f.key = :key")
                .setParameter("owner", ownerId)
                .setParameter("key", key)
                .executeUpdate();
            return null;
        });
    }

    @Test
    void confirmUpload_missingKey_throws404() {
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", 3L, null, null, null);

        assertThatThrownBy(() -> fileStorageService.confirmUpload("nonexistent/key/file.pdf", confirmRequest))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void confirmUpload_contentTypeMismatch_throws422() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "image/png", (long) content.length, null, null, null);

        assertThatThrownBy(() -> fileStorageService.confirmUpload(signResponse.key(), confirmRequest))
            .isInstanceOf(StorageValidationException.class)
            .hasMessageContaining("contentType mismatch");
    }

    @Test
    void confirmUpload_fileSizeMismatch_throws422() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", 99L, null, null, null);

        assertThatThrownBy(() -> fileStorageService.confirmUpload(signResponse.key(), confirmRequest))
            .isInstanceOf(StorageValidationException.class)
            .hasMessageContaining("fileSizeBytes mismatch");
    }

    @Test
    void confirmUpload_idempotent_returnsExistingRecord() {
        byte[] content = new byte[]{1, 2, 3};
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);

        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            "application/pdf", (long) content.length, null, null, null);

        ConfirmUploadResponse first = fileStorageService.confirmUpload(signResponse.key(), confirmRequest);
        ConfirmUploadResponse second = fileStorageService.confirmUpload(signResponse.key(), confirmRequest);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(fileStorageObjectRepository.findAll()).hasSize(1);
    }

    private SignUploadResponse signAndPut(String entity, String entityId, String contentType, String extension, byte[] content) {
        SignUploadRequest signRequest = new SignUploadRequest(
            entity, entityId, contentType, extension, (long) content.length, null, null);

        SignUploadResponse signResponse = fileStorageService.signUpload(signRequest);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        restTemplate.exchange(
            URI.create(signResponse.uploadUrl()), HttpMethod.PUT,
            new HttpEntity<>(content, headers), Void.class);

        return signResponse;
    }
}
