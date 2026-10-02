package com.softropic.skillars.platform.security.contract;

import jakarta.validation.constraints.NotNull;

public record UpdatePlayerPositionRequest(
    @NotNull PlayerPosition position
) {}
