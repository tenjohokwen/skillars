package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewErrorCode;
import com.softropic.skillars.platform.reviews.contract.ReviewFlagReason;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.instancio.Instancio;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
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
    @Mock private PessimisticLockRetryer lockRetryer;

    @InjectMocks
    private ReviewFlagService service;

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long AUTHOR_ID = 500L;
    private static final Long FLAGGER_ID = 600L;
    private static final Long COACH_USER_ID = 700L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // skillars-deferred-132 AC1 Fix 2: flag()'s own lock now runs through PessimisticLockRetryer
        // (NOWAIT + retry). Executed for real, mirroring every other lockRetryer mock in this codebase
        // (e.g. PlaybackServiceTest) — these unit tests are not exercising the retry loop itself,
        // PessimisticLockRetryerTest owns that.
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> inv.getArgument(1, Supplier.class).get());
    }

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
        CoachProfile coachProfile = Instancio.of(CoachProfile.class)
            .set(field(CoachProfile::getUserId), COACH_USER_ID)
            .create();
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile));
        when(reviewFlagRepository.existsByReviewIdAndFlaggedBy(REVIEW_ID, FLAGGER_ID)).thenReturn(false);

        CoachReview review = Instancio.of(CoachReview.class)
            .set(field(CoachReview::getReviewId), REVIEW_ID)
            .set(field(CoachReview::getCoachId), COACH_ID)
            .set(field(CoachReview::getAuthorId), AUTHOR_ID)
            .create();
        when(reviewRepository.findByIdForUpdateNoWait(REVIEW_ID)).thenReturn(Optional.of(review));

        DataIntegrityViolationException unrelated = dive("review_flags_review_id_fkey");
        when(reviewFlagRepository.saveAndFlush(any())).thenThrow(unrelated);

        assertThatThrownBy(() -> service.flag(REVIEW_ID, FLAGGER_ID, ReviewFlagReason.FAKE_REVIEW, "details"))
            .isSameAs(unrelated);
    }

    /**
     * skillars-deferred-132 AC3 Fix 10. Pins the exact key string {@code flag()} passes to
     * {@code getBoundedInt} — a {@code Mockito verify(...)} drift detector mirroring
     * {@code GdprErasureServiceTest}'s own established pattern, per {@code ConfigBounds}'s own
     * documented convention. The bounds (1, 1000) stay re-typed as literals deliberately.
     */
    @Test
    void flag_readsAutoHoldFlagThresholdConfigWithTheDocumentedKeyAndBoundsLiterally() {
        when(reviewRepository.findAuthorAndCoachIdByReviewId(REVIEW_ID))
            .thenReturn(java.util.List.<Object[]>of(new Object[] {AUTHOR_ID, COACH_ID}));
        CoachProfile coachProfile = Instancio.of(CoachProfile.class)
            .set(field(CoachProfile::getUserId), COACH_USER_ID)
            .create();
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coachProfile));
        when(reviewFlagRepository.existsByReviewIdAndFlaggedBy(REVIEW_ID, FLAGGER_ID)).thenReturn(false);

        CoachReview review = Instancio.of(CoachReview.class)
            .set(field(CoachReview::getReviewId), REVIEW_ID)
            .set(field(CoachReview::getCoachId), COACH_ID)
            .set(field(CoachReview::getAuthorId), AUTHOR_ID)
            .create();
        when(reviewRepository.findByIdForUpdateNoWait(REVIEW_ID)).thenReturn(Optional.of(review));
        when(configService.getBoundedInt(anyString(), any(Integer.class), any(Integer.class), any(Integer.class)))
            .thenReturn(3);

        service.flag(REVIEW_ID, FLAGGER_ID, ReviewFlagReason.FAKE_REVIEW, "details");

        verify(configService).getBoundedInt(
            eq(ConfigBounds.REVIEWS_AUTO_HOLD_FLAG_THRESHOLD.key()), eq(3), eq(1), eq(1000));
    }

    private static DataIntegrityViolationException dive(String constraintName) {
        org.hibernate.exception.ConstraintViolationException cve =
            new org.hibernate.exception.ConstraintViolationException(
                "constraint violation", new SQLException("23505"), constraintName);
        return new DataIntegrityViolationException("could not execute statement", cve);
    }
}
