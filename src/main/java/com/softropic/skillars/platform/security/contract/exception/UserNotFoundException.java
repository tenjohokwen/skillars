package com.softropic.skillars.platform.security.contract.exception;

import com.softropic.skillars.infrastructure.security.SecurityError;

import java.util.Map;

/**
 * Exception thrown when a requested user cannot be found.
 * Used instead of returning null to make error handling explicit.
 */
public class UserNotFoundException extends UserDomainException {

    public UserNotFoundException(String login) {
        super(String.format("User not found with login: %s", login), SecurityError.USER_NOT_FOUND);
    }

    public UserNotFoundException(Long id) {
        super(String.format("User not found with id: %d", id), SecurityError.USER_NOT_FOUND);
    }

    public UserNotFoundException(String message, Map<String, Object> logContext) {
        super(message, logContext, SecurityError.USER_NOT_FOUND);
    }
}
