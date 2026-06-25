package com.softropic.skillars.platform.video.contract;

public interface PlayerSubscriptionQueryPort {

    /**
     * Returns true if the player has a YEARLY-tier subscription currently active.
     * Used by VideoLifecycleScheduler to pause the BLOCKED→ARCHIVED clock while yearly coverage is live (FR-PAY-008).
     */
    boolean hasActiveYearlySubscription(Long playerId);

    /**
     * Returns true if the player has ANY subscription currently active.
     * Used by VideoSubscriptionLifecycleListener to skip the BLOCKED transition when another sub is still live.
     */
    boolean hasAnyActiveSubscription(Long playerId);
}
