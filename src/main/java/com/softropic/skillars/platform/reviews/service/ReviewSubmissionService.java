package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.AuthorRole;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.contract.ReviewSubmittedEvent;
import com.softropic.skillars.platform.reviews.contract.SubmitReviewResponse;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewSubmissionService {

    private final CoachReviewRepository coachReviewRepository;
    private final BookingRepository bookingRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigService configService;

    public SubmitReviewResponse submitReview(UUID coachId, Long authorId, String authorRoleStr,
                                             Integer rating, String body) {
        if (!coachProfileRepository.existsById(coachId)) {
            throw new ResourceNotFoundException("Coach", coachId.toString());
        }
        checkEligibility(coachId, authorId);
        if (coachReviewRepository.existsByAuthorIdAndCoachId(authorId, coachId)) {
            throw new OperationNotAllowedException(
                "Review already submitted for this coach",
                ReviewErrorCode.ALREADY_SUBMITTED);
        }
        AuthorRole authorRole;
        try {
            authorRole = AuthorRole.valueOf(authorRoleStr);
        } catch (IllegalArgumentException e) {
            throw new OperationNotAllowedException(
                "Role '" + authorRoleStr + "' is not permitted to submit reviews",
                ReviewErrorCode.AUTHOR_ROLE_NOT_ALLOWED);
        }
        CoachReview review = new CoachReview();
        review.setCoachId(coachId);
        review.setAuthorId(authorId);
        review.setAuthorRole(authorRole);
        review.setRating(rating);
        review.setBody(body);
        review.setModerationStatus(ReviewModerationStatus.PENDING);
        review.setLastModifiedAt(Instant.now());
        try {
            review = coachReviewRepository.save(review);
        } catch (DataIntegrityViolationException e) {
            throw new OperationNotAllowedException(
                "Review already submitted for this coach",
                ReviewErrorCode.ALREADY_SUBMITTED);
        }
        eventPublisher.publishEvent(
            new ReviewSubmittedEvent(review.getReviewId(), coachId, authorId, rating, body));
        return new SubmitReviewResponse(review.getReviewId());
    }

    public void updateReview(UUID reviewId, Long authorId, Integer rating, String body) {
        CoachReview review = coachReviewRepository.findByReviewIdAndAuthorId(reviewId, authorId)
            .orElseThrow(() -> new OperationNotAllowedException(
                "Review not found or caller is not the author",
                ReviewErrorCode.AUTHOR_MISMATCH));

        if (review.getLastModifiedAt().isAfter(Instant.now().minus(365, ChronoUnit.DAYS))) {
            throw new OperationNotAllowedException(
                "Review was modified within the last 365 days",
                ReviewErrorCode.UPDATE_TOO_SOON);
        }
        ReviewModerationStatus status = review.getModerationStatus();
        if (status == ReviewModerationStatus.BLOCKED || status == ReviewModerationStatus.UNDER_REVIEW) {
            throw new OperationNotAllowedException(
                "Review cannot be edited in its current moderation status",
                ReviewErrorCode.EDIT_NOT_PERMITTED);
        }
        checkEligibility(review.getCoachId(), authorId);

        review.setRating(rating);
        review.setBody(body);
        review.setModerationStatus(ReviewModerationStatus.PENDING);
        review.setLastModifiedAt(Instant.now());
        review.setCoachResponseBody(null);
        review.setCoachResponseAt(null);
        coachReviewRepository.save(review);
        eventPublisher.publishEvent(
            new ReviewSubmittedEvent(review.getReviewId(), review.getCoachId(), authorId, rating, body));
    }

    public void submitCoachResponse(UUID reviewId, UUID coachId, String responseBody) {
        CoachReview review = coachReviewRepository.findByIdForUpdate(reviewId)
            .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId.toString()));
        if (!review.getCoachId().equals(coachId)) {
            throw new OperationNotAllowedException(
                "Authenticated coach does not own this review",
                ReviewErrorCode.COACH_MISMATCH);
        }
        if (review.getModerationStatus() != ReviewModerationStatus.APPROVED) {
            throw new OperationNotAllowedException(
                "Coach response is only permitted on approved reviews",
                ReviewErrorCode.REVIEW_NOT_APPROVED);
        }
        if (review.getCoachResponseBody() != null) {
            throw new OperationNotAllowedException(
                "A response has already been submitted for this review",
                ReviewErrorCode.RESPONSE_ALREADY_SUBMITTED);
        }
        review.setCoachResponseBody(responseBody);
        review.setCoachResponseAt(Instant.now());
        coachReviewRepository.save(review);
    }

    private void checkEligibility(UUID coachId, Long authorId) {
        int windowDays = configService.getInt("reviews.submissionWindowDays", 14);
        Instant windowStart = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        boolean eligible = bookingRepository.existsRecentCompletedBookingByAuthor(
            coachId, authorId, windowStart);
        if (!eligible) {
            throw new OperationNotAllowedException(
                "No completed session within the submission window",
                ReviewErrorCode.NO_RECENT_SESSION);
        }
    }
}
