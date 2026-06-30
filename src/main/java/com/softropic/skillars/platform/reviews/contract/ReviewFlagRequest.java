package com.softropic.skillars.platform.reviews.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewFlagRequest(
    @NotNull ReviewFlagReason reason,
    @Size(max = 500) String details
) {}
