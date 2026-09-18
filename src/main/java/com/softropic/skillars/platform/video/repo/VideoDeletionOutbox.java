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
@Table(name = "video_deletion_outbox", schema = "main")
public class VideoDeletionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(name = "bunny_video_id", length = 255)
    private String bunnyVideoId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "next_retry_at", nullable = false)
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "triggered_by", nullable = false, length = 32)
    private String triggeredBy;

    /**
     * skillars-deferred-123 AC3: stamped by {@code claimPendingBatch} with the tick's own claim
     * instant, and cleared back to {@code null} on every transition out of {@code CLAIMED}
     * (completion or either failure outcome). Lets {@code resetStaleClaimed} key staleness on how
     * long a row has actually been claimed rather than {@code nextRetryAt}'s eligibility time, and
     * lets {@code findClaimedBatch} scope its fetch to this run's own claim.
     */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (nextRetryAt == null) nextRetryAt = now;
    }
}
