package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "coach_stripe_accounts", schema = "payment")
public class CoachStripeAccount {

    @Id
    @Column(name = "coach_id")
    private UUID coachId;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "stripe_account_id", nullable = false)
    private String stripeAccountId;

    @Column(name = "onboarding_status", nullable = false)
    private String onboardingStatus = "PENDING";

    @Column(name = "charges_enabled", nullable = false)
    private boolean chargesEnabled = false;

    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled = false;

    /**
     * fix/payment-payouts-disputes-webhooks (V193): the {@code transfers} capability from the last
     * applied {@code account.updated} ({@code active} / {@code inactive} / {@code pending}). A
     * Transfer to the account needs it {@code active}; null until the first event after V193.
     */
    @Column(name = "transfers_capability", length = 20)
    private String transfersCapability;

    /**
     * fix/payment-payouts-disputes-webhooks (V193): {@code created} of the last applied
     * {@code account.updated}. Stripe does not deliver in order — an older event is ignored.
     */
    @Column(name = "last_event_created_at")
    private Instant lastEventCreatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
