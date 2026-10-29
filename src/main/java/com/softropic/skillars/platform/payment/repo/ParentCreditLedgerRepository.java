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
     * fix/payment-payout-recovery: refunds credited in {@code [from, to]} for the coach's bookings, net of what
     * an {@code UPHOLD_COACH} dispute took back ({@code BOOKING_REFUND_REVERSAL}, negative) — whatever happened
     * to the payout, so a fully refunded session counts too. Native: joins the booking module's table.
     */
    @Query(value = """
        SELECT COALESCE(SUM(l.amount), 0)
        FROM payment.parent_credit_ledger l
        JOIN booking.bookings b ON b.id = l.reference_id
        WHERE b.coach_id = :coachId
          AND l.type IN ('BOOKING_REFUND', 'BOOKING_REFUND_REVERSAL')
          AND l.created_at BETWEEN :from AND :to
        """, nativeQuery = true)
    BigDecimal sumRefundsByCoachAndPeriod(@Param("coachId") UUID coachId, @Param("from") java.time.Instant from,
                                          @Param("to") java.time.Instant to);

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

    // --- fix/payment-dispute-credit-trace-and-lost-dispute (V199) ---

    boolean existsByReferenceIdAndParentIdAndType(UUID referenceId, Long parentId, String type);

    List<ParentCreditLedger> findByReferenceIdAndTypeOrderByParentId(UUID referenceId, String type);

    /** Wallet credit traced to one card charge, per payer. */
    interface TracedCredit {
        Long getParentId();
        BigDecimal getAmount();
    }

    /**
     * The wallet credit a card charge (PaymentIntent) funded and the payer still holds the claim to, per
     * payer: every {@code BOOKING_REFUND} whose money came from {@code :pi}, counted only up to its card
     * part, minus what an {@code UPHOLD_COACH} resolution already took back ({@code BOOKING_REFUND_REVERSAL}).
     * The source of a refund is, in order: the row's own {@code source_payment_intent_id} (with
     * {@code source_card_amount}); else the booking's {@code booking_payments} charge (card part =
     * {@code stripe_charged}); else the charge of the pack the booking drew on (card part = what the pack
     * was charged).
     */
    @Query(value = """
        SELECT t.parent_id AS "parentId", SUM(t.card_part) AS "amount" FROM (
          SELECT l.parent_id,
                 GREATEST(LEAST(l.amount, COALESCE(l.source_card_amount, l.amount))
                          + COALESCE((SELECT SUM(x.amount) FROM payment.parent_credit_ledger x WHERE x.type = 'BOOKING_REFUND_REVERSAL' AND x.reference_id = l.reference_id), 0), 0) AS card_part
          FROM payment.parent_credit_ledger l
          WHERE l.type = 'BOOKING_REFUND' AND l.source_payment_intent_id = :pi
          UNION ALL
          SELECT l.parent_id,
                 GREATEST(LEAST(l.amount, COALESCE(bp.stripe_charged, 0)) + COALESCE((SELECT SUM(x.amount) FROM payment.parent_credit_ledger x WHERE x.type = 'BOOKING_REFUND_REVERSAL' AND x.reference_id = l.reference_id), 0), 0)
          FROM payment.parent_credit_ledger l
          JOIN payment.booking_payments bp ON bp.booking_id = l.reference_id
          WHERE l.type = 'BOOKING_REFUND' AND l.source_payment_intent_id IS NULL
            AND bp.stripe_payment_intent_id = :pi
          UNION ALL
          SELECT l.parent_id,
                 GREATEST(LEAST(l.amount, COALESCE(sp.amount_charged, 0)) + COALESCE((SELECT SUM(x.amount) FROM payment.parent_credit_ledger x WHERE x.type = 'BOOKING_REFUND_REVERSAL' AND x.reference_id = l.reference_id), 0), 0)
          FROM payment.parent_credit_ledger l
          JOIN booking.bookings b ON b.id = l.reference_id
          JOIN payment.session_pack_purchases sp ON sp.purchase_id = b.session_pack_purchase_id
          WHERE l.type = 'BOOKING_REFUND' AND l.source_payment_intent_id IS NULL
            AND sp.stripe_payment_intent_id = :pi
            AND NOT EXISTS (SELECT 1 FROM payment.booking_payments bp2
                            WHERE bp2.booking_id = l.reference_id AND bp2.stripe_payment_intent_id IS NOT NULL)
        ) t
        WHERE t.card_part > 0
        GROUP BY t.parent_id
        ORDER BY t.parent_id
        """, nativeQuery = true)
    List<TracedCredit> findCreditFundedByPaymentIntent(@Param("pi") String paymentIntentId);
}
