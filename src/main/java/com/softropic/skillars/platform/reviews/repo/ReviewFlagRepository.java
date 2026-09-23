package com.softropic.skillars.platform.reviews.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReviewFlagRepository extends JpaRepository<ReviewFlag, UUID> {

    boolean existsByReviewIdAndFlaggedBy(UUID reviewId, Long flaggedBy);

    long countByReviewIdAndResolvedAtIsNull(UUID reviewId);

    long countByReviewId(UUID reviewId);

    List<ReviewFlag> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);

    // skillars-deferred-131 AC2 Fix 8: returns the row count the @Modifying JPQL UPDATE already
    // computes, instead of void, so callers can WARN-log when flags cast during moderation are about
    // to be silently wiped with no other record anything was ever flagged — no extra query, no window
    // between a separate count and this update. clearAutomatically = true is implemented by Spring
    // Data as entityManager.clear() — it detaches the WHOLE persistence context after this call, not
    // just any managed ReviewFlag, which includes the CoachReview both call sites (AdminReviewService
    // .approveReview/blockReview) already loaded via findByIdForUpdate. Harmless at both call sites
    // today regardless: CoachReview has no mapped associations, so its already-loaded fields (e.g.
    // getCoachId()) read fine off the now-detached instance — but a future caller adding a lazy
    // association access after this call would hit a LazyInitializationException, not a stale read.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewFlag f SET f.resolvedAt = :resolvedAt WHERE f.reviewId = :reviewId AND f.resolvedAt IS NULL")
    int resolveAllOpenFlags(@Param("reviewId") UUID reviewId, @Param("resolvedAt") Instant resolvedAt);
}
