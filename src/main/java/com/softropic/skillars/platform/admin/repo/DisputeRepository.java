package com.softropic.skillars.platform.admin.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    /**
     * fix/audit3-completion-dispute-integrity: an open dispute raised by one side of the booking — the coach
     * ({@code raisedByRole} COACH) or the family (PARENT / PLAYER). One open dispute per booking PER SIDE
     * ({@code idx_disputes_unique_open_per_booking_side}, V214): a coach who disputed first no longer blocks
     * the family's own dispute.
     */
    @Query("""
        SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END
        FROM Dispute d
        WHERE d.bookingId = :bookingId
          AND d.raisedByRole IN :roles
          AND d.status NOT IN ('RESOLVED', 'DISMISSED')
        """)
    boolean existsOpenByBookingIdAndRaisedByRoleIn(@Param("bookingId") UUID bookingId,
                                                   @Param("roles") Collection<String> roles);

    /**
     * fix/audit3-completion-dispute-integrity: another dispute on the booking is still open — the payout stays
     * dispute-frozen and the booking's DISPUTE_RAISED alert stays open until the last one is settled.
     */
    @Query("""
        SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END
        FROM Dispute d
        WHERE d.bookingId = :bookingId
          AND d.id <> :disputeId
          AND d.status NOT IN ('RESOLVED', 'DISMISSED')
        """)
    boolean existsOtherOpenForBooking(@Param("bookingId") UUID bookingId, @Param("disputeId") UUID disputeId);

    /**
     * fix/audit3-completion-dispute-integrity: every dispute one user raised on one booking, whatever its
     * status — capped by {@code disputes.maxPerBookingPerRaiser}.
     */
    long countByBookingIdAndRaisedBy(UUID bookingId, Long raisedBy);

    /**
     * fix/audit3-completion-dispute-integrity: serialises the resolution / dismissal of disputes on ONE booking.
     * Two disputes can now be open on a booking at once (one per side), and each is guarded only by its own row's
     * optimistic lock — two admins resolving both concurrently would each read the same
     * {@code remainingRefundable} and credit it twice, or each see the other still open and leave the payout
     * frozen (or both see none and release it). Transaction-scoped; taken before the payout row lock, and no
     * path holds that row lock while waiting on this one. Not {@code @Modifying}: a SELECT of a void function,
     * same shape as {@code CoachCancellationHistoryRepository.acquireCoachCancellationLock}.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(" + BOOKING_DISPUTE_LOCK_NAMESPACE + ", hashtext(:bookingId))",
        nativeQuery = true)
    void acquireBookingDisputeLock(@Param("bookingId") String bookingId);

    /** {@code pg_advisory_xact_lock(int, int)} namespace for {@link #acquireBookingDisputeLock} ("DSPL"). */
    int BOOKING_DISPUTE_LOCK_NAMESPACE = 0x4453504C;

    List<Dispute> findByRaisedBy(Long raisedBy);

    // feat/payment-forfeit-payouts-and-clawback: a booking's refund is clawed back at most once — a second
    // UPHOLD_COACH (a later dispute on the same booking) would re-deduct a pack session again.
    boolean existsByBookingIdAndResolution(UUID bookingId, String resolution);

    // skillars-deferred-165 AC1: admin worklist of open disputes, mirroring
    // AdminReviewService.getUnderReviewQueue's single-status paged query shape.
    Page<Dispute> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    // skillars-deferred-145 AC2.c: scoped to d.raisedBy = :authorId (not every open dispute between
    // the pair) so a coach being reviewed negatively cannot raise a dispute on an unrelated old
    // booking and block the author's future reviews/edits for as long as an admin leaves it open --
    // see the story's "Not Yet Resolved" item 2 for the full one-sided-veto reasoning.
    //
    // b.parentId = :authorId alone covers both parents and self-registered adult players, since
    // both booking-creation paths set parentId to the acting user's own id -- unlike
    // findQualifyingCompletedBookings' identical-looking OR, which exists there only because its own
    // post-filter loop re-derives ownership from the PlayerProfile row regardless. No such post-filter
    // exists here, so a b.playerId = :authorId disjunct would be dead weight (playerId is a
    // PlayerProfile PK, authorId is always a User id -- different ID spaces, never equal by
    // construction; code review 2026-10-06).
    @Query("""
        SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END
        FROM Dispute d, Booking b
        WHERE d.bookingId = b.id
          AND b.coachId = :coachId
          AND b.parentId = :authorId
          AND d.raisedBy = :authorId
          AND d.status NOT IN ('RESOLVED', 'DISMISSED')
        """)
    boolean existsActiveDisputeByAuthor(
        @Param("coachId") UUID coachId,
        @Param("authorId") Long authorId);

    // skillars-deferred-162 AC6.1 (D162-A1): a dispute locks the session recap while active, and after a
    // RESOLVED outcome that returned money or warned the coach. resolution is nullable, so COALESCE: a bare
    // "<> 'NO_ACTION'" would leave a RESOLVED/NULL row unlocked through three-valued logic.
    // BookingRepository.findBookingsMissingRecap/countBookingsMissingRecap repeat this predicate; keep identical.
    @Query("""
        SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END
        FROM Dispute d
        WHERE d.bookingId = :bookingId
          AND (d.status NOT IN ('RESOLVED', 'DISMISSED')
               OR (d.status = 'RESOLVED' AND COALESCE(d.resolution, '') <> 'NO_ACTION'))
        """)
    boolean existsRecapLockingDispute(@Param("bookingId") UUID bookingId);
}
