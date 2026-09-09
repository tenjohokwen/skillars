package com.softropic.skillars.platform.payment.contract;

import java.util.UUID;

/**
 * skillars-deferred-106 AC4.2: the {@code COACH_PAYOUT_TRANSFER} outbox payload. Carries only the
 * {@code bookingId} — {@code CoachPayoutTransferHandler} re-reads the {@code payment.coach_payouts}
 * row (written at enqueue time, AC3.2) for the resolved amounts, so the payload never has to be
 * kept in sync with the ledger.
 */
public record CoachPayoutTransferPayload(UUID bookingId) {
}
