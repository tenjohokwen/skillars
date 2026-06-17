package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PausePackRequest(
    @NotNull Instant pauseStartDate,
    @Min(1) @Max(90) int pauseDurationDays,
    List<UUID> confirmedCancellationIds
) {}
