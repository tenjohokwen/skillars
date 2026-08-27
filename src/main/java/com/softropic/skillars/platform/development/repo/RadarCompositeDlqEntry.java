package com.softropic.skillars.platform.development.repo;

import io.hypersistence.utils.hibernate.type.array.ListArrayType;
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
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "radar_composite_dlq", schema = "development")
public class RadarCompositeDlqEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "parent_id")
    private Long parentId;

    @Type(ListArrayType.class)
    @Column(name = "skill_codes", columnDefinition = "varchar[]", nullable = false)
    private List<String> skillCodes = new ArrayList<>();

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

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (nextRetryAt == null) nextRetryAt = now;
    }
}
