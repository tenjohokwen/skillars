package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PausePackRequest(
    @NotNull Instant pauseStartDate,
    // The real maximum is runtime config (pack.pause.maxDays), checked by PackSessionService; this is only
    // that key's own upper bound (ConfigBounds), so raising the config above 90 takes effect.
    @Min(1) @Max(3650) int pauseDurationDays,
    List<UUID> confirmedCancellationIds
) {}
