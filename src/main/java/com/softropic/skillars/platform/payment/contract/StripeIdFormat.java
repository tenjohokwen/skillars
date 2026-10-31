package com.softropic.skillars.platform.payment.contract;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * Shape of a Stripe PaymentMethod id as the client supplies it ({@code pm_} + a short alphanumeric/underscore
 * tail, e.g. {@code pm_1Nxyz...} or the {@code pm_card_visa} test tokens). The id is stored, written onto the
 * subscription sync outbox and sent back to Stripe, so anything else is refused with a 400 up front instead of
 * being persisted and failing later.
 */
public final class StripeIdFormat {

    /** Bean-validation regexp for {@code @Pattern} on request records. */
    public static final String PAYMENT_METHOD_ID_REGEX = "pm_[A-Za-z0-9_]{1,255}";

    private static final Pattern PAYMENT_METHOD_ID = Pattern.compile(PAYMENT_METHOD_ID_REGEX);

    private StripeIdFormat() {
    }

    /** Service-level guard (defence in depth behind the request validation). Null/blank are the caller's concern. */
    public static void requirePaymentMethodId(String paymentMethodId) {
        if (paymentMethodId == null || !PAYMENT_METHOD_ID.matcher(paymentMethodId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment.invalidPaymentMethodId");
        }
    }
}
