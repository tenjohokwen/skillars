package com.softropic.skillars.platform.payment.repo;

import com.softropic.skillars.platform.payment.contract.CreditLedgerEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParentCreditLedgerRepository extends JpaRepository<ParentCreditLedger, UUID> {

    @Query("SELECT SUM(l.amount) FROM ParentCreditLedger l WHERE l.parentId = :parentId")
    Optional<BigDecimal> sumByParentId(@Param("parentId") Long parentId);

    /**
     * Refunds credited for these bookings, net of what an {@code UPHOLD_COACH} dispute took back
     * ({@code BOOKING_REFUND_REVERSAL}, negative) — feat/payment-forfeit-payouts-and-clawback.
     */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ParentCreditLedger l WHERE l.type IN ('"
        + CreditLedgerEntryType.BOOKING_REFUND + "', '" + CreditLedgerEntryType.BOOKING_REFUND_REVERSAL
        + "') AND l.referenceId IN :bookingIds")
    BigDecimal sumRefundsByBookingIds(@Param("bookingIds") List<UUID> bookingIds);

    @Query("SELECT l FROM ParentCreditLedger l WHERE l.parentId = :parentId AND l.createdAt BETWEEN :from AND :to ORDER BY l.createdAt DESC, l.txId DESC")
    Page<ParentCreditLedger> findByParentAndPeriod(@Param("parentId") Long parentId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to,
                                                   Pageable pageable);

    /**
     * Opening balance for the credit statement: sum of every row that sorts strictly <em>after</em>
     * the anchor {@code (createdAt, txId)} in the page query's {@code createdAt DESC, txId DESC}
     * order — i.e. everything older, including a row that shares the anchor's exact {@code createdAt}
     * but sorts onto a later page (smaller {@code txId}). skillars-deferred-102 AC11: replaces the
     * old {@code createdAt < :before} anchor, which dropped such a twin and understated the balance.
     */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ParentCreditLedger l WHERE l.parentId = :parentId AND (l.createdAt < :createdAt OR (l.createdAt = :createdAt AND l.txId < :txId))")
    BigDecimal sumByParentIdBeforeAnchor(@Param("parentId") Long parentId,
                                        @Param("createdAt") Instant createdAt,
                                        @Param("txId") UUID txId);

    /** Refund credit in the period, net of refund reversals booked in it (feat/payment-forfeit-payouts-and-clawback). */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ParentCreditLedger l WHERE l.type IN ('"
        + CreditLedgerEntryType.BOOKING_REFUND + "', '" + CreditLedgerEntryType.BOOKING_REFUND_REVERSAL
        + "') AND l.createdAt BETWEEN :from AND :to")
    BigDecimal sumTotalRefundCredit(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Money returned to cards by cash-outs in the period: {@code CASH_OUT_DEBIT} rows whose refund
     * did not fail. fix/payment-money-integrity: a failed refund's debit is reversed by
     * {@code CASH_OUT_REVERSAL} rows carrying the same {@code reference_id} (the cash-out id), and
     * is excluded here. Legacy rows (before that fix) have no {@code reference_id}, so a legacy
     * failed cash-out is still counted; they also hold the gross requested amount, not the net.
     */
    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = '"
        + CreditLedgerEntryType.CASH_OUT_DEBIT + "' AND l.createdAt BETWEEN :from AND :to"
        + " AND NOT EXISTS (SELECT r.txId FROM ParentCreditLedger r WHERE r.type = '"
        + CreditLedgerEntryType.CASH_OUT_REVERSAL + "' AND r.parentId = l.parentId AND r.referenceId = l.referenceId)")
    BigDecimal sumTotalCashOuts(@Param("from") Instant from, @Param("to") Instant to);

    List<ParentCreditLedger> findAllByParentId(Long parentId);

    /**
     * skillars-deferred-91 AC2: the {@code CREDIT_WALLET_REFUND} outbox handler checks this before
     * writing, so a re-drive of an already-applied refund is a documented no-op. Backed by the
     * {@code uq_pcl_reference_type} partial unique index for the concurrent case.
     */
    boolean existsByReferenceIdAndType(UUID referenceId, String type);

    /**
     * feat/payment-forfeit-payouts-and-clawback: the booking's {@code BOOKING_REFUND} (V138's
     * {@code uq_pcl_booking_refund} allows at most one per booking) — what an {@code UPHOLD_COACH}
     * dispute resolution claws back.
     */
    Optional<ParentCreditLedger> findFirstByReferenceIdAndType(UUID referenceId, String type);
}
