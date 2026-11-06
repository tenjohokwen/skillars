package com.softropic.skillars.platform.security.contract.exception;

import java.time.Instant;

/**
 * Refuses an action while the account's last email change can still be undone (skillars-deferred-172, R1 and
 * R3): another email change ({@code email.change.recentChange}) or an erasure ({@code gdpr.recentEmailChange}).
 * Mapped to 409; the response's {@code errorMsg.message} carries {@link #getUntil()} as an ISO-8601 instant,
 * which the SPA formats into "you can … after {date}".
 */
public class RecentEmailChangeException extends RuntimeException {

    public static final String EMAIL_CHANGE_KEY = "email.change.recentChange";
    public static final String ERASURE_KEY = "gdpr.recentEmailChange";

    private final String errorKey;
    private final Instant until;

    public RecentEmailChangeException(final String errorKey, final Instant until) {
        super("Refused while the last email change can still be undone (until " + until + ")");
        this.errorKey = errorKey;
        this.until = until;
    }

    public String getErrorKey() {
        return errorKey;
    }

    public Instant getUntil() {
        return until;
    }
}
