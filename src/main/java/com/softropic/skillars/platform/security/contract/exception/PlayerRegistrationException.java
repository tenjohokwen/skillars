package com.softropic.skillars.platform.security.contract.exception;

public class PlayerRegistrationException extends RuntimeException {

    private final String errorCode;

    public PlayerRegistrationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
