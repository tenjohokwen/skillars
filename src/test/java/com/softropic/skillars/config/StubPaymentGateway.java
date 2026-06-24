package com.softropic.skillars.config;

import com.softropic.skillars.platform.payment.contract.PaymentGateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * No-op PaymentGateway for integration tests.
 * Replaces StripePaymentGateway so ITs don't call Stripe and coaches don't need
 * a payment.coach_stripe_accounts record to pass isCoachPaymentReady().
 * capturePayment() returns a stub charge ID because real capture is deferred to Story 7.2.
 */
class StubPaymentGateway implements PaymentGateway {

    @Override
    public String capturePayment(UUID referenceId, UUID coachId, BigDecimal amount) {
        return "ch_stub_" + referenceId;
    }

    @Override
    public boolean isCoachPaymentReady(UUID coachId) {
        return true;
    }
}
