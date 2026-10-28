package com.softropic.skillars.platform.admin.contract;

/**
 * What can be appealed (FR-ADM-004 / FR-TSC-003): every enforcement action this platform takes
 * against a specific user that has an identifiable record. {@code subjectId} is the record's id.
 *
 * <ul>
 *   <li>{@link #RELIABILITY_STRIKE} — a coach's reliability strike ({@code coach_reliability_strikes.id});
 *       appellant: the struck coach. Overturning deletes the strike via
 *       {@code AdminCoachEnforcementService.deleteStrike}, which also de-escalates the coach's status.</li>
 *   <li>{@link #COACH_SUSPENSION} — an admin suspension ({@code coach_profiles.id}); appellant: that
 *       coach. Overturning reinstates via {@code AdminCoachEnforcementService.reinstateCoach}.</li>
 *   <li>{@link #REVIEW_BLOCK} — a review blocked by moderation ({@code coach_reviews.review_id});
 *       appellant: its author. Overturning approves it via {@code AdminReviewService.approveReview}.</li>
 *   <li>{@link #DISPUTE_RESOLUTION} — a resolved/dismissed booking dispute ({@code disputes.id}),
 *       UX Journey 5; appellant: the party who raised it. Overturning records the decision only — the
 *       money side of a dispute has no automatic reversal, so the admin applies any correction
 *       manually ({@code reversalApplied = false}).</li>
 * </ul>
 * Not appealable in this version: moderation-blocked messages (no sender-facing surface shows a
 * block) and video moderation (has its own approve/override flow).
 */
public enum AppealSubjectType {
    RELIABILITY_STRIKE,
    COACH_SUSPENSION,
    REVIEW_BLOCK,
    DISPUTE_RESOLUTION
}
