package com.softropic.skillars.platform.admin.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum GdprError implements ErrorCode {
    REQUEST_ALREADY_PENDING, ACTIVE_BOOKINGS_EXIST, EXPORT_EXPIRED, EXPORT_IN_PROGRESS;

    @Override
    public String getErrorCode() {
        return switch (this) {
            case REQUEST_ALREADY_PENDING -> "gdpr.requestAlreadyPending";
            case ACTIVE_BOOKINGS_EXIST   -> "gdpr.activeBookingsExist";
            case EXPORT_EXPIRED          -> "gdpr.exportExpired";
            case EXPORT_IN_PROGRESS      -> "gdpr.exportInProgress";
        };
    }
}
