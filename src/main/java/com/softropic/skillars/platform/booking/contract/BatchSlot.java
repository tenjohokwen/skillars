package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record BatchSlot(
    @NotNull Instant requestedStartTime,
    @NotNull Instant requestedEndTime
) {}
