package com.softropic.skillars.platform.admin.contract;

/**
 * Outcome of a coach suspension (admin or child-safety hold).
 *
 * @param newlySuspended    false when the coach was already suspended (the call was a no-op)
 * @param cancelledBookings bookings cancelled with a full refund (REQUESTED, plus ACCEPTED /
 *                          PAYMENT_PENDING / CONFIRMED / UPCOMING unless the admin opted out)
 * @param alertedBookings   child-safety hold only: live or payment-in-flight bookings that could not be
 *                          cancelled and were raised as urgent {@code SAFETY_HOLD_BOOKING} admin alerts
 */
public record AdminSuspendCoachResponse(boolean newlySuspended, int cancelledBookings, int alertedBookings) {

    public static AdminSuspendCoachResponse alreadySuspended() {
        return new AdminSuspendCoachResponse(false, 0, 0);
    }
}
