package com.softropic.skillars.platform.admin.contract;

public enum AdminAlertType {
    MESSAGE_REPORT, CONVERSATION_REPORT, REVIEW_FLAG, STRIKE_THRESHOLD, DISPUTE_RAISED, MODERATION_UNRESOLVED,
    // skillars-deferred-128 AC2: raised when a PARENT GDPR erasure exceeds GdprErasureService's
    // per-erase lock-budget deadline — see V152__admin_alerts_gdpr_erasure_deadline_type.sql.
    GDPR_ERASURE_DEADLINE,
    // skillars-deferred-133 AC3: raised when a Stripe customer.subscription.updated event carries a
    // live/non-terminal status for a subscription that resolves to a known coach but matches no local
    // payment.coach_subscriptions row — see V153__admin_alerts_subscription_orphaned_type.sql.
    // skillars-deferred-134 AC2: also raised via the identical underlying condition detected from an
    // invoice.payment_failed event (StripeWebhookService.maybeAlertOrphanedInvoicePaymentFailed) — the
    // same alert type covers both trigger paths, not a second type.
    SUBSCRIPTION_ORPHANED,
    // C2 (V185): URGENT. A coach child-safety hold could not cancel this booking server-side — the
    // session is live (IN_PROGRESS / PAUSED) or its payment capture was in flight (PAYMENT_IN_FLIGHT,
    // in `reason`). referenceId is the booking id (reference_type BOOKING); the queue summary names the
    // parent to contact. Raised by AdminCoachEnforcementService.suspendCoachForSafetyHold.
    SAFETY_HOLD_BOOKING,
    // feat/coach-payouts-pack-and-credit: a credit cash-out whose Stripe refund outcome could not be
    // confirmed (payment.cash_out_reconciliations UNKNOWN) — see V186.
    CASH_OUT_UNRESOLVED,
    // fix/payment-payouts-disputes-webhooks (V193): payment-integrity alerts with no workflow of their own
    // (an admin marks them handled). CHARGEBACK / EXTERNAL_REFUND reference the Stripe PaymentIntent
    // (reference_type PAYMENT_INTENT); PAYOUT_HELD / PAYOUT_REVERSAL_FAILED the booking.
    CHARGEBACK,
    EXTERNAL_REFUND,
    PAYOUT_HELD,
    PAYOUT_REVERSAL_FAILED,
    // fix/payment-wallet-dispute-integrity (V208): a booking an automatic path could not finish (referenceId =
    // the booking id; reason says which: an admin-cancel refund of unknown amount, or a booking a coach
    // suspension could not cancel). Reconciled by hand, then marked handled — the payment-integrity bucket.
    BOOKING_ACTION_REQUIRED;

    /** fix/payment-payouts-disputes-webhooks: the payment-integrity types (one queue bucket, one resolve action). */
    public static final java.util.Set<AdminAlertType> PAYMENT_INTEGRITY =
        java.util.EnumSet.of(CHARGEBACK, EXTERNAL_REFUND, PAYOUT_HELD, PAYOUT_REVERSAL_FAILED, BOOKING_ACTION_REQUIRED);
}
