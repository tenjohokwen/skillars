package com.softropic.skillars.platform.security.contract.exception;

public class ShadowAccountException extends RuntimeException {

    private final String errorCode;

    public ShadowAccountException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
