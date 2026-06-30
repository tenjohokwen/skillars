package com.softropic.skillars.platform.reviews.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminReviewQueueEntryDto(
    UUID reviewId,
    UUID coachId,
    String coachName,
    String authorRole,
    int rating,
    String body,
    Instant createdAt,
    Instant lastModifiedAt,
    String heldReason,
    long flagCount,
    List<ReviewFlagDto> flags
) {}
