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

    // skillars-deferred-155 AC1: every SESSION_COMPLETED recap this coach authored for this player,
    // full history, for the report-generation "Session Notes" section. Deliberately NOT a derived
    // method on the entity's own coachId column — that column is attendance-gated (NULL for a
    // completed-but-unattended session, see TimelineEventListener.onBookingCompleted), while
    // metadata->>'coachId' is written unconditionally. A column-based filter would silently exclude
    // every unattended session's real recap text; WHERE coach_id = ? never matches a NULL row. The
    // jsonb_exists(...) guard also does AC3's "no empty section" work at the database level. Uses the
    // (skillars-deferred-155 code review) Two additions to the ORDER BY / LIMIT tail. `, id DESC` is the
    // tie-break this file's own sibling query at the top already carries: occurred_at is NOT unique
    // ("Postgres NOW() is transaction-constant, so they all land on the identical value"), so without it
    // same-timestamp Session Notes order nondeterministically and two regenerations of the same report
    // can differ. `LIMIT :limit` bounds what was a full-history fetch — the exact unbounded-List shape
    // skillars-deferred-153 AC4 removed from the sibling above, for the same reason (one row per
    // completed session, up to 2x500 chars each, hydrated as entities and rendered into one in-memory
    // PDF byte[]). The caller passes ReportGenerationService.REPORT_SESSION_NOTES_LIMIT.
    // function form, not the bare `?` containment operator (metadata ? 'whatWeDid') — a literal `?`
    // inside a Spring Data native @Query is ambiguous with JDBC's own positional-parameter syntax and
    // has no precedent anywhere else in this codebase; jsonb_exists is the equivalent function call
    // with no parser risk.
    @Query(value = "SELECT * FROM development.player_timeline_events "
        + "WHERE player_id = :playerId AND metadata->>'coachId' = CAST(:coachId AS text) "
        + "AND event_type = 'SESSION_COMPLETED' "
        + "AND (jsonb_exists(metadata, 'whatWeDid') OR jsonb_exists(metadata, 'whatsNext')) "
        + "ORDER BY occurred_at DESC, id DESC "
        + "LIMIT :limit", nativeQuery = true)
    List<PlayerTimelineEvent> findSessionNotesForReport(
        @Param("playerId") Long playerId, @Param("coachId") UUID coachId, @Param("limit") int limit);

    // skillars-12-6 AC12: the most recent attended SESSION_COMPLETED event for this coach and player
    // whose whatsNext is non-blank — the Session Builder's "carry last time's what's next forward"
    // suggestion. Deliberately the OPPOSITE filter from findSessionNotesForReport above: that one
    // filters metadata->>'coachId' precisely because the coach_id COLUMN is attendance-gated and it
    // wants unattended recaps too; this one filters the coach_id COLUMN, consistent with the prep
    // card's existing "unattended sessions are excluded" rule (the unattended-recap inconsistency is
    // G13 / story 12.7, not touched here). reference_id <> :bookingId so a booking that already has
    // its own SESSION_COMPLETED event cannot suggest its own whatsNext. jsonb_exists, not the bare `?`
    // operator (see findSessionNotesForReport's comment above).
    @Query(value = "SELECT * FROM development.player_timeline_events "
        + "WHERE player_id = :playerId AND coach_id = :coachId "
        + "AND event_type = 'SESSION_COMPLETED' "
        + "AND (reference_id IS NULL OR reference_id <> :bookingId) "
        + "AND jsonb_exists(metadata, 'whatsNext') AND btrim(metadata->>'whatsNext') <> '' "
        + "ORDER BY occurred_at DESC, id DESC "
        + "LIMIT 1", nativeQuery = true)
    Optional<PlayerTimelineEvent> findLatestWhatsNextSuggestion(
        @Param("playerId") Long playerId, @Param("coachId") UUID coachId, @Param("bookingId") UUID bookingId);

    // skillars-deferred-155 AC9: closes the gap skillars-deferred-154's own code review recorded as
    // "its own story, not a review patch" — a coach's GDPR erasure already scrubs
    // booking.session_completion_data (anonymizeByCoachId) but never touched this second,
    // independent copy of the same recap text. Same metadata->>'coachId' filter as
    // findSessionNotesForReport above, for the same attendance-gating reason — a column-based
    // filter would silently skip any non-attended session's real recap text. JPQL cannot express
    // JSONB key-removal (`-`), hence nativeQuery = true. coachId left in place: it is a structural
    // reference key, not narrative PII (TimelineQueryService.redactRecapIfNotAuthoringCoach already
    // establishes it surviving redaction as deliberate, asserted-correct behavior).
    // (skillars-deferred-155 code review) The jsonb_exists guard is a provable no-op narrowing: a row
    // carrying neither key has nothing for `- 'whatWeDid' - 'whatsNext'` to strip, so skipping it cannot
    // change the outcome, and it stops the statement row-locking and rewriting every BOOKING_CONFIRMED /
    // PERFORMANCE_REPORT row this coach ever touched. Deliberately NOT also filtered on
    // `event_type = 'SESSION_COMPLETED'` even though only onBookingCompleted writes these keys today
    // (verified: TimelineEventListener.java:56-57 is the sole writer): on a GDPR scrub an over-scan is
    // cheap and an under-scrub is a compliance failure, and the guard above already excludes the rows
    // an event_type filter would have. The read-side sibling above does filter on event_type, because a
    // read that misses a historical oddity is harmless and only SESSION_COMPLETED belongs in a report.
    // No index serves metadata->>'coachId' (V161 indexes the coach_id *column*), so this remains a
    // sequential scan — which is exactly why the caller wraps it in the lock_timeout bound.
    @Modifying
    @Query(value = "UPDATE development.player_timeline_events "
        + "SET metadata = metadata - 'whatWeDid' - 'whatsNext' "
        + "WHERE metadata->>'coachId' = CAST(:coachId AS text) "
        + "AND (jsonb_exists(metadata, 'whatWeDid') OR jsonb_exists(metadata, 'whatsNext'))",
        nativeQuery = true)
    void anonymizeMetadataByCoachId(@Param("coachId") UUID coachId);
}
