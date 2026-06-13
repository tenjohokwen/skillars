package com.softropic.skillars.platform.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachAvailabilityBlockRepository extends JpaRepository<CoachAvailabilityBlock, UUID> {
    List<CoachAvailabilityBlock> findByCoachIdAndEndDatetimeAfterAndStartDatetimeBefore(
        UUID coachId, Instant weekStart, Instant weekEnd);
    Optional<CoachAvailabilityBlock> findByIdAndCoachId(UUID id, UUID coachId);
}
