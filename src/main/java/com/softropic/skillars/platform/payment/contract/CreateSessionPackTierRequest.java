package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Upper bounds keep the values inside {@code session_pack_tiers.total_price numeric(10,2)} (an overflow was a
 * 500 from the DB) and inside sane business limits: a pack is at most {@value #MAX_SESSION_COUNT} sessions
 * and {@value #MAX_TOTAL_PRICE}. The price carries at most 2 decimals so the stored value, the per-session
 * price and the amount charged to Stripe can never disagree by rounding.
 */
public record CreateSessionPackTierRequest(
    @NotBlank @Size(max = 200) String label,
    @NotNull @Min(1) @Max(MAX_SESSION_COUNT) Integer sessionCount,
    @NotNull @DecimalMin("0.01") @DecimalMax(MAX_TOTAL_PRICE) @Digits(integer = 8, fraction = 2) BigDecimal totalPrice
) {
    public static final int MAX_SESSION_COUNT = 100;
    public static final String MAX_TOTAL_PRICE = "50000.00";
}
