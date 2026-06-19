package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoachRadarPreferenceRepository
        extends JpaRepository<CoachRadarPreference, CoachRadarPreferenceId> {

    Optional<CoachRadarPreference> findByIdCoachIdAndIdPlayerId(UUID coachId, Long playerId);
}
