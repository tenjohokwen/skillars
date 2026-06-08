package com.softropic.skillars.platform.security.contract.exception;

/**
 * Exception thrown when attempting to activate an already activated user.
 */
public class UserAlreadyActivatedException extends UserDomainException {

    public UserAlreadyActivatedException(String message) {
        super(message);
    }

    public UserAlreadyActivatedException(String message, String login) {
        super(String.format("%s [login=%s]", message, login));
    }
}
