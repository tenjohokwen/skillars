package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

public interface PlayerRadarBaselineRepository extends JpaRepository<PlayerRadarBaseline, PlayerRadarBaselineId> {

    List<PlayerRadarBaseline> findByIdPlayerId(Long playerId);

    // INSERT ... ON CONFLICT DO NOTHING — writes baseline only once, never overwrites
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
        INSERT INTO development.player_radar_baselines
            (player_id, skill_code, baseline_score, recorded_at)
        VALUES (:playerId, :skillCode, :baselineScore, NOW())
        ON CONFLICT (player_id, skill_code) DO NOTHING
        """)
    void insertBaselineIfAbsent(
        @Param("playerId") Long playerId,
        @Param("skillCode") String skillCode,
        @Param("baselineScore") BigDecimal baselineScore);

    @Modifying
    @Query("DELETE FROM PlayerRadarBaseline b WHERE b.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
