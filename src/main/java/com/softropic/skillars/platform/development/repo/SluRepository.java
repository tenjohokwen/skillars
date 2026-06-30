package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// IMMUTABLE: append-only — do NOT add delete() or update-path methods EXCEPT for GDPR Article 17 erasure
public interface SluRepository extends JpaRepository<PlayerSkillStat, UUID> {

    List<PlayerSkillStat> findByPlayerIdOrderByCalculatedAtDesc(Long playerId);

    List<PlayerSkillStat> findByPlayerIdAndSkillCode(Long playerId, String skillCode);

    List<PlayerSkillStat> findBySessionId(UUID sessionId);

    @Query(nativeQuery = true, value = """
        SELECT skill_code, SUM(slu_value) as total_slu
        FROM development.player_skill_stats
        WHERE player_id = :playerId
        GROUP BY skill_code
        """)
    List<Object[]> sumSluBySkill(@Param("playerId") Long playerId);

    @Query(nativeQuery = true, value = """
        SELECT COUNT(DISTINCT session_id)
        FROM development.player_skill_stats
        WHERE player_id = :playerId AND session_id IS NOT NULL
        """)
    Long countDistinctSessions(@Param("playerId") Long playerId);

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(slu_value), 0)
        FROM development.player_skill_stats
        WHERE player_id = :playerId
        """)
    BigDecimal sumTotalSluByPlayerId(@Param("playerId") Long playerId);

    @Query(nativeQuery = true, value = """
        SELECT MAX(calculated_at)
        FROM development.player_skill_stats
        WHERE player_id = :playerId AND coach_id = :coachId
        """)
    Instant findLastSessionDate(@Param("playerId") Long playerId, @Param("coachId") UUID coachId);

    @Query(nativeQuery = true, value = """
        SELECT coach_id, skill_code, SUM(slu_value) AS total_slu
        FROM development.player_skill_stats
        WHERE player_id = :playerId
          AND calculated_at >= :since
          AND coach_id IS NOT NULL
        GROUP BY coach_id, skill_code
        ORDER BY skill_code, total_slu DESC
        """)
    List<Object[]> findCoachContributionsByPlayerId(
        @Param("playerId") Long playerId,
        @Param("since") Instant since);

    @Modifying
    @Query("DELETE FROM PlayerSkillStat s WHERE s.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
