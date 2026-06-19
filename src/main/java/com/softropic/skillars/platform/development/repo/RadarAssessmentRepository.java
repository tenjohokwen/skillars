package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RadarAssessmentRepository extends JpaRepository<RadarAssessmentEntry, UUID> {

    @Query(nativeQuery = true, value = """
        SELECT rae.* FROM development.radar_assessment_entries rae
        JOIN main.player_profiles pp ON pp.id = rae.player_id
        WHERE rae.player_id = :playerId
          AND pp.parent_id = :parentId
          AND rae.coach_id = :coachId
        ORDER BY rae.assessment_date DESC
        """)
    List<RadarAssessmentEntry> findByPlayerIdAndCoachIdOrderByAssessmentDateDesc(
        @Param("playerId") Long playerId,
        @Param("parentId") Long parentId,
        @Param("coachId") UUID coachId);

    // Returns: [skill_code(String), assessment_type(String), avg_score(Double), count(Long)]
    // NOTE: assessment_type is returned as String because native SQL returns PostgreSQL enum as text.
    // Use AssessmentType.valueOf((String) row[1]) — do NOT cast to AssessmentType directly.
    @Query(nativeQuery = true, value = """
        SELECT rae.skill_code,
               rae.assessment_type::text,
               AVG(CAST(rae.score AS DOUBLE PRECISION)),
               COUNT(rae.id)
        FROM development.radar_assessment_entries rae
        JOIN main.player_profiles pp ON pp.id = rae.player_id
        WHERE rae.player_id = :playerId
          AND pp.parent_id = :parentId
          AND rae.skill_code IN (:skillCodes)
        GROUP BY rae.skill_code, rae.assessment_type
        """)
    List<Object[]> findAggregatesByPlayerAndSkills(
        @Param("playerId") Long playerId,
        @Param("parentId") Long parentId,
        @Param("skillCodes") Set<String> skillCodes);

    @Query(nativeQuery = true, value = """
        SELECT rae.skill_code, COUNT(DISTINCT rae.coach_id)
        FROM development.radar_assessment_entries rae
        JOIN main.player_profiles pp ON pp.id = rae.player_id
        WHERE rae.player_id = :playerId
          AND pp.parent_id = :parentId
          AND rae.coach_id != :excludeCoachId
        GROUP BY rae.skill_code
        """)
    List<Object[]> countDistinctOtherCoachesBySkill(
        @Param("playerId") Long playerId,
        @Param("parentId") Long parentId,
        @Param("excludeCoachId") UUID excludeCoachId);
}
