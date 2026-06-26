package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.security.contract.MessagingPolicy;

public enum AgeMessagingPolicy {
    PROHIBITED,      // U10: player blocked, parent is primary participant
    PARENT_MANAGED,  // AGE_10_12: same as PROHIBITED for player
    SUPERVISED,      // AGE_13_17: player can send, parent has oversight
    UNRESTRICTED;    // ADULT: player + coach only; parent excluded

    public static AgeMessagingPolicy from(MessagingPolicy policy) {
        if (!policy.canMessage()) return PROHIBITED;
        if (!policy.directAllowed()) return PARENT_MANAGED;
        if (policy.parentVisible()) return SUPERVISED;
        return UNRESTRICTED;
    }

    /** True if player role is blocked from sending in this conversation. */
    public boolean playerIsBlocked() { return this == PROHIBITED || this == PARENT_MANAGED; }

    /** True if parent role is blocked from sending (adult player). */
    public boolean parentIsBlocked() { return this == UNRESTRICTED; }

    /** True if parent has oversight visibility (oversight or primary). */
    public boolean parentHasAccess() { return this != UNRESTRICTED; }

    /** True if parent is the PRIMARY PARTICIPANT (not just an observer). */
    public boolean parentIsPrimary() { return this == PROHIBITED || this == PARENT_MANAGED; }

    /** True if the conversation should be visible in the player's own conversation list. */
    public boolean visibleToPlayer() { return this == SUPERVISED || this == UNRESTRICTED; }
}
