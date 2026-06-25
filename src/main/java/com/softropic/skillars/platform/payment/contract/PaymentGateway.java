package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * Creates an immediate-capture Stripe Destination Charge. Returns stripePaymentIntentId.
     * referenceId is the bookingId for single bookings, purchaseId for pack purchases.
     */
    String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount);

    /**
     * Creates a single Destination Charge for a batch of credit-based bookings. Returns stripePaymentIntentId.
     */
    String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount);

    /**
     * Refunds net amount to the parent via the provided Stripe PaymentIntent ID.
     * Use the parent's last_payment_intent_id from stripe_customers.
     */
    void refund(String stripePaymentIntentId, BigDecimal netAmount);

    /**
     * Creates a Stripe Customer for a parent and returns the Stripe customer ID (cus_...).
     */
    String createStripeCustomer(Long parentId);

    /**
     * Freezes a Stripe PaymentIntent (no money movement — admin holds funds pending dispute resolution).
     */
    void freezePayment(String paymentIntentId);

    /**
     * Creates a Stripe SetupIntent for saving a card without an immediate charge. Returns clientSecret.
     */
    String createSetupIntent(String stripeCustomerId);

    /** Returns true iff the coach has a COMPLETE Stripe account with charges_enabled. */
    boolean isCoachPaymentReady(UUID coachId);

    /**
     * @deprecated Use {@link #chargeAndCapture(UUID, Long, UUID, BigDecimal)} instead.
     * Will be removed in Story 7.3.
     */
    @Deprecated
    String capturePayment(UUID referenceId, UUID coachId, BigDecimal amount);
}
