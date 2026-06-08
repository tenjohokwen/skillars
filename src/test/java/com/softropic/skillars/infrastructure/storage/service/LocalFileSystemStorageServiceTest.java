package com.softropic.skillars.infrastructure.storage.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.blobstore.service.LocalFileSystemStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalFileSystemStorageService service;

    @BeforeEach
    void setUp() {
        service = new LocalFileSystemStorageService(tempDir);
    }

    @Test
    void put_createsCorrectDirectoryStructure() throws IOException {
        String key = "docs/42/2026/05/uuid.txt";
        byte[] content = "test content".getBytes(StandardCharsets.UTF_8);
        service.put(key, new ByteArrayInputStream(content), content.length, "text/plain");

        Path target = tempDir.resolve(key);
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
    }

    @Test
    void get_returnsCorrectStreamAndMetadata() throws IOException {
        String key = "test.txt";
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        service.put(key, new ByteArrayInputStream(content), content.length, "text/plain");

        StorageObject obj = service.get(key);
        try (InputStream in = obj.data()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(obj.metadata().key()).isEqualTo(key);
    }

    @Test
    void delete_removesFile() throws IOException {
        String key = "to-delete.txt";
        service.put(key, new ByteArrayInputStream("data".getBytes()), 4, "text/plain");
        
        service.delete(key);
        assertThat(Files.exists(tempDir.resolve(key))).isFalse();
        
        // Ensure idempotent
        service.delete(key);
    }

    @Test
    void exists_returnsTrueForExistingKey() throws IOException {
        String key = "exists.txt";
        service.put(key, new ByteArrayInputStream("data".getBytes()), 4, "text/plain");
        
        assertThat(service.exists(key)).isTrue();
        assertThat(service.exists("missing.txt")).isFalse();
    }

    @Test
    void stat_returnsCorrectMetadata() throws IOException {
        String key = "stat.txt";
        byte[] content = "data".getBytes();
        service.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
        
        StorageObjectMetadata meta = service.stat(key);
        assertThat(meta.key()).isEqualTo(key);
        assertThat(meta.contentLength()).isEqualTo(content.length);
        assertThat(meta.lastModified()).isNotNull();
    }

    @Test
    void copy_duplicatesFileToNewKey() throws IOException {
        String sourceKey = "source.txt";
        String destKey = "dest.txt";
        byte[] content = "data".getBytes();
        service.put(sourceKey, new ByteArrayInputStream(content), content.length, "text/plain");
        
        service.copy(sourceKey, destKey);
        
        assertThat(Files.exists(tempDir.resolve(destKey))).isTrue();
        assertThat(Files.readAllBytes(tempDir.resolve(destKey))).isEqualTo(content);
    }

    @Test
    void get_nonExistentKey_throwsStorageObjectNotFoundException() {
        assertThatThrownBy(() -> service.get("missing.txt"))
            .isInstanceOf(StorageObjectNotFoundException.class);
    }
}
