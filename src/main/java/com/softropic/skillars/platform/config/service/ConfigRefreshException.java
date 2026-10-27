package com.softropic.skillars.platform.config.service;

/**
 * skillars-deferred-163 AC1.1: a bounded must-be-fresh {@code ConfigService} load that timed out
 * waiting for an in-flight refresh, or that is rethrowing another thread's recently-recorded
 * refresh failure instead of attempting its own redundant DB call.
 *
 * <p>Deliberately an {@link IllegalStateException} subtype (not a
 * {@link org.springframework.dao.PessimisticLockingFailureException}) so it lands on
 * {@code ApiAdvice}'s existing 500 handler for genuine server-state errors, rather than the 409
 * handler reserved for client-recoverable conflicts.
 */
public class ConfigRefreshException extends IllegalStateException {

    public ConfigRefreshException(String message, Throwable cause) {
        super(message, cause);
    }
}
