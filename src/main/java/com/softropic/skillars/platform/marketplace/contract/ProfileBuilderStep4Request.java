package com.softropic.skillars.platform.marketplace.contract;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

public record ProfileBuilderStep4Request(
    @NotEmpty @Size(max = 14) List<AvailabilityWindowRequest> windows
) {
    public record AvailabilityWindowRequest(
        @Min(1) @Max(7) short dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotBlank String canonicalTimezone
    ) {}
}
