package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.exception.ErrorCode;
import com.softropic.skillars.infrastructure.security.AuthorizationException;

import java.util.Map;

public class JWTExpiredException extends AuthorizationException {
    public JWTExpiredException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public JWTExpiredException(String msg, Throwable throwable, ErrorCode errorCode) {
        super(msg, throwable, errorCode);
    }

    public JWTExpiredException(String msg, Throwable throwable, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, throwable, logContext, errorCode);
    }
}
