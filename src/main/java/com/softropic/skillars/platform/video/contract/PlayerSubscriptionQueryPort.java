package com.softropic.skillars.platform.video.contract;

import java.util.UUID;

public interface PlayerSubscriptionQueryPort {

    /**
     * Returns true if the player has a YEARLY-tier subscription currently active.
     * Used by VideoLifecycleScheduler to pause the BLOCKED→ARCHIVED clock while yearly coverage is live (FR-PAY-008).
     * NOTE: Epic 7 will move the adapter from platform.booking to platform.payment.
     */
    boolean hasActiveYearlySubscription(UUID playerId);

    /**
     * Returns true if the player has ANY subscription currently active.
     * Used by VideoSubscriptionLifecycleListener to skip the BLOCKED transition when another sub is still live.
     */
    boolean hasAnyActiveSubscription(UUID playerId);
}
