package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateBookingRequest(
    @NotNull UUID coachId,
    @NotNull Long playerId,
    @NotNull @Future Instant requestedStartTime,
    @NotNull Instant requestedEndTime,
    @NotBlank String canonicalTimezone,
    @Size(max = 500) String notes
) {}
