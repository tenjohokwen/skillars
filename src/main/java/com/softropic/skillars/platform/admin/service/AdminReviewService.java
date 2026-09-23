package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.repo.ReviewModerationLog;
import com.softropic.skillars.platform.admin.repo.ReviewModerationLogRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.AdminReviewQueueEntryDto;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagDto;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationResolvedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.reviews.service.CoachRatingService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReviewService {

    private final CoachReviewRepository reviewRepository;
    private final ReviewFlagRepository reviewFlagRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachRatingService coachRatingService;
    private final ReviewModerationLogRepository moderationLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<AdminReviewQueueEntryDto> getUnderReviewQueue(int page) {
        Pageable p = PageRequest.of(Math.max(0, page), 20);
        Page<CoachReview> reviews = reviewRepository.findByModerationStatusOrderByLastModifiedAtAsc(
            ReviewModerationStatus.UNDER_REVIEW, p);
        return reviews.map(review -> {
            long flagCount = reviewFlagRepository.countByReviewId(review.getReviewId());
            List<ReviewFlagDto> flags = reviewFlagRepository
                .findByReviewIdOrderByCreatedAtAsc(review.getReviewId())
                .stream()
                .map(f -> new ReviewFlagDto(f.getReason().name(), f.getDetails(), f.getCreatedAt()))
                .toList();
            String heldReason = review.getHeldReason() != null ? review.getHeldReason().name() : null;
            String coachName = coachProfileRepository.findById(review.getCoachId())
                .map(cp -> cp.getDisplayName())
                .orElse("Unknown");
            return new AdminReviewQueueEntryDto(
                review.getReviewId(),
                review.getCoachId(),
                coachName,
                review.getAuthorRole().name(),
                review.getRating(),
                review.getBody(),
                review.getCreatedAt(),
                review.getLastModifiedAt(),
                heldReason,
                flagCount,
                flags);
        });
    }

    @Transactional
    public void approveReview(UUID reviewId, Long adminId) {
        // Pessimistic read, not findById: CoachReview has no @Version and review_moderation_log has
        // no unique constraint beyond its PK, so a plain check-then-act loses the admin-double-click
        // race — both callers would read UNDER_REVIEW and each write a log row + event. This is the
        // first read of the row in this method, so the locked query returns fresh state (contrast
        // BookingService.createBookingRequest, where an earlier findById makes the entity managed
        // and the later locked read returns stale in-memory state).
        CoachReview review = reviewRepository.findByIdForUpdate(reviewId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found", "coach_review"));
        ReviewModerationStatus previousStatus = review.getModerationStatus();

        if (previousStatus == ReviewModerationStatus.APPROVED) {
            throw new OperationNotAllowedException("Review already approved", ReviewErrorCode.ALREADY_APPROVED);
        }

        review.setModerationStatus(ReviewModerationStatus.APPROVED);
        review.setHeldReason(null);
        review.setLastModifiedAt(Instant.now());
        reviewRepository.save(review);

        // skillars-deferred-131 AC2 Fix 8: flags cast while a review is PENDING/UNDER_REVIEW
        // accumulate, are event-published as real, then were silently wiped here with no log or
        // alert — the review's final state carried openFlagCount == 0 with no record anything was
        // ever flagged. WARN, not silently resolve, when this call actually wipes open flags.
        int resolvedFlagCount = reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now());
        if (resolvedFlagCount > 0) {
            log.warn("Approving review with open flags — {} flag(s) auto-resolved: reviewId={}",
                resolvedFlagCount, reviewId);
        }
        coachRatingService.recompute(review.getCoachId());

        ReviewModerationLog entry = new ReviewModerationLog();
        entry.setReviewId(reviewId);
        entry.setAdminId(adminId);
        entry.setAction("APPROVED");
        moderationLogRepository.save(entry);

        eventPublisher.publishEvent(
            new ReviewModerationResolvedEvent(reviewId, review.getCoachId(),
                previousStatus, ReviewModerationStatus.APPROVED));
        log.info("Review approved: reviewId={}, adminId={}", reviewId, adminId);
    }

    @Transactional
    public void blockReview(UUID reviewId, String reason, Long adminId) {
        // Pessimistic read, not findById: same admin-double-click race as approveReview above.
        // This is the first read of the row in this method, so the locked query returns fresh state.
        CoachReview review = reviewRepository.findByIdForUpdate(reviewId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found", "coach_review"));
        ReviewModerationStatus previousStatus = review.getModerationStatus();

        if (previousStatus == ReviewModerationStatus.BLOCKED) {
            throw new OperationNotAllowedException("Review already blocked", ReviewErrorCode.ALREADY_BLOCKED);
        }

        review.setModerationStatus(ReviewModerationStatus.BLOCKED);
        review.setHeldReason(null);
        review.setLastModifiedAt(Instant.now());
        reviewRepository.save(review);

        // skillars-deferred-131 AC2 Fix 8: BLOCKED reviews likewise still accept flags that can never
        // act — same silent-wipe risk as approveReview above.
        int resolvedFlagCount = reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now());
        if (resolvedFlagCount > 0) {
            log.warn("Blocking review with open flags — {} flag(s) auto-resolved: reviewId={}",
                resolvedFlagCount, reviewId);
        }

        if (previousStatus == ReviewModerationStatus.APPROVED) {
            coachRatingService.recompute(review.getCoachId());
        }

        ReviewModerationLog entry = new ReviewModerationLog();
        entry.setReviewId(reviewId);
        entry.setAdminId(adminId);
        entry.setAction("BLOCKED");
        entry.setReason(reason);
        moderationLogRepository.save(entry);

        eventPublisher.publishEvent(
            new ReviewModerationResolvedEvent(reviewId, review.getCoachId(),
                previousStatus, ReviewModerationStatus.BLOCKED));
        log.info("Review blocked: reviewId={}, adminId={}", reviewId, adminId);
    }
}
