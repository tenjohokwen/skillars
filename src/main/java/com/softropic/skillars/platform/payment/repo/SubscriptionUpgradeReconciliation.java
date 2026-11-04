package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * skillars-deferred-169 AC6: a paid upgrade proration that could not be granted because the subscription was superseded after the
 * Stripe charge. Record-and-reconcile: an operator refunds or grants by hand and stamps {@code resolvedAt}. Deliberately FK-free
 * (see V227): the writer runs in REQUIRES_NEW while the outer transaction holds the subscription row lock.
 */
@Entity
@Table(schema = "payment", name = "subscription_upgrade_reconciliations")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionUpgradeReconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscriber_kind", nullable = false, updatable = false)
    private String subscriberKind;

    @Column(name = "subscriber_ref", nullable = false, updatable = false)
    private String subscriberRef;

    @Column(name = "stripe_subscription_id", nullable = false, updatable = false)
    private String stripeSubscriptionId;

    @Column(name = "target_tier", nullable = false, updatable = false)
    private String targetTier;

    @Column(name = "latest_invoice_id", updatable = false)
    private String latestInvoiceId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public SubscriptionUpgradeReconciliation(String subscriberKind, String subscriberRef, String stripeSubscriptionId,
                                             String targetTier, String latestInvoiceId) {
        this.subscriberKind = subscriberKind;
        this.subscriberRef = subscriberRef;
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.targetTier = targetTier;
        this.latestInvoiceId = latestInvoiceId;
    }
}
