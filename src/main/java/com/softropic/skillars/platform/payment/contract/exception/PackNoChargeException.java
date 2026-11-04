package com.softropic.skillars.platform.payment.contract.exception;

/**
 * skillars-deferred-169 AC1b: a pack purchase ended in a PROVEN no-charge outcome (a card decline, or a refusal before any
 * PaymentIntent existed). Thrown only by {@code SessionPackPaymentService}; the gateway's own decline code
 * ({@code payment.lifecycleFailure}) is pinned by {@code StripePaymentGatewayTest} and shared with bookings, so the
 * distinction lives in this subclass with its own code. It stays a {@link PaymentGatewayException}, so it maps to the same 422.
 */
public class PackNoChargeException extends PaymentGatewayException {

    public static final String CODE = "payment.noChargeMade";

    public PackNoChargeException(Throwable cause) {
        // A refused card keeps its own code (payment.cardDeclined): the UI offers to update the card, and that code is itself
        // proof that nothing was charged. Everything else is the generic no-charge code.
        super(cause instanceof PaymentGatewayException pge && CARD_DECLINED.equals(pge.getErrorCode()) ? CARD_DECLINED : CODE, cause);
    }
}
