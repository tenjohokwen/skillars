package com.softropic.skillars.platform.admin.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum DisputeError implements ErrorCode {
    NOT_ELIGIBLE, WINDOW_EXPIRED, INVALID_CREDIT_AMOUNT;

    @Override
    public String getErrorCode() {
        return switch (this) {
            case NOT_ELIGIBLE          -> "disputes.notEligible";
            case WINDOW_EXPIRED        -> "disputes.windowExpired";
            case INVALID_CREDIT_AMOUNT -> "disputes.invalidCreditAmount";
        };
    }
}
