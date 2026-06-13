package com.softropic.skillars.platform.booking.contract;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PurchaseSessionPackRequest(
    @NotNull UUID coachId,
    UUID sessionPackId  // null = per-session single purchase
) {}
