package com.softropic.skillars.platform.security.contract.exception;

/**
 * Exception thrown when a password reset key has expired.
 */
public class PasswordResetExpiredException extends UserDomainException {

    public PasswordResetExpiredException(String message) {
        super(message);
    }

    public PasswordResetExpiredException(String message, String login) {
        super(String.format("%s [login=%s]", message, login));
    }
}
