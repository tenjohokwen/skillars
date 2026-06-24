package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {
    /** Creates a Stripe Destination Charge and records booking_payments. Returns stripeChargeId. */
    String capturePayment(UUID referenceId, UUID coachId, BigDecimal amount);

    /** Returns true iff the coach has a COMPLETE Stripe account with charges_enabled. */
    boolean isCoachPaymentReady(UUID coachId);
}
