package com.softropic.skillars.platform.video.repo;

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
// Not @Audited — scan records are insert-only and immutable (@PrePersist sets scannedAt, updatable=false protects it).
// Envers provides no additional value over the base table for append-only audit data.
@Table(name = "video_moderation_scans", schema = "main")
public class VideoModerationScan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "layer", nullable = false, length = 20)
    private String layer;  // ARACHNID | VIDEOINTEL | MINOR_GATE

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome; // PASSED | FLAGGED | FAILED | SKIPPED

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "scanned_at", nullable = false, updatable = false)
    private Instant scannedAt;

    @PrePersist
    void onCreate() {
        this.scannedAt = Instant.now();
    }
}
