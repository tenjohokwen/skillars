package com.softropic.skillars.platform.video.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_deletion_log", schema = "main")
public class VideoDeletionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id")
    private UUID videoId;

    @Column(name = "deleted_at", nullable = false)
    private Instant deletedAt;

    @Column(name = "triggered_by", nullable = false, length = 32)
    private String triggeredBy;

    @Column(name = "bunny_video_id", length = 255)
    private String bunnyVideoId;

    @PrePersist
    void onCreate() {
        if (deletedAt == null) deletedAt = Instant.now();
    }
}
