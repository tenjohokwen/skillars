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
 * <p>Note these still surface as HTTP 403: {@code ApiAdvice.operationDeniedHandler} maps
 * {@code OperationNotAllowedException} to {@code FORBIDDEN} unconditionally, independent of the code
 * carried. Splitting the code changes the {@code errorKey} and the message, not the status.
 */
public enum BookingError implements ErrorCode {
    COACH_UNAVAILABLE,
    SLOT_UNAVAILABLE,
    INVALID_SESSION_DURATION,
    START_TIME_IN_PAST,
    INVALID_TIME_RANGE,
    SLOT_OUTSIDE_AVAILABILITY,
    BATCH_ALREADY_PROCESSED;

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
        };
    }
}
