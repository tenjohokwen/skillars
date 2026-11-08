package com.softropic.skillars.platform.admin.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GdprRequestRepository extends JpaRepository<GdprRequest, UUID> {

    // V172 scope-aware variants: a self-scoped request (playerId NULL) and a child-scoped request
    // (playerId set) are independent — each backed by its own partial unique index.
    boolean existsByUserIdAndPlayerIdIsNullAndRequestTypeAndStatusIn(
        Long userId, String requestType, List<String> statuses);

    boolean existsByPlayerIdAndRequestTypeAndStatusIn(Long playerId, String requestType, List<String> statuses);

    List<GdprRequest> findTop20ByUserIdAndPlayerIdIsNullOrderByCreatedAtDesc(Long userId);

    List<GdprRequest> findTop20ByUserIdAndPlayerIdOrderByCreatedAtDesc(Long userId, Long playerId);

    Page<GdprRequest> findByRequestTypeAndStatus(String requestType, String status, Pageable pageable);

    Page<GdprRequest> findByRequestType(String requestType, Pageable pageable);

    Page<GdprRequest> findByStatus(String status, Pageable pageable);

    // skillars-deferred-136 AC2: candidates for GdprErasureService.retryFailedErasures's sweep — past
    // the grace window (failedAt before the caller's cutoff) and under the retry cap. requestType is
    // filtered explicitly (story review) rather than relying on the currently-true-but-undocumented
    // invariant that only ERASURE failures ever get a failedAt stamp (GdprExportService's own failure
    // path never calls markFailed) — that invariant spans two files and is easy to silently break later.
    Page<GdprRequest> findByRequestTypeAndStatusAndFailedAtBeforeAndRetryCountLessThan(
        String requestType, String status, Instant failedBefore, int retryCountLessThan, Pageable pageable);

    List<GdprRequest> findByUserIdAndRequestTypeAndStatus(Long userId, String requestType, String status);

    @Modifying
    @Query("DELETE FROM GdprRequest r WHERE r.userId = :userId AND r.createdAt < :cutoff")
    int deleteExpiredByUserId(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);

    // ── Owner decision 2026-11-08 (PRV-03): what else an account erasure waits for ──────────────────────────────
    // Native SQL across schemas, so this module takes no dependency on payment or booking internals.

    /**
     * Unused, unexpired, unrevoked pack credits with this coach, or a pack purchase with them still in flight
     * (an attempt not yet settled: its pack row may still be written). Expired credits are forfeited by policy.
     */
    @Query(value = """
        SELECT EXISTS (
                   SELECT 1 FROM payment.session_pack_purchases p
                   WHERE p.coach_id = :coachId AND p.remaining_sessions > 0
                     AND p.revoked_at IS NULL AND p.expires_at > now())
            OR EXISTS (
                   SELECT 1 FROM payment.session_pack_purchase_attempts a
                   JOIN payment.session_pack_tiers t ON t.pack_tier_id = a.pack_tier_id
                   WHERE t.coach_id = :coachId AND a.status IN ('IN_PROGRESS', 'UNKNOWN', 'UNRECONCILED'))
        """, nativeQuery = true)
    boolean coachHasUnusedPackCredits(@Param("coachId") UUID coachId);

    /** The same for a family: credits held for any of these players, or a purchase in flight by this payer. */
    @Query(value = """
        SELECT EXISTS (
                   SELECT 1 FROM payment.session_pack_purchases p
                   WHERE p.player_id IN (:playerIds) AND p.remaining_sessions > 0
                     AND p.revoked_at IS NULL AND p.expires_at > now())
            OR EXISTS (
                   SELECT 1 FROM payment.session_pack_purchase_attempts a
                   WHERE (a.parent_id = :userId OR a.player_id IN (:playerIds))
                     AND a.status IN ('IN_PROGRESS', 'UNKNOWN', 'UNRECONCILED'))
        """, nativeQuery = true)
    boolean familyHasUnusedPackCredits(@Param("userId") Long userId, @Param("playerIds") java.util.Collection<Long> playerIds);

    /** A booking of this coach that can still be disputed: a disputable status reached after {@code since}. */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM booking.bookings b
            WHERE b.coach_id = :coachId AND b.status IN (:statuses)
              AND COALESCE(b.status_changed_at, b.updated_at) > :since)
        """, nativeQuery = true)
    boolean coachHasDisputableBooking(@Param("coachId") UUID coachId,
                                      @Param("statuses") java.util.Collection<String> statuses,
                                      @Param("since") Instant since);

    /** The same for a family's players. */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM booking.bookings b
            WHERE b.player_id IN (:playerIds) AND b.status IN (:statuses)
              AND COALESCE(b.status_changed_at, b.updated_at) > :since)
        """, nativeQuery = true)
    boolean familyHasDisputableBooking(@Param("playerIds") java.util.Collection<Long> playerIds,
                                       @Param("statuses") java.util.Collection<String> statuses,
                                       @Param("since") Instant since);
}
