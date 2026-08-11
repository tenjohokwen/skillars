package com.softropic.skillars.platform.booking.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum BookingError implements ErrorCode {
    COACH_UNAVAILABLE,
    SLOT_UNAVAILABLE,
    INVALID_SESSION_DURATION;

    @Override
    public String getErrorCode() {
        return switch (this) {
            case COACH_UNAVAILABLE        -> "booking.coachUnavailable";
            case SLOT_UNAVAILABLE         -> "booking.slotUnavailable";
            case INVALID_SESSION_DURATION -> "booking.invalidSessionDuration";
        };
    }
}
