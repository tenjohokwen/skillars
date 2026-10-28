package com.softropic.skillars.platform.admin.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of the admin suspend / reactivate user endpoints; {@code reason} lands in admin_action_log.
 *
 * @param cancelUpcomingBookings suspend only, and only meaningful for a COACH account (whose
 *                               marketplace profile the suspension takes down): also cancel the coach's
 *                               upcoming bookings with a full refund (C2). Null means {@code true}.
 *                               Ignored by reactivate.
 */
public record AdminUserStatusRequest(
    @NotBlank @Size(max = 500) String reason,
    Boolean cancelUpcomingBookings) {

    public boolean cancelUpcomingBookingsOrDefault() {
        return cancelUpcomingBookings == null || cancelUpcomingBookings;
    }
}
