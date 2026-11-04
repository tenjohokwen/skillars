package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.infrastructure.validation.NoContactDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    /**
     * skillars-deferred-168: the coach's one purchasable pack (it becomes their active session pack tier).
     * {@code null} leaves the current offer untouched, an empty list withdraws it, one element sets it.
     * {@code @Valid} on the element, plus {@code @NotNull}, because Bean Validation does not cascade into a
     * list without it: before this story none of {@link SessionPackRequest}'s constraints ran over HTTP.
     */
    @Size(max = 1) List<@NotNull @Valid SessionPackRequest> sessionPacks
) {
    /**
     * Bounds are the tier's own ({@link SessionPackLimits}), so a pack the builder accepts is always a pack
     * the payment module can sell. {@code sessionCount} is boxed so a missing count is a {@code @NotNull}
     * violation rather than a silent 0. (Jackson's default float-to-int coercion still truncates a fractional
     * count, boxed or not; the pack forms only allow whole numbers.)
     */
    public record SessionPackRequest(
        @NotNull @Min(1) @Max(SessionPackLimits.MAX_SESSION_COUNT) Integer sessionCount,
        @NotNull @DecimalMin("0.01") @DecimalMax(SessionPackLimits.MAX_TOTAL_PRICE)
        @Digits(integer = 8, fraction = 2) BigDecimal totalPrice,
        @Size(max = SessionPackLimits.MAX_LABEL_LENGTH) @NoContactDetails String label
    ) {
        /**
         * The one tier rule bean validation cannot express per field: the per-session price is the total
         * divided by the count, rounded to cents, and the tier table CHECKs it is above zero. Checked here so
         * the builder answers with a field error instead of the payment module's untranslated 400.
         */
        @AssertTrue(message = "marketplace.packPerSessionPriceTooLow|Each session in the pack must cost at least 0.01")
        public boolean isPerSessionPriceValid() {
            if (sessionCount == null || sessionCount < 1 || totalPrice == null) {
                return true;
            }
            return totalPrice.divide(BigDecimal.valueOf(sessionCount), 2, RoundingMode.HALF_UP).signum() > 0;
        }
    }
}
