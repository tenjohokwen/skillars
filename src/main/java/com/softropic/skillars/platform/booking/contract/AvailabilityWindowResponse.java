package com.softropic.skillars.platform.booking.contract;

import java.time.LocalTime;
import java.util.UUID;

public record AvailabilityWindowResponse(
    UUID id,
    int dayOfWeek,
    LocalTime startTime,
    LocalTime endTime,
    String canonicalTimezone,
    boolean hasConflict
) {}
