package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.admin.repo.ReviewModerationLog;
import com.softropic.skillars.platform.admin.repo.ReviewModerationLogRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.AdminReviewQueueEntryDto;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagDto;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationResolvedEvent;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.reviews.service.CoachRatingService;
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
        CoachReview review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found", "coach_review"));
        ReviewModerationStatus previousStatus = review.getModerationStatus();

        review.setModerationStatus(ReviewModerationStatus.APPROVED);
        review.setHeldReason(null);
        review.setLastModifiedAt(Instant.now());
        reviewRepository.save(review);

        reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now());
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
        CoachReview review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new ResourceNotFoundException("Review not found", "coach_review"));
        ReviewModerationStatus previousStatus = review.getModerationStatus();

        review.setModerationStatus(ReviewModerationStatus.BLOCKED);
        review.setHeldReason(null);
        review.setLastModifiedAt(Instant.now());
        reviewRepository.save(review);

        reviewFlagRepository.resolveAllOpenFlags(reviewId, Instant.now());

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
