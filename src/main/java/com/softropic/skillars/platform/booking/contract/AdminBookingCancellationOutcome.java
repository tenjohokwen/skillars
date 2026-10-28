package com.softropic.skillars.platform.booking.contract;

/**
 * Result of {@code BookingService.cancelBookingAsAdmin} for one booking. Returned rather than thrown:
 * that method joins the caller's (suspension's) transaction, so a throw would mark the whole
 * suspension rollback-only — the caller decides what each non-cancelled outcome means.
 */
public enum AdminBookingCancellationOutcome {
    /** Cancelled by this call; the refund and the parent's neutral notice are enqueued with it. */
    CANCELLED,
    /**
     * The booking is {@code PAYMENT_PENDING} with a {@code CAPTURE_PENDING} payment row: a Stripe charge
     * is in flight (or completed without its record committing). Cancelling on top of it is what
     * produces "money captured, booking cancelled, no refund" — the same interlock as the parent's
     * {@code booking.paymentInProgress}. Left untouched.
     */
    PAYMENT_IN_PROGRESS,
    /** {@code IN_PROGRESS} / {@code PAUSED}: a live session cannot be called off server-side. Left untouched. */
    LIVE_SESSION,
    /** Already terminal / post-session (a concurrent cancel won, it completed, ...). Left untouched. */
    NOT_CANCELLABLE
}
