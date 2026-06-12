package com.softropic.skillars.platform.security.contract.exception;

public class ParentRegistrationException extends RuntimeException {

    private final String errorCode;

    public ParentRegistrationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
