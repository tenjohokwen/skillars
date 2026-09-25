package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final EntityManager entityManager;
    // skillars-deferred-135 AC3: converts updateReview/submitCoachResponse's own findByIdForUpdate
    // calls below to NOWAIT + bounded retry, mirroring ReviewFlagService.flag()'s own shipped pattern.
    private final PessimisticLockRetryer lockRetryer;

    /**
     * skillars-deferred-132 AC2 Fix 7: {@code @Transactional(REQUIRES_NEW)}, overriding this class's
     * own default (class-level {@code @Transactional}, {@code REQUIRED} propagation) for this method
     * only. This method's own catch below translates a {@code DataIntegrityViolationException} at
     * {@code saveAndFlush} into a clean {@code ALREADY_SUBMITTED} 4xx — safe only because Postgres has
     * already marked the underlying transaction rollback-only by the time that catch runs. Today that
     * is harmless because {@code ReviewResource} never wraps this call in its own transaction, making
     * this method's own {@code @Transactional} the outermost boundary; REQUIRES_NEW makes that true
     * unconditionally; regardless of what any future caller does, so a caught-and-translated DIVE can
     * never surface as {@code UnexpectedRollbackException} at an outer boundary. See this fix's own
     * story for the accepted tradeoffs (a second pooled connection per call; the row surviving an outer
     * rollback; a self-deadlock risk this method's own regression test is built to avoid).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            review = coachReviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // Code review 2026-09-23: mirrors ReviewFlagService.flag's identical Fix 6 —
            // uq_coach_reviews_author_coach is the only constraint on this insert that means "already
            // submitted" (V138__baseline_schema.sql:3080-3084); coach_reviews_rating_check and any
            // NOT NULL violation are different failures and must not be mislabeled as ALREADY_SUBMITTED.
            if (isAlreadySubmittedViolation(e)) {
                throw new OperationNotAllowedException(
                    "Review already submitted for this coach",
                    ReviewErrorCode.ALREADY_SUBMITTED);
            }
            throw e;
        }
        // AC1 (skillars-deferred-88): a freshly-created review keeps the default moderationEpoch = 0.
        eventPublisher.publishEvent(new ReviewSubmittedEvent(
            review.getReviewId(), coachId, authorId, rating, body, review.getModerationEpoch()));
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

        // AC1 (skillars-deferred-88): serialise the moderation-epoch bump. The findByReviewIdAndAuthorId
        // load above is unlocked and only backs the author-match / 365-day / status pre-checks, so an
        // unauthorised caller still gets AUTHOR_MISMATCH without ever taking a row lock (same
        // order-of-operations rationale as MessagingService.softDeleteMessage). Every mutation below
        // runs on the locked instance. Author identity is already confirmed by the pre-check, so a
        // missing row here can only mean a concurrent delete — a not-found condition, not an authz one.
        // skillars-deferred-135 AC3: NOWAIT + bounded retry, not a genuinely blocking wait — mirrors
        // ReviewFlagService.flag()'s own shipped pattern exactly.
        CoachReview locked = lockRetryer.withBoundedRetry("ReviewSubmissionService.updateReview",
            () -> coachReviewRepository.findByIdForUpdateNoWait(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId.toString())));
        // findByIdForUpdateNoWait is a JPQL query and the row is already managed from the unlocked load
        // above, so Hibernate takes the DB lock but returns the existing instance without refreshing
        // its fields. Without this refresh, two near-simultaneous edits would both read epoch N off a
        // stale instance and both publish N+1 (a lost update). Mirrors MessagingService.softDeleteMessage
        // / BookingService.createBookingRequest for the identical Hibernate identity-map gotcha.
        // Safe to call plain (no lock-timeout hint) after the NOWAIT read above: this re-requests the
        // SAME row lock this SAME transaction already holds — Postgres row locks are transaction-scoped,
        // so a same-transaction re-request never blocks, regardless of any other transaction's own
        // contention for the row.
        entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);

        // Re-run the moderation-status guard on the FRESH locked instance (review finding). The
        // pre-check above ran on the stale unlocked read; a concurrent admin BLOCK, a
        // ReviewFlagService.flag auto-hold, or a moderation-listener verdict committed between that
        // read and this lock must not be silently reset to PENDING by the edit below. Mirrors
        // MessagingService.softDeleteMessage re-checking getDeletedAt() after its own refresh.
        ReviewModerationStatus lockedStatus = locked.getModerationStatus();
        if (lockedStatus == ReviewModerationStatus.BLOCKED
                || lockedStatus == ReviewModerationStatus.UNDER_REVIEW) {
            throw new OperationNotAllowedException(
                "Review cannot be edited in its current moderation status",
                ReviewErrorCode.EDIT_NOT_PERMITTED);
        }

        locked.setRating(rating);
        locked.setBody(body);
        locked.setModerationStatus(ReviewModerationStatus.PENDING);
        locked.setLastModifiedAt(Instant.now());
        locked.setCoachResponseBody(null);
        locked.setCoachResponseAt(null);
        locked.setModerationEpoch(locked.getModerationEpoch() + 1);
        coachReviewRepository.save(locked);
        eventPublisher.publishEvent(new ReviewSubmittedEvent(
            locked.getReviewId(), locked.getCoachId(), authorId, rating, body, locked.getModerationEpoch()));
    }

    public void submitCoachResponse(UUID reviewId, UUID coachId, String responseBody) {
        // skillars-deferred-135 AC3: NOWAIT + bounded retry — see updateReview's own comment above.
        CoachReview review = lockRetryer.withBoundedRetry("ReviewSubmissionService.submitCoachResponse",
            () -> coachReviewRepository.findByIdForUpdateNoWait(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId.toString())));
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

    private static final String UNIQUE_AUTHOR_COACH_CONSTRAINT = "uq_coach_reviews_author_coach";

    /** Mirrors {@code ReviewFlagService.isUniqueFlaggerViolation}'s single-level unwrap pattern. */
    private static boolean isAlreadySubmittedViolation(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof org.hibernate.exception.ConstraintViolationException cve
            && UNIQUE_AUTHOR_COACH_CONSTRAINT.equals(cve.getConstraintName());
    }

    private void checkEligibility(UUID coachId, Long authorId) {
        // skillars-deferred-107 AC2: 0/neg → no review can ever be submitted (failFast). Clamps to 14 + WARN.
        // skillars-deferred-132 AC3 Fix 10: references the existing ConfigBounds constant's key
        // instead of the raw string literal — see ReviewFlagService's identical fix for why (future
        // typo-drift protection, not a fail-fast gap: ConfigStartupAssertion boot-protects by string
        // lookup either way). Bounds (1, 365) stay re-typed as literals, per convention.
        int windowDays = configService.getBoundedInt(
            ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS.key(), 14, 1, 365);
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
