package com.softropic.skillars.platform.security.contract.exception;

public class OtpVerificationException extends RuntimeException {

    private final String errorCode;

    public OtpVerificationException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
