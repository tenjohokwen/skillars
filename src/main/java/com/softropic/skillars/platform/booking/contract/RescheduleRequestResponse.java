package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record RescheduleRequestResponse(
    UUID id,
    String proposedBy,
    Instant proposedStartTime,
    Instant proposedEndTime,
    String status
) {}
