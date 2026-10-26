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
 *
 * <p>{@code SLOT_BLOCKED_BY_COACH} (added by {@code skillars-deferred-79} AC1) is request-state
 * validation, the same bucket as {@code SLOT_OUTSIDE_AVAILABILITY}/{@code SLOT_UNAVAILABLE}: a
 * coach-initiated {@code CoachAvailabilityBlock} overlaps the requested/proposed slot. Deliberately
 * distinct from those two codes — the cause (an explicit block-out) differs from "outside declared
 * hours" or "double-booked."
 *
 * <p>{@code BLOCK_OVERLAPS_BOOKING} (added by {@code skillars-deferred-79} AC2) guards
 * {@code AvailabilityService.addBlock}: a coach cannot create a block-out over a slot that already
 * has an active booking. Also request-state validation, not authorization.
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
    WEEK_START_OUT_OF_RANGE,
    SLOT_BLOCKED_BY_COACH,
    BLOCK_OVERLAPS_BOOKING,
    /**
     * skillars-deferred-106 AC9: a {@code COMPLETED} booking is immutable for cancellation. Once the
     * session is confirmed complete the coach payout has been (or is being) released, so a cancel /
     * no-show / refund would be chasing money that has left the platform. Any post-completion
     * grievance goes through {@code DisputeService}, which owns the transfer-reversal path. Added as
     * a fail-fast guard even though {@code BookingStateMachine} already blocks the transition, so a
     * future refactor cannot open the path silently.
     */
    BOOKING_ALREADY_COMPLETED,
    /**
     * skillars-deferred-151 AC6: guards {@code BookingCompletionService.startSession} and
     * {@code initiateQuickComplete} — neither previously had a time-based gate, unlike their sibling
     * {@code BookingService.recordNoShowCoach}, which already rejects with {@code NO_SHOW_TOO_EARLY}
     * before the booking's scheduled start time. A USER DECISION reverses
     * {@code spec-coach-booking-cancel-no-show.md}'s prior "Never add a client-side too-early guard"
     * note for {@code recordNoShowPlayer} specifically (see that method's own call site) — this is a
     * distinct code for the two completion-path methods, since "cannot start/quick-complete a
     * session before it starts" is a different condition from "cannot report a no-show before it
     * starts" even though both share the same underlying time check.
     */
    SESSION_NOT_YET_STARTED,
    /**
     * skillars-deferred-155 AC5: guards {@code BookingService.setPlayerRequest}. Deliberately a new
     * code rather than reusing {@code BOOKING_ALREADY_COMPLETED}/{@code rejectIfPostCompletion} —
     * that guard blocks exactly one of {@code BookingStatus}'s 18 values ({@code COMPLETED}) with a
     * cancellation-flavoured message ("...raise a dispute instead"), which would misdescribe a
     * rejected text edit. This code instead allow-lists the two statuses representing "this session
     * is genuinely still going to happen" ({@code CONFIRMED}, {@code UPCOMING}) and rejects every
     * other status — pre-confirmation ({@code REQUESTED}/{@code ACCEPTED}/{@code PAYMENT_PENDING},
     * not yet locked in) and every post-session/terminal status alike — with a message about
     * editability, not cancellation.
     */
    BOOKING_NOT_EDITABLE,
    /**
     * skillars-deferred-155 code review, Decision 4(a): guards the same
     * {@code BookingService.setPlayerRequest} write as {@code BOOKING_NOT_EDITABLE}, but for the clock
     * rather than the status — the session's {@code requestedEndTime} has passed. Kept as its own code
     * so the UI can say "this session has already taken place" instead of the status rejection's
     * "cannot be edited right now", which would invite a retry that can never succeed. Follows the same
     * split-a-purpose-specific-code precedent as {@code SESSION_NOT_YET_STARTED} above, which exists for
     * exactly the mirror-image reason on the no-show path.
     */
    SESSION_ALREADY_ENDED,
    /**
     * skillars-12-7 AC3: the recap edit window ({@code booking.recap.editWindowDays} after the
     * session's end) has closed. Its own code because the exception message never reaches the client
     * ({@code ApiAdvice} returns a fixed default plus the error key), so the UI needs the key to say
     * "this session is too old to edit".
     */
    RECAP_EDIT_WINDOW_CLOSED,
    /**
     * skillars-12-7 AC2/AC4: the recap cannot be written because the coach or the player has been
     * erased (GDPR) — writing would resurrect scrubbed text.
     */
    RECAP_NOT_EDITABLE_ERASED,
    /**
     * skillars-deferred-162 AC6.2 (D162-A): the recap is locked because the session is disputed (an active
     * dispute, or one resolved with credit or a coach warning). The booking itself stays COMPLETED.
     */
    RECAP_NOT_EDITABLE_DISPUTED;

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
            case SLOT_BLOCKED_BY_COACH     -> "booking.slotBlockedByCoach";
            case BLOCK_OVERLAPS_BOOKING    -> "booking.blockOverlapsBooking";
            case BOOKING_ALREADY_COMPLETED -> "booking.alreadyCompleted";
            case SESSION_NOT_YET_STARTED   -> "booking.sessionNotYetStarted";
            case BOOKING_NOT_EDITABLE      -> "booking.notEditable";
            case SESSION_ALREADY_ENDED     -> "booking.sessionAlreadyEnded";
            case RECAP_EDIT_WINDOW_CLOSED  -> "booking.recapEditWindowClosed";
            case RECAP_NOT_EDITABLE_ERASED -> "booking.recapNotEditableErased";
            case RECAP_NOT_EDITABLE_DISPUTED -> "booking.recapNotEditableDisputed";
        };
    }

    // skillars-deferred-103 AC8: consistent message for concurrent-modification races.
    // Keep this constant singular so all ~14 throw sites use identical developer-facing text.
    public static final String CONCURRENT_MODIFICATION_MESSAGE = "booking status changed under concurrent modification";
}
