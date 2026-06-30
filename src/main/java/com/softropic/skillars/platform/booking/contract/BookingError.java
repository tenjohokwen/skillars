package com.softropic.skillars.platform.booking.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum BookingError implements ErrorCode {
    COACH_UNAVAILABLE;

    @Override
    public String getErrorCode() {
        return switch (this) {
            case COACH_UNAVAILABLE -> "booking.coachUnavailable";
        };
    }
}
