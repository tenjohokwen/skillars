package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record ConflictingBookingItem(
    UUID id,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone
) {}
