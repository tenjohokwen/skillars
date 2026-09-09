package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
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

/**
 * skillars-deferred-106. {@code booking_id} is the {@code @Id} — {@link #findById} is the by-PK load
 * the outbox handlers and {@code DisputeService} use as their idempotency anchor.
 */
public interface CoachPayoutRepository extends JpaRepository<CoachPayout, UUID> {

    /**
     * AC10.2: {@code DisputeService.resolveDispute} branches on the payout status <em>under a row
     * lock</em>, atomically with the dispute-resolution write, so a concurrent
     * {@code CoachPayoutTransferHandler} drain cannot fire the transfer between the read and the
     * {@code PENDING_RELEASE} -> {@code CANCELLED} flip.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CoachPayout p WHERE p.bookingId = :bookingId")
    Optional<CoachPayout> findByIdForUpdate(@Param("bookingId") UUID bookingId);

    // --- AC12 revenue reporting: coach figures move off booking_payments.CAPTURED onto ------------
    // --- coach_payouts rows in RELEASED, dated by released_at. -----------------------------------

    @Query(value = """
        SELECT COALESCE(SUM(p.net_amount), 0)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'RELEASED'
          AND p.released_at BETWEEN :from AND :to
        """, nativeQuery = true)
    BigDecimal sumReleasedNetByCoachAndPeriod(@Param("coachId") UUID coachId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    @Query(value = """
        SELECT COALESCE(SUM(p.gross_amount), 0)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'RELEASED'
          AND p.released_at BETWEEN :from AND :to
        """, nativeQuery = true)
    BigDecimal sumReleasedGrossByCoachAndPeriod(@Param("coachId") UUID coachId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    @Query(value = """
        SELECT COALESCE(SUM(p.commission_amount), 0)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'RELEASED'
          AND p.released_at BETWEEN :from AND :to
        """, nativeQuery = true)
    BigDecimal sumReleasedCommissionByCoachAndPeriod(@Param("coachId") UUID coachId,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to);

    @Query(value = """
        SELECT COUNT(*)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'RELEASED'
          AND p.released_at BETWEEN :from AND :to
        """, nativeQuery = true)
    long countReleasedByCoachAndPeriod(@Param("coachId") UUID coachId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    /**
     * AC12.2: completed-but-not-yet-released sessions show as a separate "pending release" figure,
     * never folded into released revenue. Dated by {@code created_at} — a {@code PENDING_RELEASE}
     * row has no {@code released_at} yet.
     */
    @Query(value = """
        SELECT COALESCE(SUM(p.net_amount), 0)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'PENDING_RELEASE'
          AND p.created_at BETWEEN :from AND :to
        """, nativeQuery = true)
    BigDecimal sumPendingReleaseNetByCoachAndPeriod(@Param("coachId") UUID coachId,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to);

    @Query(value = """
        SELECT COUNT(*)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'PENDING_RELEASE'
          AND p.created_at BETWEEN :from AND :to
        """, nativeQuery = true)
    long countPendingReleaseByCoachAndPeriod(@Param("coachId") UUID coachId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    @Query(value = """
        SELECT p.booking_id
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status = 'RELEASED'
          AND p.released_at BETWEEN :from AND :to
        """, nativeQuery = true)
    List<UUID> findReleasedBookingIdsByCoachAndPeriod(@Param("coachId") UUID coachId,
                                                      @Param("from") Instant from,
                                                      @Param("to") Instant to);

    /**
     * AC12.1/AC12.2 transaction list: RELEASED and PENDING_RELEASE payouts for a coach, newest
     * first, paged. The {@code status} column on each row carries the released-vs-pending
     * distinction; dated by {@code released_at} when set, else {@code created_at}.
     */
    @Query(value = """
        SELECT p.*
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status IN ('RELEASED', 'PENDING_RELEASE')
          AND COALESCE(p.released_at, p.created_at) BETWEEN :from AND :to
        ORDER BY COALESCE(p.released_at, p.created_at) DESC
        """,
        countQuery = """
        SELECT COUNT(*)
        FROM payment.coach_payouts p
        WHERE p.coach_id = :coachId
          AND p.status IN ('RELEASED', 'PENDING_RELEASE')
          AND COALESCE(p.released_at, p.created_at) BETWEEN :from AND :to
        """,
        nativeQuery = true)
    Page<CoachPayout> findPayoutTransactionsByCoachAndPeriod(@Param("coachId") UUID coachId,
                                                             @Param("from") Instant from,
                                                             @Param("to") Instant to,
                                                             Pageable pageable);

    /** AC12.2 / receipt gate: the RELEASED payout for one booking, if it exists. */
    Optional<CoachPayout> findByBookingIdAndStatus(UUID bookingId, String status);
}
