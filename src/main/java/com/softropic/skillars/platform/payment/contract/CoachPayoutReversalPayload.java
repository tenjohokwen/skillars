package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * skillars-deferred-106 AC10.2: the {@code COACH_PAYOUT_REVERSAL} outbox payload — a dispute upheld
 * after the coach payout has {@code RELEASED}. {@code amount} is the disputed amount to reverse
 * (which may be less than the full net for a partial resolution). The handler loads the
 * {@code coach_payouts} row by {@code bookingId} for the {@code stripe_transfer_id}.
 */
public record CoachPayoutReversalPayload(UUID bookingId, BigDecimal amount) {
}
