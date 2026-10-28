package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** feat/coach-payouts-pack-and-credit (V186). See {@link CashOutReconciliation}. */
public interface CashOutReconciliationRepository extends JpaRepository<CashOutReconciliation, UUID> {

    /**
     * Locked load for a status transition. Blocking (no NOWAIT), like {@code CoachPayoutRepository}'s:
     * the only contenders are the cash-out request finishing and the re-check job, and each holds the
     * lock only for a short DB-only transaction (never across the Stripe call).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CashOutReconciliation r WHERE r.cashOutId = :id")
    Optional<CashOutReconciliation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Ids the re-check job may retry: UNKNOWN, or PENDING older than {@code stalePendingBefore} (a
     * request that died between the debit and the refund) — created after {@code notBefore}, i.e. still
     * inside Stripe's idempotency-key retention window, oldest first.
     */
    @Query("""
        SELECT r.cashOutId FROM CashOutReconciliation r
        WHERE r.createdAt > :notBefore
          AND (r.status = 'UNKNOWN' OR (r.status = 'PENDING' AND r.createdAt < :stalePendingBefore))
        ORDER BY r.createdAt
        """)
    List<UUID> findRetryable(@Param("notBefore") Instant notBefore,
                             @Param("stalePendingBefore") Instant stalePendingBefore,
                             Pageable pageable);

    /**
     * Open rows past Stripe's idempotency-key retention window that are still PENDING: retrying them
     * under the same key is no longer safe, so they are escalated to UNKNOWN for a human instead.
     */
    @Query("""
        SELECT r.cashOutId FROM CashOutReconciliation r
        WHERE r.status = 'PENDING' AND r.createdAt <= :notBefore
        ORDER BY r.createdAt
        """)
    List<UUID> findExpiredPending(@Param("notBefore") Instant notBefore, Pageable pageable);

    /**
     * Admin finance overview: cash-outs that need attention — UNKNOWN, or PENDING since before
     * {@code stalePendingBefore} (a cash-out mid-refund right now is healthy and not counted).
     */
    @Query("""
        SELECT COUNT(r) FROM CashOutReconciliation r
        WHERE r.status = 'UNKNOWN' OR (r.status = 'PENDING' AND r.createdAt < :stalePendingBefore)
        """)
    long countUnresolved(@Param("stalePendingBefore") Instant stalePendingBefore);

    @Query("""
        SELECT COALESCE(SUM(r.netAmount), 0) FROM CashOutReconciliation r
        WHERE r.status = 'UNKNOWN' OR (r.status = 'PENDING' AND r.createdAt < :stalePendingBefore)
        """)
    BigDecimal sumUnresolvedNet(@Param("stalePendingBefore") Instant stalePendingBefore);
}
