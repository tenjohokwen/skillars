package com.softropic.skillars.platform.payment.contract.exception;

/**
 * skillars-deferred-106 AC8: a {@code Transfer.create} / {@code Transfer.createReversal} failure,
 * pre-classified by {@code StripeTransferErrorClassifier} into the decision the outbox handler must
 * take:
 *
 * <ul>
 *   <li>{@link #isRetryable()} {@code == true} — the handler re-throws so the outbox backoff
 *       re-drives the row ({@code [OUTBOX_STUCK]} after 10 attempts). Network blips, 429s, HTTP 5xx,
 *       and anything unmapped (fail safe: a human sees it) land here.</li>
 *   <li>{@link #isRetryable()} {@code == false} — the handler takes the {@code HOLD} /
 *       {@code REVERSAL_FAILED} path and does <strong>not</strong> throw. The destination connected
 *       account is missing / disconnected / restricted / lacks the {@code transfers} capability.
 *       {@link #getReason()} is the metric tag ({@code INVALID_DESTINATION}, {@code ACCOUNT_RESTRICTED},
 *       {@code PERMISSION}, …).</li>
 * </ul>
 */
public class CoachPayoutTransferException extends PaymentGatewayException {

    private final boolean retryable;
    private final String reason;

    public CoachPayoutTransferException(String errorCode, boolean retryable, String reason, Throwable cause) {
        super(errorCode, cause);
        this.retryable = retryable;
        this.reason = reason;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** Short upper-snake tag for the {@code coach.payout.held} / {@code coach.payout.reversal_failed} metric. */
    public String getReason() {
        return reason;
    }
}
