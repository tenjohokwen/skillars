package com.softropic.skillars.platform.reviews.contract;

import java.util.UUID;

/**
 * An admin approved or blocked a review ({@code AdminReviewService}). The author fields are resolved by
 * the publisher inside its transaction — the review author, or a minor player author's parent — and are
 * null when no address is reachable, in which case no outcome email is sent.
 */
public record ReviewModerationResolvedEvent(
    UUID reviewId,
    UUID coachId,
    ReviewModerationStatus previousStatus,
    ReviewModerationStatus newStatus,
    String authorEmail,
    String authorLangKey,
    String coachDisplayName
) {

    public ReviewModerationResolvedEvent(UUID reviewId, UUID coachId, ReviewModerationStatus previousStatus,
                                         ReviewModerationStatus newStatus) {
        this(reviewId, coachId, previousStatus, newStatus, null, null, null);
    }
}
