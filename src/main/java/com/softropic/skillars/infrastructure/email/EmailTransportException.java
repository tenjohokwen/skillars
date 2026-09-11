package com.softropic.skillars.infrastructure.email;

/**
 * Transport-neutral supertype for every email-send failure. No code outside
 * {@code infrastructure.ses} (and, from Phase 2, {@code infrastructure.email.smtp}) should throw or
 * catch an SDK-specific or SMTP-specific exception type for a send failure — callers deal only with
 * this taxonomy.
 */
public abstract class EmailTransportException extends RuntimeException {

    protected EmailTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    protected EmailTransportException(String message) {
        super(message);
    }
}
