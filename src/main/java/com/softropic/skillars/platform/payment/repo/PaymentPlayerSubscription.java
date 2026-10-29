package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

// player_id is Long (BIGINT TSID) — player_profiles.id is a BIGINT TSID, not a UUID
@Entity
@Table(schema = "payment", name = "player_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentPlayerSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "player_id", nullable = false, unique = true)
    private Long playerId;

    @Column(nullable = false)
    private String tier = "ATHLETE";

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(name = "billing_interval", nullable = false)
    private String billingInterval = "MONTHLY";

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "past_due_since")
    private Instant pastDueSince;

    // fix/payment-subscription-entitlements: `created` of the newest Stripe event applied to this row;
    // older out-of-order subscription/invoice events are ignored (V197).
    @Column(name = "last_stripe_event_at")
    private Instant lastStripeEventAt;

    // fix/payment-charge-idempotency (V203): a subscribe in flight — written under the row lock before the
    // Stripe subscription is created, cleared when the attempt ends. A second subscribe while it stands
    // (double-click, client retry) is refused instead of creating and charging a second subscription.
    @Column(name = "subscribe_attempt_key", length = 64)
    private String subscribeAttemptKey;

    @Column(name = "subscribe_attempt_at")
    private Instant subscribeAttemptAt;

    // fix/payment-subscription-entitlements: optimistic-lock backstop behind the persist paths'
    // re-read-under-lock (V197).
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
