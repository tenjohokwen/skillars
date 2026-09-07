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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * skillars-deferred-99 AC1: a compensating refund that {@code SessionPackPaymentService.purchasePack}
 * could not issue. One row per Stripe PaymentIntent ({@code payment_intent_id} is UNIQUE) — a
 * re-attempt of the same purchase bumps {@link #attempts} and rewrites {@link #error} rather than
 * inserting a duplicate. An operator (or a future reconciliation job) issues the refund manually and
 * stamps {@link #resolvedAt}.
 */
@Entity
@Table(schema = "payment", name = "stripe_refund_failures")
@Getter
@Setter
@NoArgsConstructor
public class StripeRefundFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_intent_id", nullable = false, updatable = false, unique = true)
    private String paymentIntentId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "pack_tier_id", nullable = false)
    private UUID packTierId;

    @Column(name = "error")
    private String error;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StripeRefundFailure(String paymentIntentId, BigDecimal amount, Long parentId, UUID packTierId, String error) {
        this.paymentIntentId = paymentIntentId;
        this.amount = amount;
        this.parentId = parentId;
        this.packTierId = packTierId;
        this.error = error;
        this.attempts = 1;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (attempts < 1) attempts = 1;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
