package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-106 AC8.1 / AC15.1: the {@code StripeException} -> retryable-vs-HOLD matrix,
 * verified against stripe-java 28.4.0. At least five representative errors, one deliberately
 * ambiguous.
 */
class StripeTransferErrorClassifierTest {

    // stripe-java 28.4.0 signature order (verified by bytecode): (message, param, requestId, code, statusCode, cause).
    private static InvalidRequestException invalidRequest(String code, Integer status) {
        return new InvalidRequestException("msg", "param", "req_1", code, status, null);
    }

    // (message, requestId, code, statusCode, cause)
    private static ApiException apiException(Integer status) {
        return new ApiException("msg", "req_1", "api_error", status, null);
    }

    @Test
    void networkError_isRetryable() {
        StripeException e = new ApiConnectionException("connection reset");
        assertThat(StripeTransferErrorClassifier.classify(e).retryable()).isTrue();
    }

    @Test
    void rateLimit_isRetryable() {
        StripeException e = new RateLimitException("rate limited", "param", "req_1", "rate_limit", 429, null);
        StripeTransferErrorClassifier.Decision d = StripeTransferErrorClassifier.classify(e);
        assertThat(d.retryable()).isTrue();
        assertThat(d.reason()).isEqualTo("RATE_LIMIT");
    }

    @Test
    void http5xx_isRetryable() {
        assertThat(StripeTransferErrorClassifier.classify(apiException(503)).retryable()).isTrue();
    }

    @Test
    void idempotencyReplayMismatch_isRetryable() {
        StripeException e = new IdempotencyException("key reused with different params", "req_1", "idempotency_error", 400);
        assertThat(StripeTransferErrorClassifier.classify(e).retryable()).isTrue();
    }

    @Test
    void balanceInsufficient_isRetryable() {
        assertThat(StripeTransferErrorClassifier.classify(invalidRequest("balance_insufficient", 400)).retryable())
            .isTrue();
    }

    @Test
    void invalidDestinationAccount_isHold() {
        StripeTransferErrorClassifier.Decision d =
            StripeTransferErrorClassifier.classify(invalidRequest("account_invalid", 400));
        assertThat(d.retryable()).isFalse();
        assertThat(d.reason()).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    void missingTransferCapability_isHold() {
        StripeTransferErrorClassifier.Decision d = StripeTransferErrorClassifier.classify(
            invalidRequest("insufficient_capabilities_for_transfer", 400));
        assertThat(d.retryable()).isFalse();
        assertThat(d.reason()).isEqualTo("CAPABILITY_INACTIVE");
    }

    @Test
    void permissionDenied_isHold() {
        StripeException e = new PermissionException("not permitted", "req_1", "account_invalid", 403);
        StripeTransferErrorClassifier.Decision d = StripeTransferErrorClassifier.classify(e);
        assertThat(d.retryable()).isFalse();
        assertThat(d.reason()).isEqualTo("PERMISSION");
    }

    @Test
    void ambiguousUnmappedInvalidRequest_isTreatedAsRetryable() {
        // AC8.1: an unrecognised 4xx must surface via [OUTBOX_STUCK] for a human, not be parked in HOLD.
        StripeTransferErrorClassifier.Decision d =
            StripeTransferErrorClassifier.classify(invalidRequest("some_new_code_stripe_added", 400));
        assertThat(d.retryable()).isTrue();
    }
}
