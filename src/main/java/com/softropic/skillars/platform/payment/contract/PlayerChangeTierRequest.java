package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotNull;

public record PlayerChangeTierRequest(@NotNull Long playerId, @NotNull String newTier) {}
