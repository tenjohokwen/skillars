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
 *
 * <p>C6: {@code adminCancellable} (status is pre-session: REQUESTED / ACCEPTED / PAYMENT_PENDING /
 * CONFIRMED / UPCOMING) and {@code liveSession} (IN_PROGRESS / PAUSED, which cannot be cancelled
 * server-side) drive the admin cancel action — equally advisory; the cancel endpoint re-decides under the
 * booking row lock.
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
    boolean forceCompletable,
    boolean adminCancellable,
    boolean liveSession
) {}
