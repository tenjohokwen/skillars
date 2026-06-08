package com.softropic.skillars.platform.filestorage.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "outbox_replication_jobs")
public class OutboxReplicationJob extends BaseEntity {

    public enum ReplicationJobStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    public enum ReplicationJobType {
        REPLICATE, DELETE
    }

    @ManyToOne
    @JoinColumn(name = "storage_object_id", nullable = false)
    private FileStorageObject storageObject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReplicationJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 20)
    private ReplicationJobType jobType;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = ReplicationJobStatus.PENDING;
        }
    }
}
