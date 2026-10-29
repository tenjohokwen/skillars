package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

/**
 * fix/payment-payouts-disputes-webhooks: a payment-integrity condition an operator must look at, raised
 * as an admin alert (one OPEN alert per {@code referenceId} and {@link Kind}; a later event for the same
 * pair refreshes its reason). Published inside the writer's transaction; the admin listener inserts the
 * alert after it commits.
 */
public class PaymentIntegrityAlertEvent extends ApplicationEvent {

    /** Mirrors the admin alert type of the same name. */
    public enum Kind {
        /** A Stripe card dispute was opened or closed against a platform charge. */
        CHARGEBACK,
        /** A refund the app did not issue (e.g. from the Stripe Dashboard). */
        EXTERNAL_REFUND,
        /** A coach payout went to HOLD — the transfer cannot be made without an operator. */
        PAYOUT_HELD,
        /** A post-payout reversal failed non-retryably. */
        PAYOUT_REVERSAL_FAILED
    }

    /** What {@link #getReferenceId()} names. */
    public enum Reference {
        BOOKING,
        PAYMENT_INTENT,
        /** A coach_profiles id: a condition of the coach's Connect account, not of one booking. */
        COACH
    }

    private final Kind kind;
    private final String referenceId;
    private final Reference reference;
    private final String reason;

    public PaymentIntegrityAlertEvent(Object source, Kind kind, String referenceId, Reference reference,
                                      String reason) {
        super(source);
        this.kind = kind;
        this.referenceId = referenceId;
        this.reference = reference;
        this.reason = reason;
    }

    public Kind getKind() { return kind; }
    public String getReferenceId() { return referenceId; }
    public Reference getReference() { return reference; }
    public String getReason() { return reason; }
}
