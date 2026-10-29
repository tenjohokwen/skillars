package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * feat/payment-forfeit-payouts-and-clawback: a dispute was resolved {@code UPHOLD_COACH}; this tells the
 * party that did NOT raise it (the raiser gets {@link DisputeResolvedEvent}) — the payer whose refund was
 * reversed, or the coach who is now paid. {@code recipient} is null when no address is reachable.
 */
public record DisputeCoachUpheldEvent(UUID disputeId, UUID bookingId, UpholdCoachOutcome.Audience audience,
                                      UpholdCoachOutcome outcome, String coachDisplayName, Instant sessionStart,
                                      AdminNotificationRecipient recipient) {
}
