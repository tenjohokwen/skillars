package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
import com.softropic.skillars.platform.reviews.contract.ReviewFlaggedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlag;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReviewFlagService {

    private final CoachReviewRepository reviewRepository;
    private final ReviewFlagRepository reviewFlagRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachRatingService coachRatingService;
    private final ConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    public UUID flag(UUID reviewId, Long flaggedBy, ReviewFlagReason reason, String details) {
        CoachReview review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Review not found", ReviewErrorCode.REVIEW_NOT_FOUND));

        if (review.getAuthorId().equals(flaggedBy)) {
            throw new OperationNotAllowedException(
                "Cannot flag your own review", ReviewErrorCode.CANNOT_FLAG_OWN_REVIEW);
        }

        coachProfileRepository.findById(review.getCoachId()).ifPresent(profile -> {
            if (flaggedBy.equals(profile.getUserId())) {
                throw new OperationNotAllowedException(
                    "Coach cannot flag reviews of their own profile",
                    ReviewErrorCode.CANNOT_FLAG_OWN_COACHED_REVIEW);
            }
        });

        if (reviewFlagRepository.existsByReviewIdAndFlaggedBy(reviewId, flaggedBy)) {
            throw new OperationNotAllowedException(
                "You have already flagged this review", ReviewErrorCode.ALREADY_FLAGGED);
        }

        ReviewFlag flag = new ReviewFlag();
        flag.setReviewId(reviewId);
        flag.setFlaggedBy(flaggedBy);
        flag.setReason(reason);
        flag.setDetails(details);
        try {
            reviewFlagRepository.saveAndFlush(flag);
        } catch (DataIntegrityViolationException e) {
            // unique index review_flags_unique_flagger(review_id, flagged_by) violated by concurrent request
            throw new OperationNotAllowedException(
                "You have already flagged this review", ReviewErrorCode.ALREADY_FLAGGED);
        }

        long openFlagCount = reviewFlagRepository.countByReviewIdAndResolvedAtIsNull(reviewId);
        int threshold = configService.getInt("reviews.autoHoldFlagThreshold", 3);

        boolean autoHeld = false;
        if (openFlagCount >= threshold && review.getModerationStatus() == ReviewModerationStatus.APPROVED) {
            review.setModerationStatus(ReviewModerationStatus.UNDER_REVIEW);
            review.setHeldReason(HeldReason.FLAG_THRESHOLD);
            review.setLastModifiedAt(Instant.now());
            reviewRepository.save(review);
            coachRatingService.recompute(review.getCoachId());
            autoHeld = true;
        }

        long totalFlagCount = reviewFlagRepository.countByReviewId(reviewId);
        eventPublisher.publishEvent(
            new ReviewFlaggedEvent(reviewId, review.getCoachId(), totalFlagCount, autoHeld));

        return flag.getFlagId();
    }
}
