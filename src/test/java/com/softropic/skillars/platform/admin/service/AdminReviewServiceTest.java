package com.softropic.skillars.platform.admin.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.admin.repo.ReviewModerationLogRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.repo.CoachReview;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import com.softropic.skillars.platform.reviews.repo.ReviewFlagRepository;
import com.softropic.skillars.platform.reviews.service.CoachRatingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-131 AC2 Fix 8. No {@code AdminReviewServiceTest} existed before this story
 * (confirmed via {@code find}) — this is a new file. Pins that a review carrying open flags at the
 * moment it is approved/blocked gets a WARN naming the reviewId and the resolved count, closing the
 * pre-existing silent-wipe gap {@code ReviewFlagRepository.resolveAllOpenFlags} used to leave with no
 * log, alert, or event.
 */
@ExtendWith(MockitoExtension.class)
class AdminReviewServiceTest {

    @Mock private CoachReviewRepository reviewRepository;
    @Mock private ReviewFlagRepository reviewFlagRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private CoachRatingService coachRatingService;
    @Mock private ReviewModerationLogRepository moderationLogRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PessimisticLockRetryer lockRetryer;

    @InjectMocks
    private AdminReviewService service;

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long ADMIN_ID = 900L;

    private static CoachReview review(ReviewModerationStatus status) {
        CoachReview review = new CoachReview();
        review.setReviewId(REVIEW_ID);
        review.setCoachId(COACH_ID);
        review.setModerationStatus(status);
        return review;
    }

    /**
     * skillars-deferred-135 AC3: approveReview/blockReview now route their locked read through
     * lockRetryer.withBoundedRetry — mirrors GdprErasureServiceTest's own identical stubbing pattern.
     */
    @SuppressWarnings("unchecked")
    private void stubLockRetryerPassthrough() {
        when(lockRetryer.withBoundedRetry(any(String.class), any(java.util.function.Supplier.class)))
            .thenAnswer(inv -> inv.getArgument(1, java.util.function.Supplier.class).get());
    }

    @Test
    void approveReview_openFlagsResolved_logsWarnWithCount() {
        stubLockRetryerPassthrough();
        when(reviewRepository.findByIdForUpdateNoWait(REVIEW_ID))
            .thenReturn(Optional.of(review(ReviewModerationStatus.UNDER_REVIEW)));
        when(reviewFlagRepository.resolveAllOpenFlags(any(), any())).thenReturn(3);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(AdminReviewService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.approveReview(REVIEW_ID, ADMIN_ID);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .as("approving a review with open flags must WARN, naming the reviewId and the count")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains(REVIEW_ID.toString())
                    .contains("3 flag(s)");
            });
    }

    @Test
    void approveReview_noOpenFlags_doesNotLogWarn() {
        stubLockRetryerPassthrough();
        when(reviewRepository.findByIdForUpdateNoWait(REVIEW_ID))
            .thenReturn(Optional.of(review(ReviewModerationStatus.UNDER_REVIEW)));
        when(reviewFlagRepository.resolveAllOpenFlags(any(), any())).thenReturn(0);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(AdminReviewService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.approveReview(REVIEW_ID, ADMIN_ID);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .as("no open flags to silently wipe means no WARN")
            .noneSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void blockReview_openFlagsResolved_logsWarnWithCount() {
        stubLockRetryerPassthrough();
        when(reviewRepository.findByIdForUpdateNoWait(REVIEW_ID))
            .thenReturn(Optional.of(review(ReviewModerationStatus.PENDING)));
        when(reviewFlagRepository.resolveAllOpenFlags(any(), any())).thenReturn(2);

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(AdminReviewService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger.addAppender(logCapture);
        try {
            service.blockReview(REVIEW_ID, "policy violation", ADMIN_ID);
        } finally {
            serviceLogger.detachAppender(logCapture);
        }

        assertThat(logCapture.list)
            .as("blocking a review with open flags must WARN too — BLOCKED reviews likewise still "
                + "accept flags that can never act")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains(REVIEW_ID.toString())
                    .contains("2 flag(s)");
            });
    }
}
