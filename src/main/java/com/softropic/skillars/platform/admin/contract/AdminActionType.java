package com.softropic.skillars.platform.admin.contract;

public enum AdminActionType {
    MESSAGE_APPROVE, MESSAGE_BLOCK, CONVERSATION_UNBLOCK,
    REVIEW_APPROVE, REVIEW_BLOCK,
    COACH_SUSPEND, COACH_REINSTATE,
    COACH_STRIKE_ISSUED, COACH_STRIKE_DELETED,
    DISPUTE_RESOLVE,
    BOOKING_FORCE_COMPLETE,
    // V171: admin console account enforcement + coach verification tier.
    COACH_TIER_CHANGE,
    USER_SUSPEND, USER_REACTIVATE, USER_LOGIN_UNLOCK,
    // V183 (B6): a dismissal is no longer audited as DISPUTE_RESOLVE; appeal decisions are audited.
    DISPUTE_DISMISS, APPEAL_UPHOLD, APPEAL_OVERTURN,
    // V183 (B6): a system child-safety suspension of a coach profile (CSAM match), admin_id = AdminActionLog.SYSTEM_ACTOR_ID.
    COACH_SAFETY_HOLD,
    // V189 (C6): an admin cancelled a single booking (CANCEL_ADMIN, full refund, no strike) from the admin
    // bookings page. referenceId = the booking id; the reason carries the admin's note and the outcome.
    BOOKING_ADMIN_CANCEL
}
