package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateSessionPackTierRequest(
    @NotBlank @Size(max = 200) String label,
    @NotNull @Min(1) Integer sessionCount,
    @NotNull @DecimalMin("0.01") BigDecimal totalPrice
) {}
