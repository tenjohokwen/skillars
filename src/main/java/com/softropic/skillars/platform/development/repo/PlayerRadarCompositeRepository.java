package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

public interface PlayerRadarCompositeRepository extends JpaRepository<PlayerRadarComposite, PlayerRadarCompositeId> {

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
        INSERT INTO development.player_radar_composites
            (player_id, skill_code, composite_score, entry_count, distinct_coach_count, last_updated_at)
        VALUES (:playerId, :skillCode, :compositeScore, :entryCount, :distinctCoachCount, NOW())
        ON CONFLICT (player_id, skill_code)
        DO UPDATE SET composite_score = EXCLUDED.composite_score,
                      entry_count = EXCLUDED.entry_count,
                      distinct_coach_count = EXCLUDED.distinct_coach_count,
                      last_updated_at = EXCLUDED.last_updated_at
        """)
    void upsertComposite(
        @Param("playerId") Long playerId,
        @Param("skillCode") String skillCode,
        @Param("compositeScore") BigDecimal compositeScore,
        @Param("entryCount") int entryCount,
        @Param("distinctCoachCount") int distinctCoachCount);

    List<PlayerRadarComposite> findByIdPlayerId(Long playerId);

    @Modifying
    @Query("DELETE FROM PlayerRadarComposite c WHERE c.id.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
