package com.softropic.skillars.infrastructure.email;

/**
 * The send failed for a reason a retry may fix (throttling, a transient 5xx, a network timeout, or
 * an account-level block that is expected to clear — see {@code SesErrorClassifier}'s javadoc for
 * why the latter is transient rather than permanent).
 */
public class EmailTransportTransientException extends EmailTransportException {

    public EmailTransportTransientException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmailTransportTransientException(String message) {
        super(message);
    }
}
