package com.softropic.skillars.platform.admin.contract;

import com.softropic.skillars.platform.booking.contract.AdminBookingCancellationOutcome;

/**
 * C6: what the admin single-booking cancellation actually did. Only {@code CANCELLED} changed anything;
 * {@code LIVE_SESSION} (IN_PROGRESS / PAUSED), {@code PAYMENT_IN_PROGRESS} (a Stripe capture is in
 * flight) and {@code NOT_CANCELLABLE} (already terminal / post-session) are refusals the page explains.
 */
public record AdminCancelBookingResponse(AdminBookingCancellationOutcome outcome) {}
