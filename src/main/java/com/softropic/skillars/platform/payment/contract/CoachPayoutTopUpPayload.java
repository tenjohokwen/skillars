package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * fix/payment-payout-recovery: the {@code COACH_PAYOUT_TOPUP} outbox payload — an {@code UPHOLD_COACH}
 * dispute resolution on a booking whose payout had already released short of the coach's full net (an
 * earlier refund reversed it, or it released after a pending cut). {@code amount} is the shortfall at
 * enqueue; the handler re-derives what is still owed from the locked row and pays at most that.
 * {@code topUpId} is the Stripe {@code transfer_group} and idempotency key of the top-up transfer
 * ({@code transfer-{topUpId}}), so a re-driven message replays it instead of paying twice.
 */
public record CoachPayoutTopUpPayload(UUID bookingId, BigDecimal amount, UUID topUpId) {
}
