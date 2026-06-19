package com.softropic.skillars.platform.development.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SluTargetRequest(
    @NotBlank @Size(max = 10) String skillCode,
    @Positive BigDecimal weeklyTargetSlu
) {}
