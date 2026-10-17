package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NeglectedSkillFlagRepository extends JpaRepository<NeglectedSkillFlag, UUID> {

    List<NeglectedSkillFlag> findByPlayerIdAndResolvedAtIsNull(Long playerId);

    // skillars-deferred-152 AC4: additive sibling for call sites that only need the count
    // (CoachRosterService, SessionPrepService) -- the entity-returning method above stays for its
    // other callers (SluDashboardService, SluTargetService, NeglectedSkillProcessor,
    // DrillSuggestionService).
    int countByPlayerIdAndResolvedAtIsNull(Long playerId);

    Optional<NeglectedSkillFlag> findByPlayerIdAndSkillCodeAndResolvedAtIsNull(Long playerId, String skillCode);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM NeglectedSkillFlag n WHERE n.playerId = :playerId")
    int deleteAllByPlayerId(@org.springframework.data.repository.query.Param("playerId") Long playerId);
}
