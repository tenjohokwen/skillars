package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;

/**
 * skillars-deferred-169 AC2: what Stripe holds for one PaymentIntent's latest charge, so a pack-purchase resolver can tell
 * "captured, then refunded" from "the charge object was not readable". {@link PaymentGateway#getRefundableAmount} cannot:
 * it returns zero for both.
 *
 * @param captured the captured amount in major units, or {@code null} when the PaymentIntent has no latest charge object
 * @param refunded the refunded amount in major units ({@code 0} when {@code captured} is null)
 */
public record CaptureState(BigDecimal captured, BigDecimal refunded) {

    /** True only when money was captured and all of it has been refunded. */
    public boolean fullyRefunded() {
        return captured != null && captured.signum() > 0 && refunded.compareTo(captured) >= 0;
    }

    /** True when the charge object could not be read, so neither "captured" nor "refunded" is known. */
    public boolean unreadable() {
        return captured == null;
    }
}
