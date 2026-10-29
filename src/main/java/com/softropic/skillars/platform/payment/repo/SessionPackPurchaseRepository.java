package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SessionPackPurchaseRepository extends JpaRepository<SessionPackPurchase, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT p FROM SessionPackPurchase p WHERE p.purchaseId = :id")
    Optional<SessionPackPurchase> findByIdForUpdate(@Param("id") UUID id);

    // expiryWarnedAt IS NULL (Deferred-15 AC6): the notifier runs daily over a 14-day window, so
    // without this predicate one pack is re-selected on up to 14 consecutive mornings. Mirrors
    // findExpiredNotYetNotified below.
    @Query("SELECT p FROM SessionPackPurchase p WHERE p.expiresAt BETWEEN :from AND :to AND p.extendedAt IS NULL AND p.remainingSessions > 0 AND p.expiryWarnedAt IS NULL")
    List<SessionPackPurchase> findExpiringWithinWindowAndSessionsRemaining(@Param("from") Instant from, @Param("to") Instant to);

    List<SessionPackPurchase> findByParentIdOrderByCreatedAtDesc(Long parentId);

    /** feat/coach-workflow-gaps A20: a coach's sold packs, latest expiry first, paged. */
    org.springframework.data.domain.Page<SessionPackPurchase> findByCoachIdOrderByExpiresAtDesc(
        UUID coachId, org.springframework.data.domain.Pageable pageable);

    List<SessionPackPurchase> findByParentIdAndCoachIdOrderByCreatedAtDesc(Long parentId, UUID coachId);

    @Query("SELECT p FROM SessionPackPurchase p WHERE p.expiresAt < :now AND p.expiredNotifiedAt IS NULL AND p.remainingSessions > 0")
    List<SessionPackPurchase> findExpiredNotYetNotified(@Param("now") Instant now);

    // Deferred-65 AC1: soonest-expiring pack first, matching what the frontend already displays as
    // "current" (SessionPackPurchasePage.vue/ParentPlayerPortalPage.vue). createdAt DESC is a
    // secondary tiebreak for packs sharing an identical expiresAt — it mirrors the frontend's own
    // tiebreak (reduce() over a createdAt-DESC-ordered list keeps the first-encountered, i.e.
    // newest-created, element on a tie), so a tie resolves to the same pack on both sides.
    // purchaseId ASC is a final, purely-deterministic tertiary key for the (vanishingly unlikely)
    // case of packs tied on both expiresAt and createdAt.
    @Query("""
        SELECT p FROM SessionPackPurchase p
        WHERE p.playerId = :playerId AND p.coachId = :coachId
          AND p.remainingSessions > 0 AND p.expiresAt > :now
          AND (p.pausedUntil IS NULL OR p.pausedUntil <= :now)
        ORDER BY p.expiresAt ASC, p.createdAt DESC, p.purchaseId ASC
        """)
    List<SessionPackPurchase> findActivePacks(@Param("playerId") Long playerId,
                                              @Param("coachId") UUID coachId,
                                              @Param("now") Instant now);

    Optional<SessionPackPurchase> findTopByPlayerIdAndCoachIdOrderByCreatedAtDesc(Long playerId, UUID coachId);

    // Story Deferred-75 AC6: batch form of findActivePacks' own filter conditions, copied exactly
    // (remainingSessions > 0, expiresAt > now, and the pausedUntil window) so this method's semantics
    // stay identical to the sum of individual hasActivePack calls it replaces in
    // HomeworkAssignmentService.getLockerRoomDrills's N+1 loop.
    @Query("SELECT DISTINCT p.coachId FROM SessionPackPurchase p WHERE p.playerId = :playerId " +
        "AND p.coachId IN :coachIds AND p.remainingSessions > 0 AND p.expiresAt > :now " +
        "AND (p.pausedUntil IS NULL OR p.pausedUntil <= :now)")
    Set<UUID> findCoachIdsWithActivePack(@Param("playerId") Long playerId,
                                          @Param("coachIds") Set<UUID> coachIds,
                                          @Param("now") Instant now);

    /**
     * fix/payment-money-integrity: pack revenue charged in the period, for the admin finance
     * overview's gross volume. {@code amountCharged} is stamped at purchase (V181).
     */
    @Query("SELECT COALESCE(SUM(p.amountCharged), 0) FROM SessionPackPurchase p WHERE p.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountChargedForPeriod(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * fix/payment-session-pack-integrity: the pack (if any) already funded by this Stripe charge. Backed by
     * the V195 partial unique index, so at most one row matches.
     */
    Optional<SessionPackPurchase> findByStripePaymentIntentId(String stripePaymentIntentId);

    /**
     * fix/payment-session-pack-integrity: claims the per-booking restore marker (V195). Returns 1 when this
     * call claimed it (the caller then restores the session in the same transaction), 0 when the booking's
     * session was already restored — a re-driven outbox row.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO payment.pack_session_restores (booking_id, purchase_id, reason, restored_at)
        VALUES (:bookingId, :purchaseId, :reason, now())
        ON CONFLICT (booking_id) DO NOTHING
        """)
    int insertRestoreMarker(@Param("bookingId") UUID bookingId, @Param("purchaseId") UUID purchaseId,
                            @Param("reason") String reason);

    /**
     * feat/payment-forfeit-payouts-and-clawback: the booking's pack session was given back (its V195 restore
     * marker exists) — what an {@code UPHOLD_COACH} dispute resolution re-deducts.
     */
    @Query(nativeQuery = true, value = "SELECT EXISTS (SELECT 1 FROM payment.pack_session_restores WHERE booking_id = :bookingId)")
    boolean existsRestoreMarker(@Param("bookingId") UUID bookingId);

    /**
     * fix/payment-pack-refund-rounding: claims the per-booking money-refund marker (V200) for a pack session
     * refunded to the payer. 1 when this call claimed it (the caller records the refund on the locked pack
     * row in the same transaction), 0 when this booking's session was already refunded.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO payment.pack_session_refunds (booking_id, purchase_id, amount, refunded_at)
        VALUES (:bookingId, :purchaseId, :amount, now())
        ON CONFLICT (booking_id) DO NOTHING
        """)
    int insertRefundMarker(@Param("bookingId") UUID bookingId, @Param("purchaseId") UUID purchaseId,
                           @Param("amount") BigDecimal amount);

    /** fix/payment-pack-refund-rounding: the amount a booking's pack session was refunded at (its V200 marker). */
    @Query(nativeQuery = true, value = "SELECT amount FROM payment.pack_session_refunds WHERE booking_id = :bookingId")
    Optional<BigDecimal> findRefundMarkerAmount(@Param("bookingId") UUID bookingId);

    /** fix/payment-pack-refund-rounding: drops a booking's refund marker once its refund was clawed back. */
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM payment.pack_session_refunds WHERE booking_id = :bookingId")
    int deleteRefundMarker(@Param("bookingId") UUID bookingId);

    /** Pack purchases in the period that went through a Stripe charge — the Stripe-fee estimate's count. */
    @Query("SELECT COUNT(p) FROM SessionPackPurchase p WHERE p.stripePaymentIntentId IS NOT NULL AND p.createdAt BETWEEN :from AND :to")
    long countChargedForPeriod(@Param("from") Instant from, @Param("to") Instant to);
}
