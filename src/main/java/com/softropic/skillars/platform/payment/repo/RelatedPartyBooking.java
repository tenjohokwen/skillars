package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * feat/security-s06-related-party (V219): a booking whose payer is linked to the booked coach. The row's existence is
 * the flag; it is written only by {@link RelatedPartyBookingRepository#insertIfAbsent}.
 */
@Entity
@Table(schema = "payment", name = "related_party_bookings")
@Getter
@NoArgsConstructor
public class RelatedPartyBooking {

    @Id
    @Column(name = "booking_id", nullable = false, insertable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "coach_id", nullable = false, insertable = false, updatable = false)
    private UUID coachId;

    @Column(name = "payer_user_id", nullable = false, insertable = false, updatable = false)
    private Long payerUserId;

    @Column(name = "player_user_id", insertable = false, updatable = false)
    private Long playerUserId;

    /** Comma-separated {@code IdentitySignalKind} names that matched — ops only, never shown to a user. */
    @Column(name = "matched_kinds", nullable = false, length = 64, insertable = false, updatable = false)
    private String matchedKinds;

    @Column(name = "flagged_at", nullable = false, insertable = false, updatable = false)
    private Instant flaggedAt;

    @Column(name = "payout_hold_until", nullable = false, insertable = false, updatable = false)
    private Instant payoutHoldUntil;
}
