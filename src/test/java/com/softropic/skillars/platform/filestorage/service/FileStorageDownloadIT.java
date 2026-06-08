package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObjectRepository;
import com.softropic.skillars.platform.filestorage.repo.OutboxReplicationJobRepository;
import com.softropic.skillars.platform.filestorage.repo.StorageAccessEventRepository;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignDownloadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.security.WithMockPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockPrincipal(username = "queb@yahoo.com", businessId = "675373350208068096")
class FileStorageDownloadIT extends BaseStorageIT {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileStorageObjectRepository fileStorageObjectRepository;

    @Autowired
    private OutboxReplicationJobRepository outboxReplicationJobRepository;

    @Autowired
    private StorageAccessEventRepository storageAccessEventRepository;

    @Autowired
    private jakarta.persistence.EntityManager em;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        storageAccessEventRepository.deleteAll();
        outboxReplicationJobRepository.deleteAll();
        fileStorageObjectRepository.deleteAll();
    }

    @Test
    void signDownload_afterConfirmedUpload_returnsUsableUrl() {
        byte[] content = "hello download".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        confirmUpload(signResponse, content, "application/pdf");

        SignDownloadResponse downloadResponse = fileStorageService.signDownload(signResponse.key());

        assertThat(downloadResponse.downloadUrl()).isNotBlank();
        assertThat(downloadResponse.expiresAt()).isAfter(Instant.now());

        ResponseEntity<byte[]> getResponse = restTemplate.getForEntity(
            URI.create(downloadResponse.downloadUrl()), byte[].class);
        assertThat(getResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getResponse.getBody()).isEqualTo(content);
    }

    @Test
    void signDownload_afterConfirmedUpload_persistsAccessEvent() {
        byte[] content = "access event content".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        confirmUpload(signResponse, content, "application/pdf");

        fileStorageService.signDownload(signResponse.key());

        List<com.softropic.skillars.platform.filestorage.repo.StorageAccessEvent> events =
            storageAccessEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getKey()).isEqualTo(signResponse.key());
        assertThat(events.get(0).getOwnerId()).isNotBlank();
        assertThat(events.get(0).getSizeBytes()).isPositive();
    }

    @Test
    void signDownload_nonExistentKey_throwsNotFoundException() {
        assertThatThrownBy(() -> fileStorageService.signDownload("nonexistent/key/file.pdf"))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    @Test
    void signDownload_softDeletedFile_throwsNotFoundException() {
        byte[] content = "soft deleted".getBytes();
        SignUploadResponse signResponse = signAndPut("documents", "42", "application/pdf", "pdf", content);
        confirmUpload(signResponse, content, "application/pdf");

        softDelete(signResponse.key());

        assertThatThrownBy(() -> fileStorageService.signDownload(signResponse.key()))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }

    private void softDelete(String key) {
        transactionTemplate.execute(status -> {
            em.createQuery("UPDATE FileStorageObject f SET f.deletedAt = :now WHERE f.key = :key")
                .setParameter("now", Instant.now())
                .setParameter("key", key)
                .executeUpdate();
            return null;
        });
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

    private ConfirmUploadResponse confirmUpload(SignUploadResponse signResponse, byte[] content, String contentType) {
        ConfirmUploadRequest confirmRequest = new ConfirmUploadRequest(
            contentType, content.length, null, null, null);
        return fileStorageService.confirmUpload(signResponse.key(), confirmRequest);
    }
}
