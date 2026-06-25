package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "session_pack_purchases")
@Getter
@Setter
@NoArgsConstructor
public class SessionPackPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "purchase_id", updatable = false, nullable = false)
    private UUID purchaseId;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "pack_tier_id", nullable = false)
    private UUID packTierId;

    @Column(name = "price_per_session", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal pricePerSession;

    @Column(name = "remaining_sessions", nullable = false)
    private int remainingSessions;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "extended_at")
    private Instant extendedAt;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
