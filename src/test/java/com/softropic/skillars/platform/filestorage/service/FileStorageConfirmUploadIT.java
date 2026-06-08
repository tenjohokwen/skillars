package com.softropic.skillars.platform.filestorage.service;

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

        List<OutboxReplicationJob> jobs = outboxReplicationJobRepository.findAll();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getJobType()).isEqualTo(OutboxReplicationJob.ReplicationJobType.REPLICATE);
        assertThat(jobs.get(0).getStatus()).isEqualTo(OutboxReplicationJob.ReplicationJobStatus.PENDING);
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
