package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record CreateWindowRequest(
    @NotNull @Min(1) @Max(7) Integer dayOfWeek,
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime
) {
    @AssertTrue(message = "startTime must be before endTime")
    public boolean isValidTimeRange() {
        return startTime == null || endTime == null || startTime.isBefore(endTime);
    }
}
