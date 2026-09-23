package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlayerTimelineRepository extends JpaRepository<PlayerTimelineEvent, UUID> {
    List<PlayerTimelineEvent> findByPlayerIdOrderByOccurredAtDesc(Long playerId);

    // skillars-deferred-129 AC1 Task 4: was a Spring Data derived delete (one DELETE statement per
    // row, growing with the player's own timeline length) — the only one of GdprErasureService's
    // eleven deletePlayerDevelopmentData call sites not already a real bulk statement, unlike its ten
    // siblings (e.g. SluRepository.deleteAllByPlayerId). Converted to a single bulk statement so the
    // statement count deletePlayerDevelopmentData's new lock_timeout bound counts against is fixed.
    @Modifying
    @Query("DELETE FROM PlayerTimelineEvent e WHERE e.playerId = :playerId")
    int deleteByPlayerId(@Param("playerId") Long playerId);   // GDPR erasure only
}
