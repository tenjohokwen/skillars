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
 * fix/payment-payout-recovery (V205): money the platform refunded to a payer that a transfer reversal could
 * not take back from the coach (the coach withdrew the balance, the account closed, nothing was left of the
 * transfer). {@code CoachPayoutTransferHandler} deducts OPEN debts from the coach's next transfers, oldest
 * first; an {@code UPHOLD_COACH} on the debt's booking waives it (the coach is owed that net after all).
 */
@Entity
@Table(schema = "payment", name = "coach_payout_debts")
@Getter
@Setter
@NoArgsConstructor
public class CoachPayoutDebt {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RECOVERED = "RECOVERED";
    public static final String STATUS_WAIVED = "WAIVED";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    /** The booking whose reversal failed. */
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "recovered_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal recoveredAmount = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CoachPayoutDebt open(UUID coachId, UUID bookingId, BigDecimal amount, String reason) {
        CoachPayoutDebt d = new CoachPayoutDebt();
        d.setId(UUID.randomUUID());
        d.setCoachId(coachId);
        d.setBookingId(bookingId);
        d.setAmount(amount);
        d.setStatus(STATUS_OPEN);
        d.setReason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
        return d;
    }

    /** What is still owed. */
    public BigDecimal getOutstanding() {
        return amount.subtract(recoveredAmount == null ? BigDecimal.ZERO : recoveredAmount).max(BigDecimal.ZERO);
    }

    /** Records {@code recovered} more as repaid (at most the outstanding amount); RECOVERED once nothing is left. */
    public void recover(BigDecimal recovered) {
        BigDecimal take = recovered.min(getOutstanding());
        recoveredAmount = (recoveredAmount == null ? BigDecimal.ZERO : recoveredAmount).add(take);
        if (getOutstanding().signum() <= 0) {
            status = STATUS_RECOVERED;
        }
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
