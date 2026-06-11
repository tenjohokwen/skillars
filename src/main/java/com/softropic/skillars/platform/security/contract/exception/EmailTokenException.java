package com.softropic.skillars.platform.security.contract.exception;

public class EmailTokenException extends RuntimeException {

    private final String errorCode;
    private final boolean canResend;

    public EmailTokenException(String errorCode, boolean canResend) {
        super(errorCode);
        this.errorCode = errorCode;
        this.canResend = canResend;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isCanResend() {
        return canResend;
    }
}
