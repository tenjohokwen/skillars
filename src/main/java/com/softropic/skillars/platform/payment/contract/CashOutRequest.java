package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CashOutRequest(
    @NotNull @DecimalMin("0.01") BigDecimal amount
) {}
