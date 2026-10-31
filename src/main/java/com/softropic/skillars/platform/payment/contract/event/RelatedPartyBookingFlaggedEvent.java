package com.softropic.skillars.platform.payment.contract.event;

import java.util.UUID;

/**
 * feat/security-s06-related-party: published once, synchronously and in the flagging transaction, when a booking is
 * first flagged as related-party (payer linked to the booked coach). The reviews module hides that payer's review of
 * the coach and recomputes the coach's rating. No signal detail travels with it.
 *
 * @param playerUserId the user account of the booked player, when the player has one (a review may be authored by it)
 */
public record RelatedPartyBookingFlaggedEvent(UUID bookingId, UUID coachId, Long payerUserId, Long playerUserId) {
}
