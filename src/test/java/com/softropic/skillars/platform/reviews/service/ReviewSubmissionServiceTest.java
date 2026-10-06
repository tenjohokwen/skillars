package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.repo.DisputeRepository;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-132 AC3 Fix 10. No {@code ReviewSubmissionServiceTest} existed before this story
 * (confirmed via {@code find}) — this is a new file, not an addition to an existing one. Its sole job
 * is the {@code Mockito verify(...)} drift-detector pin this fix's own test requirement calls for; it
 * does not attempt broader coverage of {@code ReviewSubmissionService} (that lives in
 * {@code ReviewSubmissionIT}/{@code ReviewSubmissionServiceConcurrencyIT}).
 */
@ExtendWith(MockitoExtension.class)
class ReviewSubmissionServiceTest {

    @Mock private CoachReviewRepository coachReviewRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConfigService configService;
    @Mock private EntityManager entityManager;
    // skillars-deferred-135 AC3: unused by this file's own sole test (submitReview throws before
    // reaching updateReview/submitCoachResponse's lockRetryer.withBoundedRetry calls) — present only
    // so @InjectMocks has a non-null value for ReviewSubmissionService's new constructor dependency.
    @Mock private PessimisticLockRetryer lockRetryer;
    // skillars-deferred-145: new constructor dependencies for the eligibility rework. Unused by
    // submitReview_readsMinSessionAgeConfigWithTheDocumentedKeyAndBoundsLiterally (
    // findQualifyingCompletedBookings is stubbed to return empty, so checkEligibility's loop body —
    // which is what would call these — never runs); present only so @InjectMocks has non-null values.
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private DisputeRepository disputeRepository;

    @InjectMocks
    private ReviewSubmissionService service;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final Long AUTHOR_ID = 500L;

    /**
     * Pins the exact key string {@code checkEligibility} (invoked from {@code submitReview}) passes to
     * {@code getBoundedInt} — mirrors {@code ReviewFlagServiceTest}'s own identical pin for its sibling
     * config key. Stubs {@code findQualifyingCompletedBookings} to return an empty list so
     * {@code checkEligibility} throws {@code NO_QUALIFYING_SESSION} right after the config read this
     * test exists to pin — the rest of {@code submitReview}'s own logic is out of scope here.
     */
    @Test
    void submitReview_readsMinSessionAgeConfigWithTheDocumentedKeyAndBoundsLiterally() {
        when(coachProfileRepository.existsById(COACH_ID)).thenReturn(true);
        when(configService.getBoundedInt(anyString(), any(Integer.class), any(Integer.class), any(Integer.class)))
            .thenReturn(7);
        when(bookingRepository.findQualifyingCompletedBookings(eq(COACH_ID), eq(AUTHOR_ID), any(), any()))
            .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.submitReview(COACH_ID, AUTHOR_ID, "PARENT", 5, "Great coach!"))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(configService).getBoundedInt(
            eq(ConfigBounds.REVIEWS_MIN_SESSION_AGE_DAYS.key()), eq(7), eq(1), eq(365));
    }

    /**
     * skillars-deferred-145 code review (Patch, 2026-10-06): mirrors the pin above for
     * {@code REVIEWS_UPDATE_COOLDOWN_DAYS}, which had no test pinning its call-site literals at all —
     * unlike its {@code REVIEWS_MIN_SESSION_AGE_DAYS} sibling. Stubs the found review's
     * {@code lastModifiedAt} to 1 day ago so {@code updateReview}'s cooldown check throws
     * {@code UPDATE_TOO_SOON} immediately after the config read this test exists to pin — the rest of
     * {@code updateReview}'s own logic is out of scope here.
     */
    @Test
    void updateReview_readsUpdateCooldownConfigWithTheDocumentedKeyAndBoundsLiterally() {
        CoachReview review = new CoachReview();
        review.setReviewId(REVIEW_ID);
        review.setCoachId(COACH_ID);
        review.setAuthorId(AUTHOR_ID);
        review.setLastModifiedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(coachReviewRepository.findByReviewIdAndAuthorId(REVIEW_ID, AUTHOR_ID))
            .thenReturn(Optional.of(review));
        when(configService.getBoundedInt(anyString(), any(Integer.class), any(Integer.class), any(Integer.class)))
            .thenReturn(30);

        assertThatThrownBy(() -> service.updateReview(REVIEW_ID, AUTHOR_ID, 5, "Updated"))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(configService).getBoundedInt(
            eq(ConfigBounds.REVIEWS_UPDATE_COOLDOWN_DAYS.key()), eq(30), eq(1), eq(365));
    }
}
