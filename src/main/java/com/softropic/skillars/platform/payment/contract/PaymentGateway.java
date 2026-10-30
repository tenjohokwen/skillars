package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
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
     * fix/payment-session-pack-integrity: {@link #chargeAndCapture(UUID, Long, UUID, BigDecimal)} under a
     * caller-chosen Stripe idempotency key that names ONE purchase attempt — a retry of that attempt replays
     * its PaymentIntent, a different attempt always charges. Used by the session-pack purchase, whose
     * {@code referenceId} (the tier id) is not unique per charge.
     */
    String chargeAndCapture(UUID referenceId, Long parentId, UUID coachId, BigDecimal amount, String idempotencyKey);

    /**
     * Creates a single platform-account charge for a batch of credit-based bookings (see
     * {@link #chargeAndCapture} — same B-1 model). Returns stripePaymentIntentId.
     *
     * <p>fix/payment-charge-idempotency: {@code idempotencyKey} names ONE settlement of the batch (batch id +
     * the reserved booking ids), persisted on the reserved rows before this call. The batch id alone is not
     * unique per charge: two concurrent accepts of one batch settle disjoint booking sets and used to share a
     * key, so the second replayed the first's PaymentIntent and its bookings were confirmed uncharged.
     *
     * @throws com.softropic.skillars.platform.payment.contract.exception.ChargeOutcomeUnknownException when
     *     the charge may have been executed but no response says so — never a decline
     */
    String chargeAndCaptureForBatch(UUID batchId, Long parentId, UUID coachId, BigDecimal amount,
                                    String idempotencyKey);

    /**
     * fix/payment-charge-idempotency: the PaymentIntent a booking charge was sent under {@code idempotencyKey}
     * for (matched on its {@code chargeKey} metadata among the payer's PaymentIntents created at or after
     * {@code createdAfter}); empty when there is none — i.e. the charge never reached Stripe.
     *
     * @throws com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException when Stripe
     *     cannot be asked (the caller retries later — absence is not proven)
     */
    Optional<ChargeLookup> findChargeByIdempotencyKey(Long parentId, String idempotencyKey, Instant createdAfter);

    /** fix/payment-charge-idempotency: re-reads a known PaymentIntent's status and amount. */
    ChargeLookup retrieveCharge(String paymentIntentId);

    /**
     * fix/payment-charge-idempotency: the smallest card charge Stripe accepts in the platform currency (e.g.
     * EUR 0.50). A wallet/card split leaving less than this on the card is re-balanced
     * ({@code CreditCardSplit}) instead of being declined as {@code amount_too_small}.
     */
    BigDecimal minimumCardCharge();

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
     * dispute path. fix/payment-payouts-disputes-webhooks: {@code idempotencyKey} names one reversal
     * ({@code reversal-{stripeTransferId}-{reversalId}}), so a transfer can be reversed in parts.
     *
     * @throws com.softropic.skillars.platform.payment.contract.exception.CoachPayoutTransferException
     *     pre-classified retryable-vs-REVERSAL_FAILED (AC8.3)
     */
    void reverseTransfer(String stripeTransferId, BigDecimal amount, String idempotencyKey);

    /**
     * Refunds {@code amount} against the given Stripe PaymentIntent under the caller-supplied Stripe
     * idempotency key. The key identifies ONE logical refund: a retry of that same refund must reuse
     * it (Stripe then replays the original instead of refunding twice), and a different refund
     * against the same PaymentIntent must use a different key — reusing a key there makes Stripe
     * replay the first refund and the second one silently never happens. Pack-purchase compensation
     * uses {@code refund-{paymentIntentId}} (one compensating refund per purchase); a credit cash-out
     * uses {@code cashout-{cashOutId}} (a payer can cash out several times against the same
     * PaymentIntent).
     */
    void refund(String stripePaymentIntentId, BigDecimal amount, String idempotencyKey);

    /**
     * The amount still refundable on a PaymentIntent: its latest charge's captured amount minus
     * what has already been refunded on it. Stripe rejects a refund above this, so a caller that
     * computes its own refund amount (credit cash-out) checks against it first and fails with a
     * clear error instead of a generic refund failure.
     *
     * <p>fix/audit3-wallet-cashout-integrity: throws {@code PaymentGatewayException} with
     * {@link #REFUND_TARGET_INVALID} when the PaymentIntent can never be read (unknown / invalid / not permitted),
     * and {@code payment.refundLookupFailed} when the lookup may succeed on a retry.
     */
    BigDecimal getRefundableAmount(String stripePaymentIntentId);

    /** Error code of a refundable-amount lookup that will never succeed (see {@link #getRefundableAmount}). */
    String REFUND_TARGET_INVALID = "payment.refundTargetInvalid";

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
