package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CoachReliabilityStrikeRepository extends JpaRepository<CoachReliabilityStrike, UUID> {

    @Query("SELECT s.coachId, COUNT(s) FROM CoachReliabilityStrike s " +
           "WHERE s.coachId IN :coachIds AND s.createdAt > :since " +
           "GROUP BY s.coachId")
    List<Object[]> countByCoachIdInAndCreatedAtAfter(
        @Param("coachIds") List<UUID> coachIds,
        @Param("since") OffsetDateTime since
    );

    long countByCoachIdAndCreatedAtAfter(UUID coachId, OffsetDateTime since);

    List<CoachReliabilityStrike> findByCoachIdOrderByCreatedAtDesc(UUID coachId);
}
