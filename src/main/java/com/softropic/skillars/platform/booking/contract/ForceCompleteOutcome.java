package com.softropic.skillars.platform.booking.contract;

/**
 * skillars-deferred-153 AC1 (code review, 2026-10-17): the outcome of
 * {@code BookingCompletionService.forceCompleteWithPlaceholderData}.
 *
 * <p>Exists because a {@code void} return made both callers misreport. The sweeper logged
 * "Force-completed abandoned wrap-up" unconditionally and the admin endpoint wrote a
 * {@code BOOKING_FORCE_COMPLETE} audit row and returned a bare 200 — even on the two paths where
 * the method deliberately does nothing. On a money-moving action (completion releases the coach's
 * payout, see {@code CoachPayoutEnqueueListener}) an audit trail that claims an action which did
 * not occur is worse than no audit trail, so the outcome is now explicit and recorded.
 */
public enum ForceCompleteOutcome {

    /** A placeholder row was written, the booking transitioned to COMPLETED, and payout was released. */
    FORCE_COMPLETED,

    /** A {@code SessionCompletionData} row already existed — a real wrap-up landed first. No action taken. */
    ALREADY_RESOLVED,

    /** The booking was not in {@code COMPLETED_PENDING_CONFIRMATION}, so there was nothing to un-stick. */
    NOT_STUCK
}
