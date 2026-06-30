package com.softropic.skillars.platform.reviews.repo;

import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoachReviewRepository extends JpaRepository<CoachReview, UUID> {

    boolean existsByAuthorIdAndCoachId(Long authorId, UUID coachId);

    Optional<CoachReview> findByReviewIdAndAuthorId(UUID reviewId, Long authorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CoachReview r WHERE r.reviewId = :reviewId")
    Optional<CoachReview> findByIdForUpdate(@Param("reviewId") UUID reviewId);

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
