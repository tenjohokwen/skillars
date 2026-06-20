package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.contract.VideoType;
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
@Table(name = "video_quota_reservations", schema = "main")
public class VideoQuotaReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type", nullable = true)
    private VideoType videoType;  // null until Story 6.2 extends QuotaProvider.reserve() to carry VideoType

    @Column(name = "reserved_bytes", nullable = false)
    private long reservedBytes;

    @Column(name = "status", nullable = false)
    private String status;   // 'ACTIVE' | 'COMMITTED' | 'RELEASED'

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
