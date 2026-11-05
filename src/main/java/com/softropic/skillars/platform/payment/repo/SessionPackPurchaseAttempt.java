package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * skillars-deferred-169 AC1: one row per session-pack purchase attempt, written BEFORE the charge. A partial unique index on
 * {@code (parent_id) WHERE status IN (open statuses)} allows one open attempt per parent, which is the concurrency control for
 * "two purchases cannot both reach Stripe". Status writes go through
 * {@link SessionPackPurchaseAttemptRepository#transition} (compare-and-set), never through {@code save}.
 */
@Entity
@Table(schema = "payment", name = "session_pack_purchase_attempts")
@Getter
@Setter
@NoArgsConstructor
public class SessionPackPurchaseAttempt {

    public enum Status {
        IN_PROGRESS, SUCCEEDED, NO_CHARGE, REFUNDED, UNKNOWN, UNRECONCILED;

        /** The statuses covered by the partial unique index. */
        public static final Set<Status> OPEN = EnumSet.of(IN_PROGRESS, UNKNOWN, UNRECONCILED);

        public boolean isOpen() {
            return OPEN.contains(this);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id", nullable = false, updatable = false)
    private Long parentId;

    @Column(name = "attempt_key", nullable = false, updatable = false)
    private String attemptKey;

    @Column(name = "pack_tier_id", nullable = false, updatable = false)
    private UUID packTierId;

    @Column(name = "player_id", nullable = false, updatable = false)
    private Long playerId;

    @Column(name = "previous_card", updatable = false)
    private String previousCard;

    @Column(name = "attempted_card", updatable = false)
    private String attemptedCard;

    @Column(name = "customer_existed", nullable = false, updatable = false)
    private boolean customerExisted;

    @Column(name = "payment_intent_id", insertable = false, updatable = false)
    private String paymentIntentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    public SessionPackPurchaseAttempt(Long parentId, String attemptKey, UUID packTierId, Long playerId,
                                      String previousCard, String attemptedCard, boolean customerExisted) {
        this.parentId = parentId;
        this.attemptKey = attemptKey;
        this.packTierId = packTierId;
        this.playerId = playerId;
        this.previousCard = previousCard;
        this.attemptedCard = attemptedCard;
        this.customerExisted = customerExisted;
        this.status = Status.IN_PROGRESS;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
