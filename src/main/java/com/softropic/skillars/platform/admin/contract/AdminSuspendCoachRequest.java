package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param cancelUpcomingBookings C2 (DECISIONS.md item 2): also cancel — with a full refund and no coach
 *                               strike — the coach's ACCEPTED / PAYMENT_PENDING / CONFIRMED / UPCOMING
 *                               bookings. Omitted (null) means {@code true}: cancelling is the default,
 *                               the admin opts out. REQUESTED bookings are always cancelled.
 */
public record AdminSuspendCoachRequest(
    @NotBlank @Size(max = 500) String reason,
    boolean notifyCoach,
    Boolean cancelUpcomingBookings) {

    public boolean cancelUpcomingBookingsOrDefault() {
        return cancelUpcomingBookings == null || cancelUpcomingBookings;
    }
}
