package com.softropic.skillars.platform.payment.contract;

import java.util.UUID;

public record CoachStripeStatusResponse(
    UUID coachId,
    String onboardingStatus,
    boolean chargesEnabled,
    boolean payoutsEnabled
) {}
