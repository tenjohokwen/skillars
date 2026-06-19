package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SluTargetRepository
        extends JpaRepository<PlayerSluTarget, PlayerSluTarget.PlayerSluTargetId> {

    List<PlayerSluTarget> findByIdCoachIdAndIdPlayerId(UUID coachId, Long playerId);

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO development.player_slu_targets
                (coach_id, player_id, skill_code, weekly_target_slu, updated_at)
            VALUES (:coachId, :playerId, :skillCode, :weeklyTargetSlu, :updatedAt)
            ON CONFLICT (coach_id, player_id, skill_code)
            DO UPDATE SET weekly_target_slu = EXCLUDED.weekly_target_slu,
                          updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("coachId") UUID coachId,
                @Param("playerId") Long playerId,
                @Param("skillCode") String skillCode,
                @Param("weeklyTargetSlu") BigDecimal weeklyTargetSlu,
                @Param("updatedAt") Instant updatedAt);

    @Query("SELECT t.id.skillCode, MAX(t.weeklyTargetSlu) FROM PlayerSluTarget t " +
           "WHERE t.id.playerId = :playerId GROUP BY t.id.skillCode")
    List<Object[]> findMaxTargetPerSkill(@Param("playerId") Long playerId);

    @Query("SELECT DISTINCT t.id.playerId FROM PlayerSluTarget t")
    List<Long> findDistinctPlayerIds();
}
