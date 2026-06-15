package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record ParentScheduleItem(
    UUID bookingId,
    UUID coachId,
    String coachDisplayName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    int effectiveCreditsRemaining
) {}
