package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin dismissed a dispute without a resolution. Published by {@code DisputeService.dismissDispute}
 * inside its transaction; {@code recipient} is the raiser (a minor player's parent instead of the child),
 * null when no address is reachable. {@code reason} is the dismissal note the raiser can already read
 * through {@code GET /api/disputes/{id}}.
 */
public record DisputeDismissedEvent(UUID disputeId, UUID bookingId, Long raisedBy, String reason,
                                    String coachDisplayName, Instant sessionStart,
                                    AdminNotificationRecipient recipient) {
}
