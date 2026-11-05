package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CoachRadarPreferenceRepository
        extends JpaRepository<CoachRadarPreference, CoachRadarPreferenceId> {

    Optional<CoachRadarPreference> findByIdCoachIdAndIdPlayerId(UUID coachId, Long playerId);

    /** skillars-deferred-171 AC4: GDPR player erasure — every coach's preferences for the player. */
    @Modifying
    @Query("DELETE FROM CoachRadarPreference p WHERE p.id.playerId = :playerId")
    int deleteAllByIdPlayerId(@Param("playerId") Long playerId);
}
