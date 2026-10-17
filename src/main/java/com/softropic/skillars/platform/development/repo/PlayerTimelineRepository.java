package com.softropic.skillars.platform.development.repo;

import com.softropic.skillars.platform.development.contract.PlayerTimelineEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerTimelineRepository extends JpaRepository<PlayerTimelineEvent, UUID> {
    // skillars-deferred-153 AC4: was a bare List<> with no Pageable — the player's entire timeline
    // (up to ~1KB of recap text per completed session since skillars-12-2) came back on every call.
    // Slice<>, not Page<>: hasNext() is all the caller needs, and Page<> would add a COUNT query this
    // feature doesn't need totalElements from. Pass an UNSORTED PageRequest at the call site — a
    // sorted one is appended AFTER this method's own ORDER BY OccurredAtDesc and would be a no-op.
    // skillars-deferred-153 code review (2026-10-17): ...IdDesc added as a deterministic tie-break.
    // occurred_at is not unique (several events for one player routinely share a timestamp — the
    // story's own ITs seed three in a single transaction, and Postgres NOW() is transaction-constant,
    // so they all land on the identical value). Under offset pagination a non-unique sort key lets a
    // row appear on two consecutive pages or be skipped entirely, which the frontend then renders as
    // a duplicate :key or a silently missing event. The sort cannot be supplied from the call site
    // instead — Spring Data appends a Pageable-carried Sort AFTER the method name's own ORDER BY, so
    // it would be a no-op; the tie-break has to live in the method name. The same diff added
    // b.playerId ASC to the roster query for this exact reason.
    Slice<PlayerTimelineEvent> findByPlayerIdOrderByOccurredAtDescIdDesc(Long playerId, Pageable pageable);

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
