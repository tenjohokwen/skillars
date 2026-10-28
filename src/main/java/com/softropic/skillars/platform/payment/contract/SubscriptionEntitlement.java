package com.softropic.skillars.platform.payment.contract;

import java.util.Set;

/**
 * fix/payment-subscription-entitlements: the single rule for whether a stored subscription tier is
 * actually granted. A paid tier counts only while the subscription is ACTIVE or TRIALLING, or PAST_DUE
 * (Stripe is still retrying; SubscriptionService.checkPastDueGracePeriod cancels it once the grace
 * period runs out — unchanged grace semantics). INCOMPLETE / UNPAID / PAUSED / CANCELLED fall back to
 * the free tier. Before this, every entitlement reader used the stored tier regardless of status.
 */
public final class SubscriptionEntitlement {

    public static final String COACH_FREE_TIER = "SCOUT";
    /** The player fallback tier (also the default/quota tier for players with no subscription). */
    public static final String PLAYER_FREE_TIER = "ATHLETE";

    /** Statuses a mutation that charges or schedules against the live Stripe subscription requires. */
    public static final Set<String> BILLING_HEALTHY_STATUSES = Set.of("ACTIVE", "TRIALLING");

    public static final Set<String> ENTITLED_STATUSES = Set.of("ACTIVE", "TRIALLING", "PAST_DUE");

    private SubscriptionEntitlement() {
    }

    public static boolean isEntitled(String status) {
        return status != null && ENTITLED_STATUSES.contains(status);
    }

    public static String effectiveCoachTier(String storedTier, String status) {
        return isEntitled(status) && storedTier != null ? storedTier : COACH_FREE_TIER;
    }

    public static String effectivePlayerTier(String storedTier, String status) {
        return isEntitled(status) && storedTier != null ? storedTier : PLAYER_FREE_TIER;
    }
}
