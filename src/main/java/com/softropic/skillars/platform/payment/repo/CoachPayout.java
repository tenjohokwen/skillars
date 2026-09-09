package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * skillars-deferred-106: the coach-payout ledger. <strong>One row per booking</strong>
 * ({@code booking_id} is the {@code @Id} — a booking is paid out at most once), which is both the
 * idempotency anchor for {@code CoachPayoutTransferHandler} / {@code CoachPayoutReversalHandler} and
 * the concurrent backstop (mirrors {@code uq_pcl_booking_refund} / V127).
 *
 * <p>The row is created {@code PENDING_RELEASE} by {@code CoachPayoutEnqueueListener}, atomically
 * with the booking's completion write and the {@code COACH_PAYOUT_TRANSFER} outbox message. See
 * {@link com.softropic.skillars.platform.payment.contract.CoachPayoutStatus} for the state machine.
 */
@Entity
@Table(schema = "payment", name = "coach_payouts")
@Getter
@Setter
@NoArgsConstructor
public class CoachPayout {

    @Id
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    /** The coach's connected account id at enqueue time, if resolvable. Re-resolved at transfer time. */
    @Column(name = "coach_stripe_account_id")
    private String coachStripeAccountId;

    @Column(name = "gross_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "commission_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "net_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    /** One of {@link com.softropic.skillars.platform.payment.contract.CoachPayoutStatus}. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "stripe_transfer_id")
    private String stripeTransferId;

    @Column(name = "stripe_transfer_reversal_id")
    private String stripeTransferReversalId;

    /** {@code completed_at + payment.payout.hold_hours}. The transfer is deferred until this instant. */
    @Column(name = "release_after", nullable = false)
    private Instant releaseAfter;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
