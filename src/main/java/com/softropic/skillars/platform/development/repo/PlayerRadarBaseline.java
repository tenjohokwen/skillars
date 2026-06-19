package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(schema = "development", name = "player_radar_baselines")
@Getter
@Setter
@NoArgsConstructor
public class PlayerRadarBaseline {

    @EmbeddedId
    private PlayerRadarBaselineId id;

    @Column(name = "baseline_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal baselineScore;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
