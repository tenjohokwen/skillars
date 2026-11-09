package com.softropic.skillars.platform.admin.contract;

import java.util.UUID;

/**
 * Owner decision 2026-11-08 (Pitchside ADM-09): an admin decided an appeal, and the appellant is told the outcome
 * and the admin's note (APPEAL_UPHELD / APPEAL_OVERTURNED). Published by {@code AppealService.decide} inside the
 * decision's transaction; the recipient is resolved there (a minor player's parent instead of the child).
 *
 * @param subjectType what was appealed (RELIABILITY_STRIKE, COACH_SUSPENSION, REVIEW_BLOCK, DISPUTE_RESOLUTION)
 * @param recipient   null when the appellant has no reachable address: the listener then skips the email
 */
public record AppealDecidedEvent(
    UUID appealId,
    AppealStatus outcome,
    AppealSubjectType subjectType,
    String decisionNote,
    /** For OVERTURNED: whether the original decision was actually undone (false for a dispute resolution, a strike
     *  already gone, a coach since re-suspended…). Null for UPHELD. */
    Boolean reversalApplied,
    AdminNotificationRecipient recipient) {
}
