package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

// IMMUTABLE: append-only — do NOT add delete() or update-path methods
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
}
