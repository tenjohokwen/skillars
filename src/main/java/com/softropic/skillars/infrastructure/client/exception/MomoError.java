package com.softropic.skillars.infrastructure.client.exception;


import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum MomoError implements ErrorCode {
    PAYEE_NOT_FOUND("MOBILE_NUMBER_NOT_FOUND"),
    RESOURCE_NOT_FOUND("REFERENCE_NOT_FOUND"),
    CLIENT_NOT_REACHABLE("CLIENT_NOT_REACHABLE");

    private final String mappedCode;

    MomoError(String mappedCode) { this.mappedCode = mappedCode;}

    @Override
    public String getErrorCode() {
        return this.mappedCode;
    }
}
