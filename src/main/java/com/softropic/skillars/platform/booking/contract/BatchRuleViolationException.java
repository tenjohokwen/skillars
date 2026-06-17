package com.softropic.skillars.platform.booking.contract;

public class BatchRuleViolationException extends RuntimeException {

    private final String errorCode;

    public BatchRuleViolationException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
