package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotNull;

public record PlayerSubscribeRequest(
        @NotNull Long playerId,
        @NotNull String tier,
        @NotNull String billingInterval,
        @NotNull String paymentMethodId) {}
