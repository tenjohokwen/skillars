package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeworkAssignmentRepository extends JpaRepository<HomeworkAssignment, UUID> {

    List<HomeworkAssignment> findByPlayerIdOrderByAssignedAtDesc(Long playerId);

    Optional<HomeworkAssignment> findByIdAndPlayerId(UUID id, Long playerId);

    boolean existsByBookingIdAndDrillId(UUID bookingId, UUID drillId);

    /** The homework one coach assigned one player, newest first (coach-scoped completion view). */
    List<HomeworkAssignment> findByPlayerIdAndCoachIdOrderByAssignedAtDesc(Long playerId, UUID coachId);

    /** skillars-deferred-171 AC4: GDPR player erasure. Completions go with them (ON DELETE CASCADE). */
    @Modifying
    @Query("DELETE FROM HomeworkAssignment h WHERE h.playerId = :playerId")
    int deleteAllByPlayerId(@Param("playerId") Long playerId);
}
