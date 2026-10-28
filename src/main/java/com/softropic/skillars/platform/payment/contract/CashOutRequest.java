package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code @Digits(fraction = 2)}: the amount is euro money debited from a {@code numeric(10,2)}
 * ledger — a sub-cent amount is rejected rather than silently rounded.
 */
public record CashOutRequest(
    @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount
) {}
