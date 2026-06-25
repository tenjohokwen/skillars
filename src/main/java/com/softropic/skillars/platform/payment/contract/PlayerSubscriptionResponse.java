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
        boolean cancelAtPeriodEnd) {}
