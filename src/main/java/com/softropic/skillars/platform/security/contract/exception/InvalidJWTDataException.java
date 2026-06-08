package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.exception.ErrorCode;
import com.softropic.skillars.infrastructure.security.AuthorizationException;

import java.util.Map;

public class InvalidJWTDataException extends AuthorizationException {
    public InvalidJWTDataException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public InvalidJWTDataException(String msg, Throwable throwable, ErrorCode errorCode) {
        super(msg, throwable, errorCode);
    }

    public InvalidJWTDataException(String msg, Throwable throwable, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, throwable, logContext, errorCode);
    }
}
