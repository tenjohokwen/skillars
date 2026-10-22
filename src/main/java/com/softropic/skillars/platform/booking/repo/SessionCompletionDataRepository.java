package com.softropic.skillars.platform.booking.repo;

import com.softropic.skillars.platform.booking.contract.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionCompletionDataRepository extends JpaRepository<SessionCompletionData, UUID> {

    Optional<SessionCompletionData> findByBookingId(UUID bookingId);

    // skillars-12-7 AC2: the recap edit takes the row lock BEFORE checking the player's erasure tombstone,
    // so a concurrent player erasure (anonymizeByPlayerId) cannot interleave between check and write.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SessionCompletionData s WHERE s.bookingId = :bookingId")
    Optional<SessionCompletionData> findForUpdateByBookingId(@Param("bookingId") UUID bookingId);

    // skillars-12-7 AC7: one batched lookup for the coach week schedule (no per-row query).
    List<SessionCompletionData> findByBookingIdIn(java.util.Collection<UUID> bookingIds);

    @Query("""
        SELECT s FROM SessionCompletionData s
        WHERE s.completionMode = 'QUICK'
          AND s.createdAt < :cutoff
          AND EXISTS (
              SELECT b FROM Booking b
              WHERE b.id = s.bookingId
                AND b.status = :targetStatus
          )
        """)
    List<SessionCompletionData> findPendingQuickCompletes(@Param("cutoff") Instant cutoff,
                                                          @Param("targetStatus") String targetStatus);

    default List<SessionCompletionData> findPendingQuickCompletes(Instant cutoff) {
        // Booking.status is a plain String column; bind the enum's name(), not the enum.
        return findPendingQuickCompletes(cutoff, BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());
    }

    // skillars-deferred-153 AC2: GdprErasureService never touched booking.session_completion_data
    // before this — what_we_did/whats_next (skillars-12-2) and the pre-existing voice_note_text
    // otherwise survive a player's own erasure indefinitely. Nulls the three coach-authored free-text
    // columns in place; does not delete the row itself (ratings/attendance are not personally-
    // identifying free text — out of this method's scope).
    @Modifying
    @Query("UPDATE SessionCompletionData s SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL, s.whatWentWell = NULL WHERE s.playerId = :playerId")
    void anonymizeByPlayerId(@Param("playerId") Long playerId);

    // skillars-deferred-154 AC2: anonymizeByPlayerId only runs from the PLAYER-side erasure branch —
    // a coach's own erasure never touched this table, so their authored voice_note_text/what_we_did/
    // whats_next survived their own erasure indefinitely. Identical shape, keyed on coachId instead.
    @Modifying
    @Query("UPDATE SessionCompletionData s SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL, s.whatWentWell = NULL WHERE s.coachId = :coachId")
    void anonymizeByCoachId(@Param("coachId") UUID coachId);
}
