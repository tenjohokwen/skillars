package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record AvailabilityBlockResponse(
    UUID id,
    Instant startDatetime,
    Instant endDatetime,
    String reason
) {}
