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
    SUBSCRIPTION_ORPHANED
}
