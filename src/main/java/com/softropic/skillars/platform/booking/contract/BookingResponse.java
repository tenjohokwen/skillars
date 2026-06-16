package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
    UUID id,
    Long playerId,
    String playerName,
    UUID coachId,
    String coachDisplayName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    String notes,
    Instant createdAt,
    String parentName,
    int effectiveCreditsRemaining,
    RescheduleRequestResponse pendingReschedule
) {}
