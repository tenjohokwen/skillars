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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "session_pack_purchases")
@Getter
@Setter
@NoArgsConstructor
public class SessionPackPurchase {

    /** A card dispute on the pack's charge was lost: the payer has the money back. */
    public static final String REVOKED_CHARGEBACK_LOST = "CHARGEBACK_LOST";
    /** The pack's charge was refunded in full outside the app (e.g. the Stripe Dashboard). */
    public static final String REVOKED_EXTERNAL_REFUND = "EXTERNAL_REFUND";
    /** The coach was suspended: the unused sessions were refunded to the payer's wallet. */
    public static final String REVOKED_COACH_SUSPENDED = "COACH_SUSPENDED";

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
     * feat/coach-payouts-pack-and-credit (V186): {@code platform.commission.rate} in force when the pack
     * was bought. Each completed pack session's coach payout deducts THIS rate, never the live one.
     * Nullable only for packs sold before V186 — the payout falls back to the live rate for those, as
     * it does for a pre-V133 {@code booking_payments} row.
     */
    @Column(name = "commission_rate", updatable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    /**
     * feat/coach-payouts-pack-and-credit (V186): sessions sold in this pack, stamped at purchase (the
     * tier's {@code session_count} is coach-editable afterwards). The per-session payout share is
     * {@code amountCharged / sessionCount}. Nullable only for rows V186 could not backfill.
     */
    @Column(name = "session_count", updatable = false)
    private Integer sessionCount;

    /**
     * feat/coach-payouts-pack-and-credit (V186): how many of this pack's completed sessions have had a
     * coach payout allocated, and the gross allocated to them so far. Written only by
     * {@code CoachPayoutOutboxSupport} under this row's lock, so the last session takes the remainder
     * and the allocated gross never exceeds {@link #amountCharged}.
     */
    @Column(name = "payout_sessions_allocated", nullable = false)
    private int payoutSessionsAllocated;

    @Column(name = "payout_gross_allocated", nullable = false, precision = 10, scale = 2)
    private BigDecimal payoutGrossAllocated = BigDecimal.ZERO;

    /**
     * fix/payment-pack-refund-rounding (V200): how many of this pack's sessions have been refunded to the
     * payer as money (a wallet {@code BOOKING_REFUND} for a booking on an expired pack), and the gross
     * refunded so far. Written only under this row's lock, by {@code PackRefundAllocationService}, with a
     * per-booking {@code pack_session_refunds} marker. Together with the payout columns above they are
     * the pack's settlement ledger: every session is settled once — paid to its coach or refunded — and
     * whichever settles LAST takes the rounding remainder, so payouts + refunds never drift from
     * {@link #amountCharged} by the cents {@link #perSessionShare()} rounds away.
     */
    @Column(name = "refund_sessions_issued", nullable = false)
    private int refundSessionsIssued;

    @Column(name = "refund_gross_issued", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundGrossIssued = BigDecimal.ZERO;

    /**
     * skillars-deferred-164 AC5. Mutable by design — deliberately NOT {@code updatable = false}.
     * Three service-layer call sites mutate this outside the entity: {@code PackSessionService}
     * (pause, extends by the pause duration) and {@code SessionPackPaymentService} (both
     * {@code extendPack}, +30 days, and {@code createPurchase}'s initial set, now + the pack-size tier's {@code pack.expiry.days.tierN}). Treat
     * this as a service-layer invariant mutated only by those two services' pause/extend/create
     * logic, not a bare "whoever has the setter can call it" surface — the class-level
     * {@link Setter @Setter} stays (52 existing test call sites make a narrower, field-specific
     * replacement its own, separate change).
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "extended_at")
    private Instant extendedAt;

    /**
     * fix/payment-pack-integrity (V210): when the pause starts. The pack is paused over
     * {@code [pausedFrom, pausedUntil)} only — see {@link #isPausedAt}. Null on a pause recorded before
     * V210, which keeps the old meaning (paused until {@code pausedUntil}).
     */
    @Column(name = "paused_from")
    private Instant pausedFrom;

    @Column(name = "paused_until")
    private Instant pausedUntil;

    /**
     * fix/payment-pack-integrity (V210): set while a card dispute on the pack's charge is open — the pack
     * cannot be booked against or spent until the dispute is won (cleared) or lost (revoked).
     */
    @Column(name = "dispute_frozen_at")
    private Instant disputeFrozenAt;

    /**
     * fix/payment-pack-integrity (V210): the pack's remaining sessions were taken away, and why
     * ({@link #REVOKED_CHARGEBACK_LOST}, {@link #REVOKED_EXTERNAL_REFUND}, {@link #REVOKED_COACH_SUSPENDED}).
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 40)
    private String revokedReason;

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

    /**
     * fix/payment-session-pack-integrity: one session's share of what this pack was charged —
     * {@code amountCharged / sessionCount} rounded DOWN to the cent, so settling every session at it can
     * never return more than was paid (fix/payment-pack-refund-rounding: settlement — payouts and refunds —
     * uses {@link #nextSettlementValue()}, whose last session takes the remainder) (the tier's HALF_UP {@code pricePerSession} could:
     * 3 x 66.67 = 200.01 for 200.00). Falls back to {@code pricePerSession} only for a legacy row V181/V186
     * could not stamp.
     */
    public BigDecimal perSessionShare() {
        if (hasStampedAmount()) {
            return amountCharged.divide(BigDecimal.valueOf(sessionCount), 2, RoundingMode.DOWN);
        }
        return pricePerSession;
    }

    /** Sessions settled so far: paid out to the coach, or refunded to the payer as money. */
    public int settledSessions() {
        return payoutSessionsAllocated + refundSessionsIssued;
    }

    /** Gross settled so far: allocated to coach payouts plus refunded to the payer. */
    public BigDecimal settledGross() {
        return nz(payoutGrossAllocated).add(nz(refundGrossIssued));
    }

    /**
     * What is still unsettled of what this pack was charged — {@code amountCharged} minus every coach
     * allocation and every refund so far. This is the most that refunding ALL the pack's remaining value
     * may return; it already includes the rounding remainder. Null for a legacy row with no stamped
     * {@code amountCharged}/{@code sessionCount}.
     */
    public BigDecimal unsettledValue() {
        if (!hasStampedAmount()) {
            return null;
        }
        return amountCharged.subtract(settledGross());
    }

    /**
     * fix/payment-pack-refund-rounding: the value of the next session to settle (coach payout or refund),
     * read under this row's lock. Every session but the last settles at {@link #perSessionShare()} (rounded
     * DOWN); the {@code sessionCount}-th settled session takes {@link #unsettledValue()}, so the shares and
     * the remainder sum to exactly {@code amountCharged} whatever the order of payouts and refunds. Never
     * more than {@link #unsettledValue()}, never negative. Falls back to {@code pricePerSession} for a legacy
     * row.
     */
    public BigDecimal nextSettlementValue() {
        if (!hasStampedAmount()) {
            return pricePerSession;
        }
        BigDecimal remaining = unsettledValue();
        if (remaining.signum() <= 0 || settledSessions() >= sessionCount) {
            return BigDecimal.ZERO;
        }
        return settledSessions() + 1 >= sessionCount ? remaining : perSessionShare().min(remaining);
    }

    /** Records one session's coach-payout allocation. Caller holds this row's lock. */
    public void recordPayoutAllocation(BigDecimal gross) {
        payoutSessionsAllocated++;
        payoutGrossAllocated = nz(payoutGrossAllocated).add(gross);
    }

    /** Records one session refunded to the payer. Caller holds this row's lock. */
    public void recordRefund(BigDecimal amount) {
        refundSessionsIssued++;
        refundGrossIssued = nz(refundGrossIssued).add(amount);
    }

    /**
     * Un-records one session's refund (the refund was clawed back by an {@code UPHOLD_COACH} dispute and the
     * session is now paid to the coach instead). Caller holds this row's lock.
     */
    public void releaseRefund(BigDecimal amount) {
        refundSessionsIssued = Math.max(0, refundSessionsIssued - 1);
        refundGrossIssued = nz(refundGrossIssued).subtract(amount).max(BigDecimal.ZERO);
    }

    /**
     * fix/payment-pack-integrity: whether the pack is paused at {@code at} — inside
     * {@code [pausedFrom, pausedUntil)}. A pause scheduled for a later date does not block the pack before it
     * starts. A pre-V210 pause (no {@code pausedFrom}) counts from any time until {@code pausedUntil}.
     */
    public boolean isPausedAt(Instant at) {
        return pausedUntil != null && at.isBefore(pausedUntil) && (pausedFrom == null || !at.isBefore(pausedFrom));
    }

    /** fix/payment-pack-integrity: a card dispute on the pack's charge is open. */
    public boolean isDisputeFrozen() {
        return disputeFrozenAt != null;
    }

    /** fix/payment-pack-integrity: the pack's remaining sessions were revoked. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** True when {@code amountCharged} and {@code sessionCount} were stamped at purchase. */
    public boolean hasStampedAmount() {
        return amountCharged != null && sessionCount != null && sessionCount > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
