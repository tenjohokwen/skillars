package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.exception.ErrorCode;
import com.softropic.skillars.infrastructure.security.AuthorizationException;

import java.util.Map;

public class MissingAuthenticationException extends AuthorizationException {

    public MissingAuthenticationException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public MissingAuthenticationException(String msg, Throwable cause, ErrorCode errorCode) {
        super(msg, cause, errorCode);
    }

    public MissingAuthenticationException(String msg, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, cause, logContext, errorCode);
    }

    public MissingAuthenticationException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, null, logContext, errorCode);
    }

}
