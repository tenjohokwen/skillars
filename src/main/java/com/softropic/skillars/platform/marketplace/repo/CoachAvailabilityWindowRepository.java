package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachAvailabilityWindowRepository extends JpaRepository<CoachAvailabilityWindow, UUID> {
    // Deferred-78 AC3: findByCoachId issued no ORDER BY, so callers (AvailabilityService's own
    // windowResponses among them) returned windows in undefined order. dayOfWeek/startTime/id
    // ascending, mirroring this package's existing derived-order convention
    // (CoachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc).
    List<CoachAvailabilityWindow> findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(UUID coachId);
    Optional<CoachAvailabilityWindow> findByIdAndCoachId(UUID id, UUID coachId);
    void deleteByCoachId(UUID coachId);
}
