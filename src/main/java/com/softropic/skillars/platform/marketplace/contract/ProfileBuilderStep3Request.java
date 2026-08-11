package com.softropic.skillars.platform.marketplace.contract;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record ProfileBuilderStep3Request(
    @NotNull @DecimalMin("0.01") BigDecimal perSessionPrice,
    /**
     * Per-coach session length override, in minutes. Deliberately has no {@code @NotNull}: a coach
     * who does not touch the field submits {@code null} and inherits the platform default
     * ({@code booking.session.defaultDurationMinutes}), which is the behaviour the whole model rests
     * on. Bounds mirror chk_coach_pricing_session_duration (V93).
     */
    @Min(15) @Max(240) Integer sessionDurationMinutes,
    @Size(max = 5) List<SessionPackRequest> sessionPacks
) {
    public record SessionPackRequest(
        @Positive int sessionCount,
        @NotNull @DecimalMin("0.01") BigDecimal totalPrice,
        @Size(max = 100) String label
    ) {}
}
