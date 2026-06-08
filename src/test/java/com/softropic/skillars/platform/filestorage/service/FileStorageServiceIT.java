package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository;
import com.softropic.skillars.platform.filestorage.config.FileStorageProperties;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;

@WithMockPrincipal(username = "queb@yahoo.com", businessId = "675373350208068096")
class FileStorageServiceIT extends BaseStorageIT {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    private StorageAccessEventRepository storageAccessEventRepository;

    @Autowired
    private FileStorageProperties fileStorageProperties;

    @Autowired
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        storageAccessEventRepository.deleteAll();
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
    }

    @Test
    void signUpload_validRequest_returnsUrlAndKey() {
        SignUploadRequest request = new SignUploadRequest(
            "documents", "42", "application/pdf", "pdf", 1024L, null, null);

        SignUploadResponse response = fileStorageService.signUpload(request);

        assertThat(response.key()).matches("documents/42/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.pdf");
        assertThat(response.uploadUrl()).isNotBlank();
        assertThat(response.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void signUpload_uploadUrlIsUsable() {
        SignUploadRequest request = new SignUploadRequest(
            "avatars", "user-1", "application/pdf", "pdf", 3L, null, null);

        SignUploadResponse response = fileStorageService.signUpload(request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        HttpEntity<byte[]> entity = new HttpEntity<>(new byte[]{1, 2, 3}, headers);

        ResponseEntity<Void> putResponse = restTemplate.exchange(
            URI.create(response.uploadUrl()), HttpMethod.PUT, entity, Void.class);

        assertThat(putResponse.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @WithMockPrincipal(businessId = "tenant-quota")
    void signUpload_quotaExceeded_throws() {
        String ownerId = "tenant-quota";

        FileStorageObject existingFile = Instancio.of(FileStorageObject.class)
            .ignore(field(BaseEntity.class, "id"))
            .set(field(FileStorageObject.class, "ownerId"), ownerId)
            .set(field(FileStorageObject.class, "sizeBytes"), fileStorageProperties.getQuota().getDefaultBytes())
            .set(field(FileStorageObject.class, "provider"), "s3")
            .set(field(FileStorageObject.class, "tags"), null)
            .create();
        fileStorageObjectRepository.save(existingFile);

        SignUploadRequest request = new SignUploadRequest(
            "documents", "42", "application/pdf", "pdf", 1L, null, null);

        assertThatThrownBy(() -> fileStorageService.signUpload(request))
            .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void signUpload_invalidMimeType_throws() {
        SignUploadRequest request = new SignUploadRequest(
            "documents", "42", "application/x-executable", "pdf", 1024L, null, null);

        assertThatThrownBy(() -> fileStorageService.signUpload(request))
            .isInstanceOf(StorageValidationException.class);
    }
}
