package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RelatedPartyBookingRepository extends JpaRepository<RelatedPartyBooking, UUID> {

    /** Flags the booking; 1 if this call did, 0 if it was already flagged (the idempotency point). */
    @Modifying
    @Query(value = """
        INSERT INTO payment.related_party_bookings
            (booking_id, coach_id, payer_user_id, player_user_id, matched_kinds, payout_hold_until)
        VALUES (:bookingId, :coachId, :payerUserId, :playerUserId, :matchedKinds, :holdUntil)
        ON CONFLICT (booking_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("bookingId") UUID bookingId, @Param("coachId") UUID coachId,
                       @Param("payerUserId") Long payerUserId, @Param("playerUserId") Long playerUserId,
                       @Param("matchedKinds") String matchedKinds, @Param("holdUntil") Instant holdUntil);

    /** Only ever lengthens the hold. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE payment.related_party_bookings SET payout_hold_until = GREATEST(payout_hold_until, :holdUntil) "
        + "WHERE booking_id = :bookingId", nativeQuery = true)
    int extendHold(@Param("bookingId") UUID bookingId, @Param("holdUntil") Instant holdUntil);

    @Query("SELECT r.payoutHoldUntil FROM RelatedPartyBooking r WHERE r.bookingId = :bookingId")
    Optional<Instant> findPayoutHoldUntil(@Param("bookingId") UUID bookingId);

    @Query("""
        SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM RelatedPartyBooking r
        WHERE r.coachId = :coachId AND (r.payerUserId = :userId OR r.playerUserId = :userId)
        """)
    boolean existsForCoachAndUser(@Param("coachId") UUID coachId, @Param("userId") Long userId);
}
