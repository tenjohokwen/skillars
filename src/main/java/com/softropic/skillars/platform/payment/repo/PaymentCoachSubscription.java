package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

// No stripe_customer_id column here (dropped by V96, field removed by skillars-deferred-24): the
// coach's Stripe customer is looked up via StripeCustomer, whose @Id is StripeCustomer.parentId
// (payment.stripe_customers.parent_id, a bare BIGINT with no FK). Despite that field's name, the value
// passed for a coach is the coach's own user id (main.user.id) — NOT this entity's coachId
// (marketplace.coach_profiles.id). See SubscriptionService.subscribeCoach(UUID coachId, Long
// coachUserId, String tier), which keeps the two deliberately separate and whose lookup is
// stripeCustomerRepository.findById(coachUserId). A dedicated column here would be redundant with
// that lookup. It was only ever hand-seeded by test SQL, never written by production code, and
// payment.player_subscriptions — the equivalent table for the pattern this one mirrors — never had it.
@Entity
@Table(schema = "payment", name = "coach_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@Slf4j
public class PaymentCoachSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "coach_id", nullable = false, unique = true)
    private UUID coachId;

    @Column(nullable = false)
    private String tier = "SCOUT";

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "past_due_since")
    private Instant pastDueSince;

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
