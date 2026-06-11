package com.softropic.skillars.platform.security.contract.exception;

public class CoachRegistrationException extends RuntimeException {

    private final String errorCode;

    public CoachRegistrationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
