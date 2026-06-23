package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.VideoType;
import com.softropic.skillars.platform.video.contract.Visibility;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "videos", schema = "main")
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_asset_id")
    private String providerAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_state", nullable = false)
    private OperationalState operationalState;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_state", nullable = false)
    private AccessState accessState;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "storage_bytes")
    private Long storageBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type", nullable = true)
    private VideoType videoType;

    @Column(name = "encoding_completed_at")
    private Instant encodingCompletedAt;

    @Column(name = "scanning_started_at")
    private Instant scanningStartedAt;

    @Column(name = "moderation_lock_until")
    private Instant moderationLockUntil;

    @Column(name = "moderation_retry_count", nullable = false)
    private int moderationRetryCount = 0;

    @Column(name = "lifecycle_locked_at")
    private Instant lifecycleLockedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
