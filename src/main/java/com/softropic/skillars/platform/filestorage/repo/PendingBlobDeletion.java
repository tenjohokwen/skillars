package com.softropic.skillars.platform.filestorage.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * skillars-deferred-90 AC13: one pending storage-key deletion. Written inside a business
 * transaction (e.g. GDPR erasure) and drained off the request path by
 * {@code PendingBlobDeletionService} after that transaction commits.
 */
@Entity
@Table(schema = "main", name = "pending_blob_deletions")
@Getter
@Setter
@NoArgsConstructor
public class PendingBlobDeletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    public PendingBlobDeletion(String storageKey) {
        this.storageKey = storageKey;
    }
}
