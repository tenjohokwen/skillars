package com.softropic.skillars.platform.development.repo;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(schema = "development", name = "player_radar_composites")
@Getter
@Setter
@NoArgsConstructor
public class PlayerRadarComposite {

    @EmbeddedId
    private PlayerRadarCompositeId id;

    @Column(name = "composite_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal compositeScore;

    @Column(name = "entry_count", nullable = false)
    private Integer entryCount;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;
}
