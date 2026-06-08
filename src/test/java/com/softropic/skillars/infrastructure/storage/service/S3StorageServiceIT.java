package com.softropic.skillars.infrastructure.storage.service;

import com.softropic.skillars.infrastructure.blobstore.service.StorageService;
import com.softropic.skillars.infrastructure.storage.BaseStorageIT;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3StorageServiceIT extends BaseStorageIT {

    @Autowired
    private StorageService storageService;

    private final List<String> keysToCleanup = new ArrayList<>();

    @BeforeEach
    void setUp() {
        keysToCleanup.clear();
    }

    @AfterEach
    void tearDown() {
        keysToCleanup.forEach(key -> {
            try {
                storageService.delete(key);
            } catch (Exception ignored) {
            }
        });
        keysToCleanup.clear();
    }

    @Test
    void contextLoadsAndMinioIsReachable() {
        String key = "smoke-test/" + Instancio.create(String.class) + ".txt";
        keysToCleanup.add(key);
        byte[] content = "hello minio".getBytes(StandardCharsets.UTF_8);

        storageService.put(key, new ByteArrayInputStream(content), content.length, "text/plain");

        assertThat(storageService.exists(key)).isTrue();

        storageService.delete(key);

        assertThat(storageService.exists(key)).isFalse();
    }

    @Test
    void put_and_get_returnsCorrectContentAndMetadata() throws IOException {
        String key = "s3-it/" + Instancio.create(String.class) + ".pdf";
        keysToCleanup.add(key);
        byte[] expected = "abc".getBytes(StandardCharsets.UTF_8);

        storageService.put(key, new ByteArrayInputStream(expected), expected.length, "application/pdf");

        StorageObject obj = storageService.get(key);
        try (InputStream inputStream = obj.data()) {
            byte[] result = inputStream.readAllBytes();
            assertThat(result).isEqualTo(expected);
            assertThat(obj.metadata().contentLength()).isEqualTo(expected.length);
            assertThat(obj.metadata().key()).isEqualTo(key);
        }
    }

    @Test
    void delete_removesObject() {
        String key = "s3-it/" + Instancio.create(String.class) + ".pdf";
        byte[] content = "delete-me".getBytes(StandardCharsets.UTF_8);

        storageService.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
        assertThat(storageService.exists(key)).isTrue();

        storageService.delete(key);
        assertThat(storageService.exists(key)).isFalse();

        // idempotent: second delete must not throw
        storageService.delete(key);
    }

    @Test
    void exists_returnsTrueAndFalse() {
        String key = "s3-it/" + Instancio.create(String.class) + ".pdf";
        keysToCleanup.add(key);
        byte[] content = "exists-check".getBytes(StandardCharsets.UTF_8);

        storageService.put(key, new ByteArrayInputStream(content), content.length, "application/pdf");
        assertThat(storageService.exists(key)).isTrue();

        assertThat(storageService.exists("nonexistent/smoke/" + UUID.randomUUID() + ".txt")).isFalse();
    }

    @Test
    void stat_returnsCorrectMetadata() {
        String key = "s3-it/" + Instancio.create(String.class) + ".pdf";
        keysToCleanup.add(key);
        byte[] content = "stat-me".getBytes(StandardCharsets.UTF_8);
        String contentType = "application/pdf";

        storageService.put(key, new ByteArrayInputStream(content), content.length, contentType);

        StorageObjectMetadata meta = storageService.stat(key);

        assertThat(meta.key()).isEqualTo(key);
        assertThat(meta.contentLength()).isEqualTo(content.length);
        assertThat(meta.lastModified()).isNotNull();
        assertThat(meta.contentType()).isEqualTo(contentType);
    }

    @Test
    void copy_duplicatesObjectToNewKey() throws IOException {
        String sourceKey = "s3-it/" + Instancio.create(String.class) + ".pdf";
        String destKey = "s3-it/" + Instancio.create(String.class) + "-copy.pdf";
        keysToCleanup.add(sourceKey);
        keysToCleanup.add(destKey);
        byte[] content = "copy-me".getBytes(StandardCharsets.UTF_8);

        storageService.put(sourceKey, new ByteArrayInputStream(content), content.length, "application/pdf");

        storageService.copy(sourceKey, destKey);

        assertThat(storageService.exists(sourceKey)).isTrue();
        assertThat(storageService.exists(destKey)).isTrue();

        StorageObject destObj = storageService.get(destKey);
        try (InputStream inputStream = destObj.data()) {
            byte[] destContent = inputStream.readAllBytes();
            assertThat(destContent).isEqualTo(content);
        }
    }

    @Test
    void get_nonExistentKey_throwsStorageObjectNotFoundException() {
        assertThatThrownBy(() -> storageService.get("non/existent/" + UUID.randomUUID() + ".pdf"))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }
}
