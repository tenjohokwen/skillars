package com.softropic.skillars.platform.marketplace.contract;

import java.time.LocalTime;

public record CoachAvailabilityWindowDto(
    short dayOfWeek,
    LocalTime startTime,
    LocalTime endTime,
    String canonicalTimezone
) {}
