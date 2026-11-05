package com.softropic.skillars.platform.payment.contract;

import java.time.Instant;
import java.util.UUID;

public record PlayerSubscriptionResponse(
        UUID subscriptionId,
        Long playerId,
        String tier,
        String billingInterval,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        /*
         * A Stripe subscription backs this plan, so a new tier is a change of it, not a fresh subscribe.
         * The tier alone cannot say so: ATHLETE is both the free fallback and a priced tier (a scheduled
         * downgrade can leave a paying player on it).
         */
        boolean subscribed) {}
