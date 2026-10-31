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
    Instant lastModifiedAt,
    // feat/security-s06-related-party: true when this review is not published or counted because the author's account
    // appears related to the coach. A flag only — never which signal matched.
    boolean relatedPartyHidden
) {}
