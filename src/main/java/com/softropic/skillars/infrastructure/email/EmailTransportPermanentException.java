package com.softropic.skillars.infrastructure.email;

/**
 * The send failed for a reason no retry can fix — a malformed recipient address, or the message
 * itself being rejected by the transport.
 */
public class EmailTransportPermanentException extends EmailTransportException {

    /**
     * skillars-deferred-110 AC4: same depth/cycle-guard rationale as {@code
     * EmailTransportRateLimitedException}'s own constant — {@link #isPresentIn(Throwable)} needs to
     * see through {@code ComponentConfig}'s {@code RetryTemplate}/circuit-breaker wrapping layers,
     * which a shallow two-level walk (the kind {@code MailManager.isRetryable} uses for its broad
     * "any non-repairable error" category) is not deep enough for.
     */
    private static final int MAX_CAUSE_DEPTH = 5;

    public EmailTransportPermanentException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmailTransportPermanentException(String message) {
        super(message);
    }

    /**
     * Whether {@code throwable} is, or wraps, a permanent send failure — cycle-guarded and
     * depth-bounded, mirroring {@link EmailTransportRateLimitedException#isPresentIn(Throwable)}.
     * skillars-deferred-110 AC4b: used by {@code ComponentConfig.defaultCustomizer()}'s
     * {@code ignoreException} predicate so a permanent failure (e.g. a bad recipient address) does
     * not count toward the circuit breaker's failure-rate window — it is a data problem, not a
     * signal the transport itself is unhealthy.
     */
    public static boolean isPresentIn(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof EmailTransportPermanentException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
