package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeworkAssignmentRepository extends JpaRepository<HomeworkAssignment, UUID> {

    List<HomeworkAssignment> findByPlayerIdOrderByAssignedAtDesc(Long playerId);

    Optional<HomeworkAssignment> findByIdAndPlayerId(UUID id, Long playerId);

    boolean existsByBookingIdAndDrillId(UUID bookingId, UUID drillId);
}
