package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * fix/payment-payout-recovery. Lock order: every locker of debt rows already holds a
 * {@code coach_payouts} row lock (payout row first, debt rows second), and takes the debt rows in
 * {@code (created_at, id)} order, so two transfers of one coach serialise on the debts instead of deadlocking.
 */
public interface CoachPayoutDebtRepository extends JpaRepository<CoachPayoutDebt, UUID> {

    /** The coach's OPEN debts, oldest first, locked — the transfer-time offset. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CoachPayoutDebt d WHERE d.coachId = :coachId AND d.status = 'OPEN' "
        + "ORDER BY d.createdAt, d.id")
    List<CoachPayoutDebt> findOpenByCoachIdForUpdate(@Param("coachId") UUID coachId);

    /** The OPEN debts a failed reversal of this booking left, locked — waived by an {@code UPHOLD_COACH}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM CoachPayoutDebt d WHERE d.bookingId = :bookingId AND d.status = 'OPEN' "
        + "ORDER BY d.createdAt, d.id")
    List<CoachPayoutDebt> findOpenByBookingIdForUpdate(@Param("bookingId") UUID bookingId);

    /** What the coach still owes, for the admin view and alerts. */
    @Query("SELECT COALESCE(SUM(d.amount - d.recoveredAmount), 0) FROM CoachPayoutDebt d "
        + "WHERE d.coachId = :coachId AND d.status = 'OPEN'")
    BigDecimal sumOutstandingByCoachId(@Param("coachId") UUID coachId);
}
