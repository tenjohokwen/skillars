package com.softropic.skillars.platform.reviews.contract;

import java.util.UUID;

public record ReviewModerationResolvedEvent(
    UUID reviewId,
    UUID coachId,
    ReviewModerationStatus previousStatus,
    ReviewModerationStatus newStatus
) {}
