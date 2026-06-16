package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record ScheduleBookingItem(
    UUID bookingId,
    Long playerId,
    String playerName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    RescheduleRequestResponse pendingReschedule
) {}
