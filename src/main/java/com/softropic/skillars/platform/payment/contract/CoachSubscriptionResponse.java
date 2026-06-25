package com.softropic.skillars.platform.payment.contract;

import java.time.Instant;
import java.util.UUID;

public record CoachSubscriptionResponse(
        UUID subscriptionId,
        String tier,
        String status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd) {}
