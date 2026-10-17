package com.softropic.skillars.platform.booking.repo;

import com.softropic.skillars.platform.booking.contract.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionCompletionDataRepository extends JpaRepository<SessionCompletionData, UUID> {

    Optional<SessionCompletionData> findByBookingId(UUID bookingId);

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
    @Query("UPDATE SessionCompletionData s SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL WHERE s.playerId = :playerId")
    void anonymizeByPlayerId(@Param("playerId") Long playerId);
}
