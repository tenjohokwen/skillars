package com.softropic.skillars.platform.booking.contract;

import com.softropic.skillars.infrastructure.exception.ErrorCode;

/**
 * Wire-level error codes for the booking module.
 *
 * <p>The four codes below {@code INVALID_SESSION_DURATION} were split out of
 * {@code SecurityError.MISSING_RIGHTS} by the {@code skillars-deferred-30} code review. That one code
 * previously carried eight throw sites across six unrelated causes in
 * {@code BookingService.createBookingRequest} alone, so no frontend branch on it could say anything
 * more specific than "something was rejected" — and the generic copy it produced told the caller to
 * retry rejections that are deterministically non-retryable.
 *
 * <p>{@code MISSING_RIGHTS} is deliberately retained at the genuine authorization sites (caller does
 * not own the player profile / the session pack). Everything here is request-state validation, which
 * is a different thing and needs its own user-facing message.
 *
 * <p>The three {@code *_RESCHEDULE*} / {@code BOOKING_NOT_RESCHEDULABLE} codes were split out of
 * {@code SecurityError.MISSING_RIGHTS} by {@code skillars-deferred-31} AC3 for the same reason, in
 * {@code RescheduleService}: that class carried twelve {@code MISSING_RIGHTS} throws across its three
 * public methods, of which only three (parent/coach does not own the booking) are authorization.
 * {@code BATCH_NONE_ACCEPTED} (AC2) is not a split — it replaces a silent {@code return} that reported
 * HTTP 2xx over a batch in which nothing was accepted.
 *
 * <p>Note these still surface as HTTP 403: {@code ApiAdvice.operationDeniedHandler} maps
 * {@code OperationNotAllowedException} to {@code FORBIDDEN} unconditionally, independent of the code
 * carried. Splitting the code changes the {@code errorKey} and the message, not the status.
 *
 * <p>{@code CONCURRENT_MODIFICATION} (added by {@code skillars-deferred-67}) differs from the splits
 * above in *kind*, not just *source*: it does not split an existing authorization throw into a more
 * specific one — it replaces a genuine authorization code ({@code SecurityError.MISSING_RIGHTS}) that
 * was being reused for a non-authorization case (a concurrent-write race) across all seven
 * {@code OptimisticLockingFailureException} catches in {@code BookingCompletionService}. HTTP status is
 * unaffected either way — {@code ApiAdvice.operationDeniedHandler} still maps
 * {@code OperationNotAllowedException} to 403 unconditionally, same as the note above.
 *
 * <p>{@code SESSION_CROSSES_MIDNIGHT} (added by {@code skillars-deferred-69}) is request-state
 * validation, not authorization: {@code BookingService.isSlotWithinAvailabilityWindow} throws it when a
 * session's end falls on a different calendar day than its start against an otherwise-matching
 * availability window, since no window entry can ever be checked against a second calendar day.
 *
 * <p>{@code CANNOT_RESPOND_TO_OWN_PROPOSAL} (added by {@code skillars-deferred-69} AC5, alongside
 * coach-initiated reschedule) guards {@code acceptReschedule}/{@code declineReschedule} and the new
 * {@code acceptRescheduleAsParent}/{@code declineRescheduleAsParent}: whichever party proposed a
 * reschedule cannot be the one to accept or decline it. Request-state validation, not authorization.
 *
 * <p>{@code WEEK_START_OUT_OF_RANGE} (added by {@code skillars-deferred-78} AC6) guards
 * {@code AvailabilityResource.getAvailability} and {@code ScheduleResource.getCoachSchedule}
 * against a pathological {@code weekStart} query parameter: a malformed request parameter, the
 * same kind of thing as {@code START_TIME_IN_PAST}/{@code INVALID_TIME_RANGE} above, not an
 * authorization failure.
 */
public enum BookingError implements ErrorCode {
    COACH_UNAVAILABLE,
    SLOT_UNAVAILABLE,
    INVALID_SESSION_DURATION,
    START_TIME_IN_PAST,
    INVALID_TIME_RANGE,
    SLOT_OUTSIDE_AVAILABILITY,
    BATCH_ALREADY_PROCESSED,
    BATCH_NONE_ACCEPTED,
    BOOKING_NOT_RESCHEDULABLE,
    RESCHEDULE_ALREADY_PENDING,
    RESCHEDULE_NOT_PENDING,
    NO_SHOW_TOO_EARLY,
    CONCURRENT_MODIFICATION,
    SESSION_CROSSES_MIDNIGHT,
    CANNOT_RESPOND_TO_OWN_PROPOSAL,
    AVAILABILITY_CHANGED,
    WEEK_START_OUT_OF_RANGE;

    @Override
    public String getErrorCode() {
        return switch (this) {
            case COACH_UNAVAILABLE         -> "booking.coachUnavailable";
            case SLOT_UNAVAILABLE          -> "booking.slotUnavailable";
            case INVALID_SESSION_DURATION  -> "booking.invalidSessionDuration";
            case START_TIME_IN_PAST        -> "booking.startTimeInPast";
            case INVALID_TIME_RANGE        -> "booking.invalidTimeRange";
            case SLOT_OUTSIDE_AVAILABILITY -> "booking.slotOutsideAvailability";
            case BATCH_ALREADY_PROCESSED   -> "booking.batchAlreadyProcessed";
            case BATCH_NONE_ACCEPTED       -> "booking.batchNoneAccepted";
            case BOOKING_NOT_RESCHEDULABLE -> "booking.notReschedulable";
            case RESCHEDULE_ALREADY_PENDING -> "booking.rescheduleAlreadyPending";
            case RESCHEDULE_NOT_PENDING    -> "booking.rescheduleNotPending";
            case NO_SHOW_TOO_EARLY         -> "booking.noShowTooEarly";
            case CONCURRENT_MODIFICATION   -> "booking.concurrentModification";
            case SESSION_CROSSES_MIDNIGHT  -> "booking.sessionCrossesMidnight";
            case CANNOT_RESPOND_TO_OWN_PROPOSAL -> "booking.cannotRespondToOwnProposal";
            case AVAILABILITY_CHANGED      -> "booking.availabilityChanged";
            case WEEK_START_OUT_OF_RANGE   -> "booking.weekStartOutOfRange";
        };
    }
}
