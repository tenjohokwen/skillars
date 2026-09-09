package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

import java.util.Locale;

/**
 * skillars-deferred-106 AC8.1: the explicit {@code StripeException} -> decision mapping for
 * {@code Transfer.create} / {@code Transfer.createReversal}, verified against the pinned Stripe SDK
 * (<strong>stripe-java 28.4.0</strong>, see {@code pom.xml}). Kept as a standalone, side-effect-free
 * helper so the matrix can be unit-tested independently of the gateway.
 *
 * <p><strong>Exception hierarchy that makes the ordering below load-bearing</strong> (28.4.0):
 * <pre>
 *   RateLimitException  extends InvalidRequestException extends StripeException
 *   PermissionException extends AuthenticationException  extends StripeException
 * </pre>
 * so {@code RateLimitException} must be matched <em>before</em> {@code InvalidRequestException}, or a
 * 429 would be misread as a non-retryable bad-request.
 *
 * <h2>The matrix</h2>
 * <table>
 *   <tr><th>Stripe failure</th><th>Decision</th><th>Why</th></tr>
 *   <tr><td>{@link ApiConnectionException}</td><td>retryable</td><td>network — the request may not have reached Stripe</td></tr>
 *   <tr><td>{@link RateLimitException} / HTTP 429</td><td>retryable</td><td>back off and re-drive</td></tr>
 *   <tr><td>HTTP 5xx ({@link ApiException} or any status &ge; 500)</td><td>retryable</td><td>Stripe-side, transient</td></tr>
 *   <tr><td>{@link IdempotencyException}</td><td>retryable</td><td>mismatched replay — surface via {@code [OUTBOX_STUCK]}</td></tr>
 *   <tr><td>{@code balance_insufficient}</td><td>retryable</td><td>platform balance tops up as charges settle</td></tr>
 *   <tr><td>{@link InvalidRequestException} — bad destination
 *       ({@code account_invalid}, {@code account_closed}, {@code insufficient_capabilities_for_transfer},
 *       …)</td><td>HOLD</td><td>the coach's connected account cannot receive — operator reconciles</td></tr>
 *   <tr><td>{@link PermissionException}</td><td>HOLD</td><td>platform not permitted to transfer to this account</td></tr>
 *   <tr><td>anything else / unmapped</td><td><strong>retryable</strong></td><td>fail safe: a human sees it via {@code [OUTBOX_STUCK]} rather than it being parked silently in HOLD</td></tr>
 * </table>
 */
final class StripeTransferErrorClassifier {

    /** {@code retryable} — throw so the outbox backoff re-drives. {@code reason} — the metric tag. */
    record Decision(boolean retryable, String reason) {
        static Decision retryable(String reason) {
            return new Decision(true, reason);
        }

        static Decision hold(String reason) {
            return new Decision(false, reason);
        }
    }

    private StripeTransferErrorClassifier() {
    }

    static Decision classify(StripeException e) {
        if (e instanceof ApiConnectionException) {
            return Decision.retryable("NETWORK");
        }
        if (e instanceof RateLimitException || is(e.getStatusCode(), 429)) {
            return Decision.retryable("RATE_LIMIT");
        }
        if (e instanceof IdempotencyException) {
            return Decision.retryable("IDEMPOTENCY_REPLAY");
        }
        Integer status = e.getStatusCode();
        if (status != null && status >= 500) {
            return Decision.retryable("STRIPE_5XX");
        }
        if (e instanceof ApiException) {
            return Decision.retryable("STRIPE_API");
        }

        String code = e.getCode() == null ? "" : e.getCode().toLowerCase(Locale.ROOT);
        if ("balance_insufficient".equals(code)) {
            // Platform balance cannot cover the transfer yet; it settles as charges clear.
            return Decision.retryable("BALANCE_INSUFFICIENT");
        }

        if (e instanceof PermissionException) {
            return Decision.hold("PERMISSION");
        }
        if (e instanceof InvalidRequestException) {
            // Only a KNOWN bad-destination code parks the payout in HOLD. A 4xx we do not recognise
            // is treated as ambiguous -> retryable, so a human sees it via [OUTBOX_STUCK] rather
            // than it sitting silently in HOLD (AC8.1).
            return switch (code) {
                case "account_invalid", "account_closed", "account_country_invalid_address",
                     "transfers_not_allowed" -> Decision.hold("INVALID_DESTINATION");
                case "insufficient_capabilities_for_transfer" -> Decision.hold("CAPABILITY_INACTIVE");
                default -> Decision.retryable("UNMAPPED_INVALID_REQUEST");
            };
        }

        // Unmapped: retryable on purpose (AC8.1) — better a human sees it via [OUTBOX_STUCK] than it
        // sitting silently in HOLD.
        return Decision.retryable("UNMAPPED");
    }

    private static boolean is(Integer actual, int expected) {
        return actual != null && actual == expected;
    }
}
