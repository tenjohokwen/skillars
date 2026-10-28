package com.softropic.skillars.platform.admin.contract;

public record AdminQueueSummaryDto(
    long messageReports,
    long conversationReports,
    long reviewFlags,
    long strikeAlerts,
    long disputes,
    long moderationHolds,
    // skillars-deferred-128 story review (Decision 2): without this, one open GDPR_ERASURE_DEADLINE
    // alert made `total` strictly exceed the sum of this record's own reported buckets — see
    // AdminQueueService.getSummary's own comment for the full rationale.
    long gdprErasureDeadlines,
    // skillars-deferred-133 AC3: same defect skillars-deferred-128 fixed above, for
    // SUBSCRIPTION_ORPHANED — without its own bucket, `total` would silently exceed the sum of the
    // reported buckets the moment one is raised.
    long subscriptionOrphaned,
    // C2: urgent bookings a coach child-safety hold could not cancel (live session / payment in flight).
    long safetyHoldBookings,
    // feat/coach-payouts-pack-and-credit: CASH_OUT_UNRESOLVED — same reasoning, its own bucket.
    long cashOutUnresolved,
    // fix/payment-payouts-disputes-webhooks: CHARGEBACK + EXTERNAL_REFUND + PAYOUT_HELD + PAYOUT_REVERSAL_FAILED.
    long paymentIntegrity,
    long total) {}
