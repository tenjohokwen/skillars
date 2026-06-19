package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "performance_reports")
@Getter @Setter @NoArgsConstructor
public class PerformanceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private Long playerId;  // BIGINT — NOT UUID

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 500)
    private String storageKey;

    @Column(name = "next_steps", nullable = false, updatable = false, length = 500)
    private String nextSteps;

    @Column(name = "version", nullable = false)
    private int version = 1;
}
