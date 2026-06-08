package com.softropic.skillars.platform.filestorage.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
@Getter
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "storage_access_events")
public class StorageAccessEvent extends BaseEntity {

    @Column(name = "key", nullable = false, length = 1024)
    private String key;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.accessedAt = Instant.now();
    }
}
