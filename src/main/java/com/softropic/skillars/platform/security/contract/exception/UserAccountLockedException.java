package com.softropic.skillars.platform.security.contract.exception;

/**
 * Exception thrown when attempting operations on a locked user account.
 */
public class UserAccountLockedException extends UserDomainException {

    public UserAccountLockedException(String message) {
        super(message);
    }

    public UserAccountLockedException(String message, String login) {
        super(String.format("%s [login=%s]", message, login));
    }
}
