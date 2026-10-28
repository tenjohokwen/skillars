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

    /**
     * feat/coach-payouts-pack-and-credit (V186): the session pack that funded this booking, for a
     * pack-funded payout; null for a card/credit-funded one.
     */
    @Column(name = "session_pack_purchase_id", updatable = false)
    private UUID sessionPackPurchaseId;

    @Column(name = "gross_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "commission_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "net_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal netAmount;

    /**
     * fix/payment-payouts-disputes-webhooks (V193): what has been reversed from the released transfer
     * so far. A partial reversal keeps the row {@code RELEASED}; the coach's earned net is
     * {@link #getEffectiveNetAmount()}.
     */
    @Column(name = "reversed_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal reversedAmount = BigDecimal.ZERO;

    /** fix/payment-payouts-disputes-webhooks (V193): an in-app dispute on the booking is open. */
    @Column(name = "dispute_frozen", nullable = false)
    private boolean disputeFrozen;

    /** fix/payment-payouts-disputes-webhooks (V193): a Stripe card dispute on the funding charge is open. */
    @Column(name = "chargeback_frozen", nullable = false)
    private boolean chargebackFrozen;

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

    /** Net still with the coach: {@code net_amount - reversed_amount}, never negative. */
    public BigDecimal getEffectiveNetAmount() {
        BigDecimal reversed = reversedAmount == null ? BigDecimal.ZERO : reversedAmount;
        BigDecimal net = netAmount == null ? BigDecimal.ZERO : netAmount;
        return net.subtract(reversed).max(BigDecimal.ZERO);
    }

    /** Gross pro rata to the net the coach kept (same formula as the reporting queries). */
    public BigDecimal getEffectiveGrossAmount() {
        return proRata(grossAmount);
    }

    /** Commission pro rata to the net the coach kept (same formula as the reporting queries). */
    public BigDecimal getEffectiveCommissionAmount() {
        return proRata(commissionAmount);
    }

    private BigDecimal proRata(BigDecimal amount) {
        if (amount == null || netAmount == null || netAmount.signum() <= 0
                || reversedAmount == null || reversedAmount.signum() == 0) {
            return amount;
        }
        return amount.multiply(getEffectiveNetAmount()).divide(netAmount, 2, java.math.RoundingMode.HALF_UP);
    }

    /** True while a per-payout freeze reason (in-app dispute or card dispute) stands. */
    public boolean hasPayoutFreezeReason() {
        return disputeFrozen || chargebackFrozen;
    }

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
