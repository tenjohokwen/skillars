package com.softropic.skillars.infrastructure.security;



import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ErrorCode;

import java.util.Map;

public class AuthorizationException extends ApplicationException {

    public AuthorizationException(String msg, ErrorCode errorCode) {
        super(msg, errorCode);
    }

    public AuthorizationException(String msg, Throwable cause, ErrorCode errorCode) {
        super(msg, cause, errorCode);
    }

    public AuthorizationException(String msg, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, cause, logContext, errorCode);
    }

    public AuthorizationException(String msg, Map<String, Object> logContext, ErrorCode errorCode) {
        super(msg, null, logContext, errorCode);
    }

}
