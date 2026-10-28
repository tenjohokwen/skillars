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
    USER_SUSPEND, USER_REACTIVATE, USER_LOGIN_UNLOCK
}
