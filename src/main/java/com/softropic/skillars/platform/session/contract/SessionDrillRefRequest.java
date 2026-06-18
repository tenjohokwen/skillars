package com.softropic.skillars.platform.session.contract;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SessionDrillRefRequest(
    @NotNull UUID drillId,
    @Min(0) int order
) {}
