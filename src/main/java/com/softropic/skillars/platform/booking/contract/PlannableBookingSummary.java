package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * feat/coach-workflow-gaps A19: one of a coach's upcoming bookings, as a session-plan target (the template
 * "Use for this session" picker). {@code playerName} is the player profile's display name — the same value the
 * coach already sees on their schedule ({@code ScheduleBookingItem.playerName}).
 */
public record PlannableBookingSummary(
    UUID bookingId,
    Long playerId,
    String playerName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String canonicalTimezone
) {}
