package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachAvailabilityWindowRepository extends JpaRepository<CoachAvailabilityWindow, UUID> {
    List<CoachAvailabilityWindow> findByCoachId(UUID coachId);
    Optional<CoachAvailabilityWindow> findByIdAndCoachId(UUID id, UUID coachId);
    void deleteByCoachId(UUID coachId);
}
