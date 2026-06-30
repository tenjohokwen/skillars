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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewFlag f SET f.resolvedAt = :resolvedAt WHERE f.reviewId = :reviewId AND f.resolvedAt IS NULL")
    void resolveAllOpenFlags(@Param("reviewId") UUID reviewId, @Param("resolvedAt") Instant resolvedAt);
}
