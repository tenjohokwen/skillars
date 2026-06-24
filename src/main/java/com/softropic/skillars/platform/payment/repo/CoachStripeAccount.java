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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
