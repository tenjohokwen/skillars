package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateBlockRequest(
    @NotNull Instant startDatetime,
    @NotNull Instant endDatetime,
    String reason
) {
    @AssertTrue(message = "startDatetime must be before endDatetime")
    public boolean isValidDatetimeRange() {
        return startDatetime == null || endDatetime == null || startDatetime.isBefore(endDatetime);
    }
}
