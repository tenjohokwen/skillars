package com.softropic.skillars.platform.reviews.contract;

import java.util.UUID;

/**
 * Published by {@code ReviewSubmissionService.submitReview} / {@code .updateReview} immediately after
 * the row is set to {@code PENDING}. Consumed by {@code ReviewModerationService} at
 * {@code AFTER_COMMIT}.
 *
 * <p>{@code moderationEpoch} (story skillars-deferred-88 AC1) is the row's monotonic moderation
 * counter captured at publish time. A newly-created review publishes {@code 0}; each
 * {@code updateReview} bumps it under the row lock. {@code ReviewModerationService} discards any
 * verdict whose event epoch no longer matches the row's current epoch (a superseded edit).
 */
public record ReviewSubmittedEvent(
    UUID reviewId,
    UUID coachId,
    Long authorId,
    int rating,
    String body,
    long moderationEpoch) {}
