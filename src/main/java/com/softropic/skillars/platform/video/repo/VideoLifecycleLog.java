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
@Table(name = "video_lifecycle_log", schema = "main")
public class VideoLifecycleLog {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "video_id", nullable = false, updatable = false)
    private UUID videoId;

    @Column(name = "from_state", nullable = false, updatable = false)
    private String fromState;

    @Column(name = "to_state", nullable = false, updatable = false)
    private String toState;

    @Column(name = "triggered_by", nullable = false, updatable = false)
    private String triggeredBy;

    @Column(name = "transitioned_at", nullable = false, updatable = false)
    private Instant transitionedAt;

    @PrePersist
    void onPersist() {
        if (id == null) id = UUID.randomUUID();
        if (transitionedAt == null) transitionedAt = Instant.now();
    }
}
