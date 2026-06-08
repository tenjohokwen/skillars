package com.softropic.skillars.platform.security.contract.exception;

/**
 * Exception thrown when attempting operations that require an activated user account.
 */
public class UserNotActivatedException extends UserDomainException {

    public UserNotActivatedException(String message) {
        super(message);
    }

    public UserNotActivatedException(String message, String login) {
        super(String.format("%s [login=%s]", message, login));
    }
}
