package com.softropic.skillars.platform.payment.contract.exception;

/**
 * fix/payment-charge-idempotency: a Stripe charge whose outcome is NOT known — the request may have been
 * executed (and the card charged) even though no response says so: a connection failure or read timeout
 * after the SDK's own retries, a 5xx, a 409 / {@code IdempotencyException} (another request under the same
 * key is still executing or already ran), or a PaymentIntent still {@code processing}.
 *
 * <p>Never treat this as a decline. The caller keeps its CAPTURE_PENDING reservation (which carries the
 * idempotency key) and leaves the outcome to {@code ChargeOutcomeReconciler}, which finds the
 * PaymentIntent by that key and settles or declines. A subtype of {@link PaymentGatewayException} so a
 * caller with no reconciliation of its own (the synchronous session-pack purchase) still fails the request
 * as before.
 */
public class ChargeOutcomeUnknownException extends PaymentGatewayException {

    public static final String ERROR_CODE = "payment.chargeOutcomeUnknown";

    private final String idempotencyKey;

    public ChargeOutcomeUnknownException(String idempotencyKey, Throwable cause) {
        super(ERROR_CODE, cause);
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
