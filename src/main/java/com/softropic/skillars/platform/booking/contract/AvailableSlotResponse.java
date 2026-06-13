package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;

public record AvailableSlotResponse(
    Instant startDatetime,
    Instant endDatetime
) {}
