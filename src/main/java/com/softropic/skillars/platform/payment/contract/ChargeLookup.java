package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * fix/payment-charge-idempotency: what Stripe says about one booking charge's PaymentIntent, read back by
 * {@code ChargeOutcomeReconciler} when the charge call's own response was lost.
 *
 * @param paymentIntentId the PaymentIntent id
 * @param status          Stripe's PaymentIntent status ({@code succeeded}, {@code processing},
 *                        {@code requires_payment_method}, {@code canceled}, ...)
 * @param amount          the PaymentIntent amount in major units (what the card was asked for)
 */
public record ChargeLookup(String paymentIntentId, String status, BigDecimal amount) {

    public static final String SUCCEEDED = "succeeded";
    public static final String PROCESSING = "processing";

    public boolean succeeded() {
        return SUCCEEDED.equals(status);
    }

    /** Stripe has not finished with it yet — neither captured nor failed. */
    public boolean pending() {
        return PROCESSING.equals(status) || "requires_capture".equals(status);
    }
}
