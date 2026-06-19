package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "development", name = "neglected_skill_flags")
@Getter
@Setter
@NoArgsConstructor
public class NeglectedSkillFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "player_id", nullable = false)
    private Long playerId; // BIGINT TSID

    @Column(name = "skill_code", nullable = false, length = 10)
    private String skillCode;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt; // NULL = still open
}
