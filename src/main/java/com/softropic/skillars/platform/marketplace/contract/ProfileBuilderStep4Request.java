package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.infrastructure.validation.IanaTimezone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

public record ProfileBuilderStep4Request(
    // @NotNull on the element, not just @Valid: Bean Validation skips the cascade on a null list
    // element, so {"windows":[null]} passed every constraint here and NPE'd inside
    // CoachProfileService.validateAvailabilityWindows -> 500. Found by the 2026-08-07 code review.
    @NotEmpty @Size(max = 14) List<@NotNull @Valid AvailabilityWindowRequest> windows
) {
    public record AvailabilityWindowRequest(
        @Min(1) @Max(7) short dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotBlank @IanaTimezone String canonicalTimezone
    ) {}
}
