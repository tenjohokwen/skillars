package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotNull;

// No billingInterval field — coaches are MONTHLY only per FR-PAY-006
public record CoachSubscribeRequest(@NotNull String tier, @NotNull String paymentMethodId) {}
