package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateRescheduleRequest(
    @NotNull Instant proposedStartTime,
    @NotNull Instant proposedEndTime
) {}
