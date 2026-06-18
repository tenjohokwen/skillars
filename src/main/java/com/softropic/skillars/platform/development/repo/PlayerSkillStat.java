package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// IMMUTABLE: append-only — never update or delete rows once written
@Entity
@Table(schema = "development", name = "player_skill_stats")
@Getter
@Setter
@NoArgsConstructor
public class PlayerSkillStat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "player_id", nullable = false, updatable = false)
    private Long playerId;  // TSID Long — NOT UUID despite epics spec

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "skill_code", nullable = false, length = 10, updatable = false)
    private String skillCode;

    @Column(name = "slu_value", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal sluValue;

    @Column(name = "calculated_at", nullable = false, updatable = false)
    private Instant calculatedAt;
}
