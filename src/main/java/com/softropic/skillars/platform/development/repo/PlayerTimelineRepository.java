package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerTimelineRepository extends JpaRepository<PlayerTimelineEvent, UUID> {
    List<PlayerTimelineEvent> findByPlayerIdOrderByOccurredAtDesc(Long playerId);

    // skillars-12-3 Task 1.4: serves both the coach-access clock (CoachPlayerLastSessionResolver,
    // Task 2.1) and the Session Prep endpoint's recap fields (SessionPrepService, Task 3.4) — a
    // single query for "the most recent attended SESSION_COMPLETED this coach wrote for this player".
    Optional<PlayerTimelineEvent> findTopByPlayerIdAndCoachIdAndEventTypeOrderByOccurredAtDesc(
        Long playerId, UUID coachId, PlayerTimelineEventType eventType);

    // skillars-deferred-129 AC1 Task 4: was a Spring Data derived delete (one DELETE statement per
    // row, growing with the player's own timeline length) — the only one of GdprErasureService's
    // eleven deletePlayerDevelopmentData call sites not already a real bulk statement, unlike its ten
    // siblings (e.g. SluRepository.deleteAllByPlayerId). Converted to a single bulk statement so the
    // statement count deletePlayerDevelopmentData's new lock_timeout bound counts against is fixed.
    @Modifying
    @Query("DELETE FROM PlayerTimelineEvent e WHERE e.playerId = :playerId")
    int deleteByPlayerId(@Param("playerId") Long playerId);   // GDPR erasure only
}
