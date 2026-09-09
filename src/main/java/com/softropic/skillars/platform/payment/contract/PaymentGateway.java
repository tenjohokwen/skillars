package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * skillars-deferred-106 (B-1 separate charges &amp; transfers): creates an immediate-capture
     * Stripe charge <strong>to the platform account</strong> — no {@code transfer_data.destination},
     * no {@code application_fee_amount}. The full session price is held on the platform balance; the
     * coach's net is transferred later on {@code BookingCompletedEvent} ({@link #transferToCoach}).
     * A {@code transfer_group} = {@code referenceId} links the later transfer in Stripe. Returns
     * stripePaymentIntentId. referenceId is the bookingId for single bookings, purchaseId for pack
     * purchases.
     */
    String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount);

    /**
     * Creates a single platform-account charge for a batch of credit-based bookings (see
     * {@link #chargeAndCapture} — same B-1 model). Returns stripePaymentIntentId.
     */
    String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount);

    /**
     * skillars-deferred-106 AC5.1: transfers {@code netAmount} from the platform balance to the
     * coach's connected account on session completion. {@code transferGroupId} is the booking (or
     * batch) id, used both as the Stripe {@code transfer_group} and to derive the deterministic
     * idempotency key {@code transfer-{transferGroupId}}. Returns the {@code stripeTransferId}.
     *
     * @throws com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException
     *     pre-classified retryable-vs-HOLD (AC8)
     */
    String transferToCoach(UUID transferGroupId, UUID coachId, BigDecimal netAmount, String currency);

    /**
     * skillars-deferred-106 AC5.1: reverses (part of) a released coach transfer — the post-payout
     * dispute path. Key {@code reversal-{stripeTransferId}}.
     *
     * @throws com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException
     *     pre-classified retryable-vs-REVERSAL_FAILED (AC8.3)
     */
    void reverseTransfer(String stripeTransferId, BigDecimal amount);

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
