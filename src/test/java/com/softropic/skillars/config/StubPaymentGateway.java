package com.softropic.skillars.config;

import com.softropic.skillars.platform.payment.contract.PaymentGateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * No-op PaymentGateway for integration tests that do not need real Stripe.
 * Tests that require Stripe verification should use BasePaymentIT + WireMock instead.
 */
class StubPaymentGateway implements PaymentGateway {

    @Override
    public String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount) {
        return "pi_stub_" + referenceId;
    }

    @Override
    public String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount) {
        return "pi_batch_stub_" + batchId;
    }

    @Override
    public void refund(String stripePaymentIntentId, BigDecimal netAmount) {
        // no-op for tests
    }

    @Override
    public String createStripeCustomer(Long parentId) {
        return "cus_stub_" + parentId;
    }

    @Override
    public void freezePayment(String paymentIntentId) {
        // no-op for tests
    }

    @Override
    public String createSetupIntent(String stripeCustomerId) {
        return "seti_stub_secret_" + stripeCustomerId;
    }

    @Override
    public boolean isCoachPaymentReady(UUID coachId) {
        return true;
    }

    @Override
    @Deprecated
    public String capturePayment(UUID referenceId, UUID coachId, BigDecimal amount) {
        return "ch_stub_" + referenceId;
    }
}
