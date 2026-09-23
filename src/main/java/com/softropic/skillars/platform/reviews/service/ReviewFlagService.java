package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
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
        // skillars-deferred-130 AC1 Fix 1: locked as this transaction's first read, mirroring
        // ReviewSubmissionService.updateReview / ReviewModerationService.handleReviewSubmitted /
        // AdminReviewService.approveReview+blockReview — every other writer of
        // CoachReview.moderationStatus in this module takes this lock before mutating. The real
        // trigger this closes: ReviewSubmissionService.updateReview is the only status-writer that
        // never calls ReviewFlagRepository.resolveAllOpenFlags, so an unlocked read here could
        // observe a stale APPROVED snapshot, then have its full-row save() below clobber a
        // concurrent updateReview's PENDING/rating/body/epoch commit. No entityManager.refresh is
        // needed — this is the first read of the row, not a re-lock of an already-loaded instance.
        CoachReview review = reviewRepository.findByIdForUpdate(reviewId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Review not found", ReviewErrorCode.REVIEW_NOT_FOUND));

        if (review.getAuthorId().equals(flaggedBy)) {
            throw new OperationNotAllowedException(
                "Cannot flag your own review", ReviewErrorCode.CANNOT_FLAG_OWN_REVIEW);
        }

        CoachProfile coachProfile = coachProfileRepository.findById(review.getCoachId())
            .orElseThrow(() -> new OperationNotAllowedException(
                "Coach profile not found for review", ReviewErrorCode.COACH_PROFILE_MISSING));
        if (flaggedBy.equals(coachProfile.getUserId())) {
            throw new OperationNotAllowedException(
                "Coach cannot flag reviews of their own profile",
                ReviewErrorCode.CANNOT_FLAG_OWN_COACHED_REVIEW);
        }

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

        // The status guard below runs on the row locked above (skillars-deferred-130 AC1 Fix 1). The
        // count read here is NOT itself locked (it reads reviews.review_flags, a different table from
        // the coach_reviews row this method holds FOR UPDATE) — its consistency is convention-based,
        // not mechanically enforced: it holds only because this method and its two siblings
        // (AdminReviewService.approveReview/blockReview) all happen to take the coach_reviews lock
        // before touching review_flags, so nothing else can be resolving flags concurrently while this
        // count runs. A stale pre-lock count could otherwise justify an auto-hold the fresh count no
        // longer supports (e.g. an admin resolved the flags in the window between an unlocked read and
        // this lock) — code review 2026-09-23.
        long openFlagCount = reviewFlagRepository.countByReviewIdAndResolvedAtIsNull(reviewId);
        // skillars-deferred-107 AC2: 0 → the first flag on any review auto-holds it. Clamps to 3 + WARN.
        int threshold = configService.getBoundedInt("reviews.autoHoldFlagThreshold", 3, 1, 1000);

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
