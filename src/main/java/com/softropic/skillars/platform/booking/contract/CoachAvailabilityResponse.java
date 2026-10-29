package com.softropic.skillars.platform.booking.contract;

import java.util.List;

/**
 * {@code coachPriced} (fix/booking-price-and-excused-race): whether the coach has a {@code coach_pricing} row.
 * Without one, card- and credit-funded requests are refused ({@code booking.coachPricingUnavailable}), so the
 * booking page uses it to disable those up front; pack-funded requests are unaffected.
 */
public record CoachAvailabilityResponse(
    List<AvailabilityWindowResponse> windows,
    List<AvailabilityBlockResponse> blocks,
    List<AvailableSlotResponse> computedSlots,
    String canonicalTimezone,
    String availabilitySignature,
    boolean coachPriced
) {}
