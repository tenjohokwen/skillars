package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.UploadSessionStatus;
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
@Table(name = "upload_sessions", schema = "main")
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "provider_upload_id")
    private String providerUploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UploadSessionStatus status;

    @Column(name = "reserved_bytes", nullable = false)
    private long reservedBytes;

    @Column(name = "reservation_handle")
    private String reservationHandle;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
