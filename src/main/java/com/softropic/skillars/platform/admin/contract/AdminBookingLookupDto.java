package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only admin view of one booking, shown before the force-complete confirmation so the admin
 * sees what they are acting on (skillars-deferred-153 / FR-ADM force-complete UI).
 *
 * <p>{@code playerId} is a {@code PlayerProfile} PK, not a user id. {@code forceCompletable} mirrors
 * the two pre-checks of {@code BookingCompletionService.forceCompleteWithPlaceholderData} — status is
 * {@code COMPLETED_PENDING_CONFIRMATION} and no completion row exists — and is advisory only: the
 * force-complete endpoint re-checks both against the live row and reports the real outcome.
 */
public record AdminBookingLookupDto(
    UUID bookingId,
    String status,
    UUID coachId,
    Long playerId,
    Long parentId,
    Instant requestedStartTime,
    Instant requestedEndTime,
    Instant updatedAt,
    boolean completionRecorded,
    boolean forceCompletable
) {}
