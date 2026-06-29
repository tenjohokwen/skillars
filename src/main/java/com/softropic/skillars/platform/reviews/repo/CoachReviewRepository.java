package com.softropic.skillars.platform.reviews.repo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CoachReviewRepository extends JpaRepository<CoachReview, UUID> {

    boolean existsByAuthorIdAndCoachId(Long authorId, UUID coachId);

    Optional<CoachReview> findByReviewIdAndAuthorId(UUID reviewId, Long authorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CoachReview r WHERE r.reviewId = :reviewId")
    Optional<CoachReview> findByIdForUpdate(@Param("reviewId") UUID reviewId);
}
