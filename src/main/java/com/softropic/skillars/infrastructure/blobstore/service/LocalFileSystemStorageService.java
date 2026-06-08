package com.softropic.skillars.infrastructure.blobstore.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class LocalFileSystemStorageService implements StorageService {

    private final Path baseDir;

    @Override
    public void put(String key, InputStream data, long contentLength, String contentType) {
        Path targetPath = baseDir.resolve(key).normalize();
        if (!targetPath.startsWith(baseDir)) {
            throw new StorageProviderException("put", new IllegalArgumentException("Key escapes base directory: " + key), key);
        }
        Path tempPath = targetPath.resolveSibling("." + targetPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(targetPath.getParent());
            Files.copy(data, tempPath, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored file at: {}", targetPath);
        } catch (IOException e) {
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            throw new StorageProviderException("put", e, key);
        }
    }

    @Override
    public StorageObject get(String key) {
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir)) {
            throw new StorageProviderException("get", new IllegalArgumentException("Key escapes base directory: " + key), key);
        }
        try {
            StorageObjectMetadata metadata = stat(key);
            return new StorageObject(Files.newInputStream(path), metadata);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new StorageObjectNotFoundException("File not found: " + key);
        } catch (IOException e) {
            throw new StorageProviderException("get", e, key);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path path = baseDir.resolve(key).normalize();
            if (!path.startsWith(baseDir)) {
                throw new StorageProviderException("delete", new IllegalArgumentException("Key escapes base directory: " + key), key);
            }
            Files.deleteIfExists(path);
            log.info("Deleted file: {}", key);
        } catch (IOException e) {
            throw new StorageProviderException("delete", e, key);
        }
    }

    @Override
    public boolean exists(String key) {
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir)) {
            throw new StorageProviderException("exists", new IllegalArgumentException("Key escapes base directory: " + key), key);
        }
        return Files.exists(path);
    }

    @Override
    public StorageObjectMetadata stat(String key) {
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir)) {
            throw new StorageProviderException("stat", new IllegalArgumentException("Key escapes base directory: " + key), key);
        }
        if (!Files.exists(path)) {
            throw new StorageObjectNotFoundException("File not found: " + key);
        }
        try {
            long size = Files.size(path);
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            String ct = Files.probeContentType(path);
            if (ct == null) ct = "application/octet-stream";
            return new StorageObjectMetadata(key, ct, size, "", lastModified);
        } catch (IOException e) {
            throw new StorageProviderException("stat", e, key);
        }
    }

    @Override
    public void copy(String sourceKey, String destinationKey) {
        Path sourcePath = baseDir.resolve(sourceKey).normalize();
        Path destPath = baseDir.resolve(destinationKey).normalize();
        if (!sourcePath.startsWith(baseDir) || !destPath.startsWith(baseDir)) {
            throw new StorageProviderException("copy", new IllegalArgumentException("Key escapes base directory"), sourceKey + "->" + destinationKey);
        }
        if (!Files.exists(sourcePath)) {
            throw new StorageObjectNotFoundException("Source file not found: " + sourceKey);
        }
        try {
            Files.createDirectories(destPath.getParent());
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageProviderException("copy", e, sourceKey + "->" + destinationKey);
        }
    }
}
