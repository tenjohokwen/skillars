package com.softropic.skillars.infrastructure.blobstore.service;

import com.softropic.skillars.infrastructure.blobstore.contract.StorageObject;
import com.softropic.skillars.infrastructure.blobstore.contract.StorageObjectMetadata;

import java.io.InputStream;
import java.util.List;

public interface StorageService {
    void put(String key, InputStream data, long contentLength, String contentType);
    StorageObject get(String key);
    void delete(String key);

    /**
     * skillars-deferred-164 AC11. Bulk delete, added for completeness — no current caller needs it
     * (the only multi-object-deletion path, {@code BlobDeletionOutboxHandler}, processes exactly
     * one storage key per generic-outbox row by that outbox's own one-row-at-a-time contract).
     *
     * <p><strong>Contract</strong>, mirroring {@link #delete(String)}'s own idempotent-on-missing-key
     * behavior: a {@code null} or empty {@code keys} is a no-op. A key that does not exist is not an
     * error (deleting it is still a success). A genuine per-key failure (e.g. a permission error) IS
     * surfaced — implementations throw rather than silently drop it.
     *
     * <p>Best-effort, not all-or-nothing: every key is attempted even after one fails, and all
     * failures are reported together as one {@code StorageProviderException} naming every failed
     * key once the whole list has been processed. Keys that were deleted stay deleted.
     */
    void deleteAll(List<String> keys);

    boolean exists(String key);
    StorageObjectMetadata stat(String key);
    void copy(String sourceKey, String destinationKey);
}
