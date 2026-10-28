package com.softropic.skillars.platform.reviews.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.reviews.contract.HeldReason;
import com.softropic.skillars.platform.reviews.contract.ReviewModerationStatus;
import com.softropic.skillars.platform.reviews.contract.ReviewSubmittedEvent;
import com.softropic.skillars.platform.reviews.repo.CoachReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/content-consistency-and-lost-updates: reviews whose async moderation verdict never landed
 * used to stay PENDING forever, outside the admin queue (UNDER_REVIEW only). Drives the real
 * scheduled bean through its Spring proxy, so {@link #releaseSchedulerLock} runs before every call.
 */
class ReviewModerationSweeperIT extends AbstractIntegrationTest {

    private static final String LOCK_NAME = "ReviewModerationSweeper_sweep";
    private static final UUID COACH_ID = UUID.randomUUID();

    @Autowired private ReviewModerationSweeper sweeper;
    @Autowired private CoachReviewRepository reviewRepository;
    @Autowired private ReviewModerationService reviewModerationService;

    private static final java.util.concurrent.atomic.AtomicLong NEXT_AUTHOR =
        new java.util.concurrent.atomic.AtomicLong(9_879_000_001L);

    private UUID seedReview(String status, Instant lastModifiedAt) {
        UUID id = UUID.randomUUID();
        long author = NEXT_AUTHOR.getAndIncrement();
        transactionTemplate.execute(tx -> {
            jdbcTemplate.update(
                "INSERT INTO reviews.coach_reviews (review_id, coach_id, author_id, author_role, rating, body, "
                    + "moderation_status, created_at, last_modified_at) VALUES (?, ?, ?, 'PARENT', 4, 'body', ?, ?, ?)",
                id, COACH_ID, author, status, Timestamp.from(lastModifiedAt), Timestamp.from(lastModifiedAt));
            return null;
        });
        return id;
    }

    private Map<String, Object> row(UUID reviewId) {
        return jdbcTemplate.queryForMap(
            "SELECT moderation_status, held_reason, last_modified_at FROM reviews.coach_reviews WHERE review_id = ?",
            reviewId);
    }

    @Test
    void stalePendingReview_isHeldForAdmin_freshAndResolvedAreUntouched() {
        Instant old = Instant.now().minus(Duration.ofHours(2));
        UUID stale = seedReview("PENDING", old);
        UUID fresh = seedReview("PENDING", Instant.now().minus(Duration.ofMinutes(1)));
        UUID approved = seedReview("APPROVED", old);

        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();

        Map<String, Object> staleRow = row(stale);
        assertThat(staleRow.get("moderation_status")).isEqualTo("UNDER_REVIEW");
        assertThat(staleRow.get("held_reason")).isEqualTo(HeldReason.MODERATION_TIMEOUT.name());
        // lastModifiedAt is the author's cooldown anchor and the admin queue's FIFO key: untouched.
        assertThat(((Timestamp) staleRow.get("last_modified_at")).toInstant())
            .isCloseTo(old, org.assertj.core.api.Assertions.within(Duration.ofMillis(1)));

        assertThat(row(fresh).get("moderation_status")).isEqualTo("PENDING");
        assertThat(row(approved).get("moderation_status")).isEqualTo("APPROVED");
        assertThat(row(approved).get("held_reason")).isNull();
    }

    @Test
    void conditionalHold_isNoOp_whenTheRowWasResolvedAfterTheSelect() {
        // Stands in for the select/update race: a verdict committed APPROVED after the sweeper's
        // select read the row as PENDING. The atomic UPDATE must not overwrite it.
        UUID reviewId = seedReview("APPROVED", Instant.now().minus(Duration.ofHours(2)));
        Integer updated = transactionTemplate.execute(tx -> reviewRepository.holdIfStillPending(
            reviewId, ReviewModerationStatus.PENDING, ReviewModerationStatus.UNDER_REVIEW,
            HeldReason.MODERATION_TIMEOUT, Instant.now()));
        assertThat(updated).isZero();
        assertThat(row(reviewId).get("moderation_status")).isEqualTo("APPROVED");
    }

    @Test
    void lateVerdict_afterTheHold_isDiscarded() {
        UUID reviewId = seedReview("PENDING", Instant.now().minus(Duration.ofHours(2)));
        releaseSchedulerLock(LOCK_NAME);
        sweeper.sweep();
        assertThat(row(reviewId).get("moderation_status")).isEqualTo("UNDER_REVIEW");

        // The lost verdict finally arrives (blank body -> APPROVED without calling Gemini). Its
        // PENDING guard must leave the human-review hold in place.
        reviewModerationService.handleReviewSubmitted(
            new ReviewSubmittedEvent(reviewId, COACH_ID, 1L, 4, " ", 0L));
        assertThat(row(reviewId).get("moderation_status")).isEqualTo("UNDER_REVIEW");
        assertThat(row(reviewId).get("held_reason")).isEqualTo(HeldReason.MODERATION_TIMEOUT.name());
    }
}
