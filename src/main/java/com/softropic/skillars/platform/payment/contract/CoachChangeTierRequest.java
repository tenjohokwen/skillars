package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotNull;

public record CoachChangeTierRequest(@NotNull String newTier) {}
