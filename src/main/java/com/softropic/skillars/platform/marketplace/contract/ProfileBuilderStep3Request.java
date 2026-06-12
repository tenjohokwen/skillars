package com.softropic.skillars.platform.marketplace.contract;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record ProfileBuilderStep3Request(
    @NotNull @DecimalMin("0.01") BigDecimal perSessionPrice,
    @Size(max = 5) List<SessionPackRequest> sessionPacks
) {
    public record SessionPackRequest(
        @Positive int sessionCount,
        @NotNull @DecimalMin("0.01") BigDecimal totalPrice,
        @Size(max = 100) String label
    ) {}
}
