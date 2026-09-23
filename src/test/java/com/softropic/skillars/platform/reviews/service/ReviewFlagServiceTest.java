package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-131 AC2 Fixes 6 & 7. No {@code ReviewFlagServiceTest} existed before this story
 * (confirmed via {@code find}) — this is a new file, not an addition to an existing one.
 */
@ExtendWith(MockitoExtension.class)
class ReviewFlagServiceTest {

    @Mock private CoachReviewRepository reviewRepository;
    @Mock private ReviewFlagRepository reviewFlagRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private CoachRatingService coachRatingService;
    @Mock private ConfigService configService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReviewFlagService service;

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long AUTHOR_ID = 500L;
    private static final Long FLAGGER_ID = 600L;
    private static final Long COACH_USER_ID = 700L;

    /**
     * Fix 7: {@code flag(reviewId, null, ...)} must throw a clean exception, not NPE on
     * {@code flaggedBy.equals(...)}. The check runs before any repository call.
     */
    @Test
    void flag_nullFlaggedBy_throwsInvalidFlaggerNotNpe() {
        assertThatThrownBy(() -> service.flag(REVIEW_ID, null, ReviewFlagReason.FAKE_REVIEW, "details"))
            .isInstanceOf(OperationNotAllowedException.class)
            .satisfies(e -> assertThat(((OperationNotAllowedException) e).getErrorCode())
                .isEqualTo(ReviewErrorCode.INVALID_FLAGGER));

        verifyNoInteractions(reviewRepository, reviewFlagRepository, coachProfileRepository);
    }

    /**
     * Fix 6, negative branch: a {@code DataIntegrityViolationException} whose root cause names a
     * DIFFERENT constraint than {@code review_flags_unique_flagger} must propagate uncaught, not be
     * mapped to {@code ALREADY_FLAGGED}. The positive branch (the real Postgres exception shape for
     * the unique-flagger violation itself) is pinned by a Testcontainers IT instead — a Mockito
     * fixture built against the wrong exception shape would not catch that mismatch.
     */
    @Test
    void flag_dataIntegrityViolationOnUnrelatedConstraint_propagatesUncaught() {
        when(reviewRepository.findAuthorAndCoachIdByReviewId(REVIEW_ID))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {AUTHOR_ID, COACH_ID}));
        CoachProfile coachProfile = new CoachProfile();
        coachProfile.setUserId(COACH_USER_ID);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile));
        when(reviewFlagRepository.existsByReviewIdAndFlaggedBy(REVIEW_ID, FLAGGER_ID)).thenReturn(false);

        CoachReview review = new CoachReview();
        review.setReviewId(REVIEW_ID);
        review.setCoachId(COACH_ID);
        review.setAuthorId(AUTHOR_ID);
        when(reviewRepository.findByIdForUpdate(REVIEW_ID)).thenReturn(Optional.of(review));

        DataIntegrityViolationException unrelated = dive("review_flags_review_id_fkey");
        when(reviewFlagRepository.saveAndFlush(any())).thenThrow(unrelated);

        assertThatThrownBy(() -> service.flag(REVIEW_ID, FLAGGER_ID, ReviewFlagReason.FAKE_REVIEW, "details"))
            .isSameAs(unrelated);
    }

    private static DataIntegrityViolationException dive(String constraintName) {
        org.hibernate.exception.ConstraintViolationException cve =
            new org.hibernate.exception.ConstraintViolationException(
                "constraint violation", new SQLException("23505"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cve);
    }
}
