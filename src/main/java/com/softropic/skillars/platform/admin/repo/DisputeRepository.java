package com.softropic.skillars.platform.admin.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    @Query("""
        SELECT d FROM Dispute d
        WHERE d.bookingId = :bookingId
          AND d.status NOT IN ('RESOLVED', 'DISMISSED')
        """)
    Optional<Dispute> findOpenByBookingId(@Param("bookingId") UUID bookingId);

    List<Dispute> findByRaisedBy(Long raisedBy);

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
}
