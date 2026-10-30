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
    RECAP_NOT_EDITABLE_DISPUTED,
    /**
     * skillars-deferred-163 AC2.1: {@code submitWrapUp} throws this instead of the generic
     * {@code MISSING_RIGHTS} "can no longer be edited" message when the booking was already
     * force-completed with an empty-recap placeholder row — by the stuck-completion sweeper OR by
     * admin force-complete, which produce the identical placeholder signature
     * ({@code BookingCompletionService.isSweeperPlaceholder}). Deliberately does not claim the
     * sweeper is the exclusive cause. {@code PUT /recap} ({@code updateRecap}) can still save the
     * recap text, so the old message was also substantively wrong, not just imprecise.
     */
    RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE,
    /**
     * fix/booking-integrity (B2): the player profile a booking is being created for has been erased
     * ({@code developmentDataErasedAt} set) or has a child-erasure request in flight
     * ({@code PENDING}/{@code PROCESSING}/{@code FAILED}, the last being re-driven by
     * {@code GdprErasureService.retryFailedErasures}). Checked under the {@code player_profiles} row
     * lock by {@code BookingService.requirePlayerBookableUnderLock}, the same lock the erasure takes.
     */
    PLAYER_NOT_BOOKABLE,
    /**
     * fix/booking-price-and-excused-race: a card- or credit-funded booking (single, batch, or a coach's
     * duplicate-next-week proposal) is being created while the coach has no {@code coach_pricing} row.
     * The request is refused rather than stored with a NULL {@code agreed_price}, because whatever price
     * the coach set before accepting would then be charged without the payer ever having seen it.
     * Pack-funded bookings are exempt (the pack's own locked price applies).
     */
    COACH_PRICING_UNAVAILABLE,
    /**
     * fix/booking-price-and-excused-race: accept-time counterpart of {@code COACH_PRICING_UNAVAILABLE}. A
     * non-pack booking with no {@code agreed_price} (a row created before V192, or after it while the
     * coach had no pricing) is never charged at the coach's current price, which the payer did not agree
     * to. The coach declines it and the payer re-requests, which locks the price they then see.
     */
    PRICE_NOT_LOCKED,
    /**
     * fix/booking-request-consent: the coach tried to accept a booking they proposed themselves ("repeat
     * next week"). Only the parent can confirm a coach-proposed booking, which is what accepts and charges it.
     */
    PARENT_CONFIRMATION_REQUIRED,
    /**
     * fix/booking-request-consent: the parent tried to confirm a booking the coach did not propose — a
     * parent's own request is accepted by the coach, never by the parent.
     */
    NOT_A_COACH_PROPOSAL,
    /**
     * fix/booking-request-consent: confirm / decline / withdraw on a booking that is no longer REQUESTED
     * (the coach accepted or declined it, it expired, or it was already withdrawn). Distinct from the
     * generic cancel, which applies the paid-booking refund rules: a "withdraw" click must never turn
     * into the cancellation of a booking that was accepted and charged a moment earlier.
     */
    REQUEST_NOT_PENDING,
    /**
     * fix/booking-request-consent (M2): the parent already has {@code booking.request.maxPendingPerCoach}
     * requests awaiting this coach's answer. Requests are free and hold their slot, so without a cap one
     * account could hide a coach's whole calendar.
     */
    TOO_MANY_PENDING_REQUESTS,
    /**
     * fix/booking-request-consent (M2): the requested start is further ahead than
     * {@code booking.request.maxHorizonDays}. Also bounds how far ahead a price can be locked.
     */
    BEYOND_BOOKING_HORIZON,
    /**
     * fix/booking-completion-integrity: a REQUESTED booking whose start time has already passed can no
     * longer be accepted or confirmed (coach accept, batch accept, parent confirm — one shared check in
     * {@code BookingService.acceptAndInitiatePayment}). Accepting it used to charge the parent for a session
     * that was already over. The expiry scheduler declines such requests too.
     */
    REQUEST_EXPIRED,
    /**
     * fix/booking-completion-integrity: a parent cannot cancel a session the coach has started
     * (IN_PROGRESS or PAUSED). Cancelling a PAUSED session used to be a forfeit that paid the coach in
     * full; a grievance about a live session goes through a dispute instead.
     */
    SESSION_IN_PROGRESS,
    /**
     * fix/audit3-completion-dispute-integrity: a coach no-show cannot be reported for a session the coach
     * started (Start pressed), even one ended early and awaiting the family's confirmation — the family
     * disputes it instead. A never-started (Quick Completed) session can still be reported.
     */
    SESSION_WAS_STARTED,
    /**
     * fix/audit3-completion-dispute-integrity: the family confirmed this session before the coach's wrap-up. The
     * recap-only dialog is refused: the coach submits the wrap-up, which also records the attendance, ratings and
     * homework (and the session's SLU / timeline / homework). Mirrors {@link #RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE}.
     */
    WRAP_UP_AWAITED;

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
            case RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE -> "booking.recapStillEditableAfterForceComplete";
            case PLAYER_NOT_BOOKABLE       -> "booking.playerNotBookable";
            case COACH_PRICING_UNAVAILABLE -> "booking.coachPricingUnavailable";
            case PRICE_NOT_LOCKED          -> "booking.priceNotLocked";
            case PARENT_CONFIRMATION_REQUIRED -> "booking.parentConfirmationRequired";
            case NOT_A_COACH_PROPOSAL      -> "booking.notACoachProposal";
            case REQUEST_NOT_PENDING       -> "booking.requestNotPending";
            case TOO_MANY_PENDING_REQUESTS -> "booking.tooManyPendingRequests";
            case BEYOND_BOOKING_HORIZON    -> "booking.beyondBookingHorizon";
            case REQUEST_EXPIRED           -> "booking.requestExpired";
            case SESSION_IN_PROGRESS       -> "booking.sessionInProgress";
            case SESSION_WAS_STARTED       -> "booking.sessionWasStarted";
            case WRAP_UP_AWAITED           -> "booking.wrapUpAwaited";
        };
    }

    // skillars-deferred-103 AC8: consistent message for concurrent-modification races.
    // Keep this constant singular so all ~14 throw sites use identical developer-facing text.
    public static final String CONCURRENT_MODIFICATION_MESSAGE = "booking status changed under concurrent modification";
}
