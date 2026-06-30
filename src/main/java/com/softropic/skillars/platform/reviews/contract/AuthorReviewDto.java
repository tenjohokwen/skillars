package com.softropic.skillars.platform.reviews.contract;

import java.time.Instant;
import java.util.UUID;

public record AuthorReviewDto(
    UUID reviewId,
    String authorRole,
    int rating,
    String body,
    String moderationStatus,
    String coachResponseBody,
    Instant coachResponseAt,
    Instant createdAt,
    Instant lastModifiedAt
) {}
