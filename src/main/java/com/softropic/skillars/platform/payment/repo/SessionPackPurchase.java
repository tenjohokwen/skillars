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

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "pack_tier_id", nullable = false)
    private UUID packTierId;

    @Column(name = "price_per_session", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal pricePerSession;

    /**
     * fix/payment-money-integrity (V181): what the payer was actually charged for the pack — the
     * tier's total price at purchase time, stamped because the tier is coach-editable afterwards.
     * Read by the admin finance overview's gross volume. Nullable only for legacy rows V181 could
     * not backfill.
     */
    @Column(name = "amount_charged", updatable = false, precision = 10, scale = 2)
    private BigDecimal amountCharged;

    @Column(name = "remaining_sessions", nullable = false)
    private int remainingSessions;

    /**
     * skillars-deferred-164 AC5. Mutable by design — deliberately NOT {@code updatable = false}.
     * Three service-layer call sites mutate this outside the entity: {@code PackSessionService}
     * (pause, extends by the pause duration) and {@code SessionPackPaymentService} (both
     * {@code extendPack}, +30 days, and {@code createPurchase}'s initial set, now+60 days). Treat
     * this as a service-layer invariant mutated only by those two services' pause/extend/create
     * logic, not a bare "whoever has the setter can call it" surface — the class-level
     * {@link Setter @Setter} stays (52 existing test call sites make a narrower, field-specific
     * replacement its own, separate change).
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "extended_at")
    private Instant extendedAt;

    @Column(name = "paused_until")
    private Instant pausedUntil;

    @Column(name = "expired_notified_at")
    private Instant expiredNotifiedAt;

    @Column(name = "expiry_warned_at")
    private Instant expiryWarnedAt;

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
