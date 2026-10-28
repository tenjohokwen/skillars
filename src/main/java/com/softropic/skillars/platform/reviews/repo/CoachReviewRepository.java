package com.softropic.skillars.platform.reviews.repo;

import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachReviewRepository extends JpaRepository<CoachReview, UUID> {

    boolean existsByAuthorIdAndCoachId(Long authorId, UUID coachId);

    Optional<CoachReview> findByReviewIdAndAuthorId(UUID reviewId, Long authorId);

    // skillars-deferred-135 AC3: kept for the shared query shape only — every call site that used to
    // call this genuinely-blocking method now calls findByIdForUpdateNoWait below (wrapped in
    // PessimisticLockRetryer.withBoundedRetry) instead. Not deleted: PessimisticLockRetryerCallSiteAuditTest's
    // own exempt-repository list still documents blocking-only repositories elsewhere in this codebase
    // (VideoQuotaRepository, CoachPayoutRepository, MessageRepository) as a deliberate category, and a
    // genuinely blocking variant of this query may still be useful for a future call site that needs
    // the older tradeoff (see PessimisticLockRetryer's own Javadoc on why NOWAIT+retry isn't free —
    // it holds the connection while sleeping between attempts).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CoachReview r WHERE r.reviewId = :reviewId")
    Optional<CoachReview> findByIdForUpdate(@Param("reviewId") UUID reviewId);

    // skillars-deferred-132 AC1 Fix 2: originally NOWAIT-only for ReviewFlagService.flag() alone.
    // skillars-deferred-135 AC3: now the ONLY locked-read path for ALL SIX call sites in this module —
    // ReviewFlagService.flag() (the original caller), ReviewSubmissionService.updateReview/
    // submitCoachResponse, AdminReviewService.approveReview/blockReview, and
    // ReviewModerationService.handleReviewSubmitted's own AFTER_COMMIT listener. All six are wrapped in
    // PessimisticLockRetryer.withBoundedRetry, mirroring flag()'s own already-shipped pattern exactly.
    // findByIdForUpdate above is no longer called anywhere in this codebase — kept, not deleted, see
    // its own comment for why.
    //
    // <p>ReviewModerationService's own conversion was the one genuinely open safety question this AC
    // had to confirm empirically, not assume: its call site's own pre-conversion comment (superseded by
    // this one) warned the lock might need to be genuinely blocking, since the row can legitimately stay
    // contended for the duration of an admin's approveReview/blockReview transaction. A dedicated
    // GdprErasureServiceConcurrencyIT-style concurrency test (ReviewModerationServiceConcurrencyIT)
    // empirically confirmed NOWAIT+bounded-retry resolves correctly under realistic contention — see
    // that test's own Javadoc for the full reasoning (in short: ReviewModerationResolvedEvent has no
    // listener today so approveReview/blockReview's own transaction hold time is a handful of local DB
    // round-trips, well inside the default ~3.2s worst-case retry budget; and a retry-exhaustion failure
    // was already a safely-handled outcome on this path before this AC — handleReviewSubmitted's own
    // surrounding try/catch already swallowed a lock-acquisition failure, leaving the review PENDING for
    // later admin-queue resolution, exactly the same safe fallback NOWAIT-exhaustion now also reaches).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT r FROM CoachReview r WHERE r.reviewId = :reviewId")
    Optional<CoachReview> findByIdForUpdateNoWait(@Param("reviewId") UUID reviewId);

    // skillars-deferred-131 AC2 Fix 5: an unlocked scalar projection for flag()'s write-independent
    // guards (self-flag, coach-flags-own-profile), so neither fact is loaded via the entity — nothing
    // enters the persistence context before flag()'s own findByIdForUpdate below, which stays genuinely
    // this transaction's first load of the CoachReview entity. List<Object[]>, not Optional<Object[]>
    // — mirroring computeAggregates's own multi-column projection shape in this same file; an
    // Optional<Object[]> return type does not unwrap correctly here (Spring Data's null-handling
    // wrapper double-wraps the tuple), confirmed while implementing this fix.
    @Query("SELECT r.authorId, r.coachId FROM CoachReview r WHERE r.reviewId = :reviewId")
    List<Object[]> findAuthorAndCoachIdByReviewId(@Param("reviewId") UUID reviewId);

    long countByCoachIdAndModerationStatus(UUID coachId, ReviewModerationStatus status);

    @Query("SELECT AVG(r.rating) FROM CoachReview r " +
           "WHERE r.coachId = :coachId AND r.moderationStatus = :status")
    Double computeAverageRating(@Param("coachId") UUID coachId,
                                @Param("status") ReviewModerationStatus status);

    // Single atomic query — eliminates TOCTOU race between count and avg reads
    @Query("SELECT COUNT(r), AVG(r.rating) FROM CoachReview r " +
           "WHERE r.coachId = :coachId AND r.moderationStatus = :status")
    List<Object[]> computeAggregates(@Param("coachId") UUID coachId,
                                    @Param("status") ReviewModerationStatus status);

    Page<CoachReview> findByCoachIdAndModerationStatus(
        UUID coachId, ReviewModerationStatus status, Pageable pageable);

    Page<CoachReview> findByCoachId(UUID coachId, Pageable pageable);

    Optional<CoachReview> findByAuthorIdAndCoachId(Long authorId, UUID coachId);

    /**
     * {@code ReviewModerationSweeper}: ids of reviews stuck in PENDING since before {@code threshold}.
     * Anchored on {@code lastModifiedAt}, not {@code createdAt}: {@code updateReview} re-PENDs a review
     * and bumps lastModifiedAt in the same write, so an edit restarts the grace window.
     */
    @Query("SELECT r.reviewId FROM CoachReview r WHERE r.moderationStatus = :pending "
           + "AND r.lastModifiedAt < :threshold ORDER BY r.lastModifiedAt ASC")
    List<UUID> findPendingIdsOlderThan(@Param("pending") ReviewModerationStatus pending,
                                       @Param("threshold") Instant threshold,
                                       Pageable pageable);

    /**
     * {@code ReviewModerationSweeper}: atomic PENDING -> UNDER_REVIEW hold. The predicate is
     * re-evaluated by Postgres after it takes the row lock, so a verdict, an admin decision or an
     * author edit that committed after {@link #findPendingIdsOlderThan} selected this row makes this
     * a 0-row no-op instead of overwriting it. Deliberately does not touch {@code lastModifiedAt}
     * (that column is the author's edit-cooldown anchor and the admin queue's FIFO key).
     *
     * @return 1 if this call held the review, 0 if it was no longer a stale PENDING review
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CoachReview r SET r.moderationStatus = :underReview, r.heldReason = :reason "
           + "WHERE r.reviewId = :reviewId AND r.moderationStatus = :pending AND r.lastModifiedAt < :threshold")
    int holdIfStillPending(@Param("reviewId") UUID reviewId,
                           @Param("pending") ReviewModerationStatus pending,
                           @Param("underReview") ReviewModerationStatus underReview,
                           @Param("reason") HeldReason reason,
                           @Param("threshold") Instant threshold);

    Page<CoachReview> findByModerationStatusOrderByLastModifiedAtAsc(
        ReviewModerationStatus status, Pageable pageable);

    List<CoachReview> findAllByAuthorId(Long authorId);

    @Modifying
    @Query("DELETE FROM CoachReview r WHERE r.authorId = :authorId AND r.moderationStatus <> :approved")
    int deleteNonApprovedByAuthorId(@Param("authorId") Long authorId,
                                    @Param("approved") ReviewModerationStatus approved);

    // Sets author_id = 0 (sentinel for "deleted user") on APPROVED reviews — authorId is BIGINT, not UUID
    @Modifying
    @Query(value = "UPDATE reviews.coach_reviews SET author_id = 0 WHERE author_id = :authorId AND moderation_status = 'APPROVED'", nativeQuery = true)
    int anonymiseApprovedReviews(@Param("authorId") Long authorId);
}
