package com.softropic.skillars.platform.payment.service;

/**
 * Outcome of {@link BookingPaymentPersistenceService#reserveCapture}. Only {@link #RESERVED}
 * permits the caller to contact Stripe.
 */
public enum CaptureReservation {

    /** A CAPTURE_PENDING row was written and committed. The caller may charge. */
    RESERVED,

    /**
     * The booking is gone or no longer PAYMENT_PENDING — typically a parent cancellation that won
     * the race, or a settle that already completed. Expected and correct; no money moves.
     */
    BOOKING_NOT_PENDING,

    /** A terminal payment row already exists: duplicate event delivery. */
    ALREADY_SETTLED,

    /**
     * A CAPTURE_PENDING row from an earlier attempt is still standing, so that attempt's outcome at
     * Stripe is unknown. Never re-charge on this: escalate for manual reconciliation instead.
     */
    CAPTURE_UNCONFIRMED
}
