package com.softropic.skillars.platform.admin.contract;

import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;

/**
 * skillars-deferred-153 AC1 (code review, 2026-10-17): tells the admin what actually happened.
 *
 * <p>The endpoint previously returned a bare 200 with no body for all three outcomes, so an admin
 * could close a support ticket believing a booking had been force-completed when it had not. AC1
 * asked for "success with a 'no action needed, already completed' note" — this carries it. Adding a
 * body is not a breaking change: the endpoint is new in this story and has no consumers yet.
 */
public record AdminForceCompleteBookingResponse(ForceCompleteOutcome outcome) {}
