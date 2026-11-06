package com.softropic.skillars.platform.payment.contract;

import java.time.Instant;
import java.util.UUID;

public record CoachSubscriptionResponse(
        UUID subscriptionId,
        String tier,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        /*
         * A paid downgrade waiting for the period end (null when none): the plan stays on {@code tier} until
         * {@code pendingEffectiveAt}. Re-selecting {@code tier} through change-tier cancels it.
         */
        String pendingTier,
        Instant pendingEffectiveAt) {}
