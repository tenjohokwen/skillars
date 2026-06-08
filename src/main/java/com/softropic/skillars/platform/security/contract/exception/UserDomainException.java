package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ErrorCode;

import java.util.Map;

/**
 * Base exception for user domain-related errors.
 * Thrown when user entity business rules are violated.
 */
public class UserDomainException extends ApplicationException {

    public UserDomainException(String message) {
        super(message);
    }

    public UserDomainException(String message, ErrorCode errorCode) {
        super(message, errorCode);
    }

    public UserDomainException(String message, Throwable cause) {
        super(message, cause, null);
    }

    public UserDomainException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public UserDomainException(String message, Map<String, Object> logContext, ErrorCode errorCode) {
        super(message, logContext, errorCode);
    }

    public UserDomainException(String message, Throwable cause, Map<String, Object> logContext, ErrorCode errorCode) {
        super(message, cause, logContext, errorCode);
    }
}
