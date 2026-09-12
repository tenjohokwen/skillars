package com.softropic.skillars.infrastructure.email;

/**
 * The send was rejected by a transport's own outbound rate limiter before the wire call was even
 * attempted — story ses-1.3 AC3/AC4.
 *
 * <p>Transport-neutral: this type lives here, alongside {@link EmailTransportPermanentException}/
 * {@link EmailTransportTransientException}, rather than in {@code infrastructure.ses}, so that
 * {@code platform.notification} can special-case a rate-limit rejection ({@code ComponentConfig}'s
 * {@code RetryTemplate} skips retrying it — see that class) without importing anything SES-specific.
 * Any future rate-limited transport can reuse this same marker.
 *
 * <p>Extends {@link EmailTransportTransientException} — a rate-limit rejection is transient by
 * nature (the same send will very likely succeed once the limiter's window refreshes), it is simply
 * not worth burning in-request retry attempts on, which is exactly what the {@code RetryTemplate}
 * change this type enables achieves.
 */
public class EmailTransportRateLimitedException extends EmailTransportTransientException {

    /**
     * How far {@link #isPresentIn(Throwable)} walks a cause chain. Deliberately deeper than
     * {@code MailManager.isRetryable}'s two levels: that method's bound exists because it matches a
     * <em>broad</em> category (any non-repairable error), where a deep match risks misclassifying an
     * unrelated failure buried in some other exception's chain. This matches exactly one narrowly
     * scoped type that only a transport's own rate limiter ever throws, so a deeper walk cannot
     * produce a false positive — and it needs the depth, because the rejection reaches its callers
     * through {@code MailManager.sendEmailSync}'s unconditional {@code RuntimeException} rewrap plus
     * whatever the retry/circuit-breaker layers add on top.
     */
    private static final int MAX_CAUSE_DEPTH = 5;

    public EmailTransportRateLimitedException(String message) {
        super(message);
    }

    /**
     * Whether {@code throwable} is, or wraps, a rate-limit rejection — cycle-guarded and
     * depth-bounded. Lives here rather than on either caller because two unrelated layers need the
     * same answer: {@code ComponentConfig}'s circuit-breaker {@code ignoreException} predicate, and
     * {@code MailManager}'s decision not to spend a delivery attempt on a rejection
     * (story ses-1.3, code review 2026-09-12). A {@code null} argument is {@code false}, so the
     * success path reads naturally at both call sites.
     */
    public static boolean isPresentIn(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof EmailTransportRateLimitedException) {
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
