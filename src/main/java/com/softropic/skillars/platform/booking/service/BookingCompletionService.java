package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingDisputeLookup;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.contract.MissingRecapItem;
import com.softropic.skillars.platform.booking.contract.MissingRecapsResponse;
import com.softropic.skillars.platform.booking.contract.SessionRecapResponse;
import com.softropic.skillars.platform.booking.contract.SessionRecapUpdatedEvent;
import com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest;
import com.softropic.skillars.platform.booking.contract.WrapUpRequest;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.repo.User;
import org.springframework.data.domain.PageRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.repo.SessionStatus;
import com.softropic.skillars.platform.session.service.DrillUsagePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingCompletionService {

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;
    private final SessionCompletionDataRepository completionDataRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ConfigService configService;
    private final DrillRepository drillRepository;
    private final BookingDisputeLookup bookingDisputeLookup;
    private final SessionRepository sessionRepository;
    private final ContactDetailSanitizer contactDetailSanitizer;

    @Transactional
    public void startSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.UPCOMING);
        verifyNotBeforeStartTime(booking);
        try {
            bookingService.transition(bookingId, BookingEvent.START, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
        // skillars-12-7 code review (Decision 2): lets "Resume wrap-up" reopen the wizard in LIVE mode.
        // transition() refreshed this same managed instance under its row lock, so the write is flushed
        // with the status change at commit.
        booking.setLiveStartedAt(Instant.now(ClockProvider.getClock()));
    }

    @Transactional
    public void endSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != BookingStatus.IN_PROGRESS && current != BookingStatus.PAUSED) {
            throw new OperationNotAllowedException(
                "Booking is in status " + current + ", expected IN_PROGRESS or PAUSED",
                SecurityError.MISSING_RIGHTS);
        }
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE_PENDING, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
        // fix/audit3-completion-dispute-integrity: Start then End a minute later used to be a LIVE completion —
        // paid at once, the family never asked. Below booking.live.minDurationPercent of the booked length the
        // session waits for the family's confirmation (or dispute) like a Quick Complete; the stamp also makes
        // the coach's wrap-up QUICK (submitWrapUp). transition() refreshed this same managed instance.
        if (isShortLiveSession(booking, Instant.now(ClockProvider.getClock()))) {
            log.info("Live session {} ended below the minimum live duration — awaiting the family's confirmation",
                bookingId);
            requestFamilyConfirmation(booking);
        }
    }

    /**
     * fix/audit3-completion-dispute-integrity: the session was started (Start pressed) but ran for less than
     * {@code booking.live.minDurationPercent} of its booked length, Start to now. Pauses are not tracked, so a
     * paused stretch counts as live time. A never-started booking is not "short live" — it never was live.
     */
    boolean isShortLiveSession(Booking booking, Instant endedAt) {
        if (booking.getLiveStartedAt() == null) {
            return false;
        }
        long minPercent = configService.getBoundedLong(ConfigBounds.BOOKING_LIVE_MIN_DURATION_PERCENT);
        long bookedMillis = java.time.Duration.between(
            booking.getRequestedStartTime(), booking.getRequestedEndTime()).toMillis();
        long liveMillis = Math.max(0L, java.time.Duration.between(booking.getLiveStartedAt(), endedAt).toMillis());
        return liveMillis * 100 < bookedMillis * minPercent;
    }

    /**
     * fix/audit3-completion-dispute-integrity: asks the family to confirm (or dispute) a session that has just
     * entered COMPLETED_PENDING_CONFIRMATION without a full live run — a Quick Complete, a live session ended
     * below the minimum duration, or one {@link UnresolvedSessionSweeperService} ended. Sent here, at entry,
     * rather than only at wrap-up submit: a coach who never submitted one was later paid by
     * {@link StuckCompletionSweeperService} with the family never told. Once per booking
     * ({@code confirmation_requested_at}), so the wrap-up submit does not send it again. Joins the caller's
     * transaction; the caller holds the booking row lock (transition() / lockBookingForUpdate), and
     * {@code booking} is that managed instance, so the stamp flushes with the status change.
     */
    void requestFamilyConfirmation(Booking booking) {
        if (booking.getConfirmationRequestedAt() != null) {
            return;
        }
        booking.setConfirmationRequestedAt(Instant.now(ClockProvider.getClock()));
        bookingRepository.save(booking);
        String parentEmail = userRepository.findById(booking.getParentId())
            .map(User::getEmail).orElse("");
        String coachDisplayName = coachProfileRepository.findById(booking.getCoachId())
            .map(CoachProfile::getDisplayName).orElse(null);
        String playerName = playerProfileRepository.findById(booking.getPlayerId())
            .map(PlayerProfile::getName).orElse("Player");
        eventPublisher.publishEvent(new QuickCompleteConfirmationRequiredEvent(
            this, booking.getId(), booking.getParentId(), parentEmail, coachDisplayName,
            booking.getRequestedStartTime(), booking.getCanonicalTimezone(), playerName
        ));
    }

    @Transactional
    public void pauseSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.IN_PROGRESS);
        try {
            bookingService.transition(bookingId, BookingEvent.PAUSE, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
    }

    @Transactional
    public void resumeSession(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.PAUSED);
        try {
            bookingService.transition(bookingId, BookingEvent.RESUME, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
    }

    @Transactional
    public void initiateQuickComplete(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != BookingStatus.UPCOMING) {
            throw new OperationNotAllowedException(
                "Quick Complete requires an UPCOMING booking", SecurityError.MISSING_RIGHTS);
        }
        verifyNotBeforeStartTime(booking);
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE_PENDING, new TransitionContext(ActorRole.COACH, coachUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }
        // fix/audit3-completion-dispute-integrity: the family hears it now, not only once a wrap-up is submitted
        // (which may never happen). transition() refreshed this same managed instance under its row lock.
        requestFamilyConfirmation(booking);
    }

    @Transactional
    public void submitWrapUp(UUID bookingId, Long coachUserId, WrapUpRequest req) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking unlocked = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(unlocked, coach);
        // fix/booking-completion-integrity (payouts L4): lock the booking row before anything is read, so two
        // concurrent submits serialise — the second then sees the first one's completion row (or its status
        // change) below and is a no-op. The old catch of DataIntegrityViolationException around save() could
        // never fire: the row's id is generated in the VM, so the INSERT only flushed at commit. Locked after
        // the ownership check, so a caller who does not own the booking cannot pin its row.
        Booking booking = bookingService.lockBookingForUpdate(bookingId);

        // skillars-deferred-153 AC1 (code review, 2026-10-17): distinguish "the stuck-completion
        // sweeper already auto-completed this booking" from a generic status mismatch. This is the one
        // case where the coach is about to lose recap text they just typed, and verifyStatus's raw
        // "Booking is in status COMPLETED, expected COMPLETED_PENDING_CONFIRMATION" tells them nothing
        // actionable. The transition is one-way (BookingStateMachine has no path back to
        // COMPLETED_PENDING_CONFIRMATION).
        //
        // skillars-deferred-163 AC2.1 (corrected): the old message claimed the recap "can no longer be
        // edited" and blamed the sweeper exclusively. Both are wrong — admin force-complete
        // (forceCompleteWithPlaceholderData) produces the identical empty-recap placeholder signature,
        // and PUT /recap (updateRecap) can still save the text either way. A dedicated error lets the
        // caller redirect the coach to the recap dialog instead of a dead-end "can no longer be edited".
        //
        // Code review 2026-10-27: only when updateRecap would actually accept the text — the same
        // recapEditability verdict it applies. A disputed, erased or out-of-window booking gets that
        // verdict's own reason instead, never a "you can still save it" redirect into a read-only dialog.
        // The message names no cause: "automatically" would be false for an admin force-complete.
        Optional<SessionCompletionData> existing = completionDataRepository.findByBookingId(bookingId);
        if (BookingStatus.valueOf(booking.getStatus()) == BookingStatus.COMPLETED
                && existing.isPresent() && existing.get().isAwaitingWrapUp()) {
            // fix/audit3-completion-dispute-integrity: the family confirmed before this wrap-up. Fill the
            // placeholder in and record the development side once; payout and completion already happened.
            completeAwaitedWrapUp(booking, coach, existing.get(), req);
            return;
        }
        if (BookingStatus.valueOf(booking.getStatus()) == BookingStatus.COMPLETED) {
            if (existing.isPresent() && isSweeperPlaceholder(existing.get())) {
                Editability verdict = recapEditability(booking, coach, Instant.now(ClockProvider.getClock()));
                if (!verdict.editable()) {
                    throw new OperationNotAllowedException("Recap cannot be edited", verdict.reason());
                }
                throw new OperationNotAllowedException(
                    "This session was already completed, but you can still add the recap",
                    BookingError.RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE);
            }
        }
        if (existing.isPresent()
                && BookingStatus.valueOf(booking.getStatus()) == BookingStatus.COMPLETED_PENDING_CONFIRMATION) {
            // Idempotency guard (see the lock above): a previous QUICK submit already saved this booking's
            // wrap-up and is waiting on the parent. (A duplicate of a LIVE submit finds the booking COMPLETED
            // and gets verifyStatus's mismatch below, as before.)
            log.warn("Session completion data already exists for booking {} — ignoring duplicate submitWrapUp", bookingId);
            return;
        }
        verifyStatus(booking, BookingStatus.COMPLETED_PENDING_CONFIRMATION);
        // fix/booking-completion-integrity (H1): the mode is the server's, never the client's. LIVE (complete and
        // release the payout now) only when the coach actually ran the session — pressed Start
        // (liveStartedAt) and ended it themselves. A quick-completed session (never started), or one
        // UnresolvedSessionSweeperService ended (autoEndedAt), is QUICK: it stays pending until the parent
        // confirms or the quick-complete timeout passes. A coach used to quick-complete and then submit
        // mode=LIVE, completing and getting paid with no parent confirmation window at all.
        // fix/audit3-completion-dispute-integrity: and not when the family was already asked to confirm
        // (confirmation_requested_at) — a live session ended below booking.live.minDurationPercent of its
        // booked length is QUICK too (endSession).
        String mode = booking.getLiveStartedAt() != null && booking.getAutoEndedAt() == null
            && booking.getConfirmationRequestedAt() == null ? "LIVE" : "QUICK";
        if (!mode.equals(req.mode())) {
            log.warn("submitWrapUp for booking {}: client sent mode={}, server derived {} — using {}",
                bookingId, req.mode(), mode, mode);
        }
        List<UUID> homeworkDrillIds = validateHomeworkDrills(req.homeworkDrillIds(), coach);

        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(bookingId);
        scd.setCoachId(coach.getId());
        scd.setPlayerId(booking.getPlayerId());
        scd.setPlayerAttended(Boolean.TRUE.equals(req.playerAttended()));
        scd.setEffortRating(req.effortRating());
        scd.setFocusRating(req.focusRating());
        scd.setTechniqueRating(req.techniqueRating());
        scd.setCompletionMode(mode);
        // skillars-deferred-163 AC2.3: sanitised on every write path (updateRecap already shares this
        // helper — see BookingService.setPlayerRequest/setCoachPlanNote for the same call shape).
        scd.setWhatWeDid(BookingService.sanitizeAndTruncate(contactDetailSanitizer, req.recapWhatWeDid(), 500));
        scd.setWhatsNext(BookingService.sanitizeAndTruncate(contactDetailSanitizer, req.recapWhatsNext(), 500));
        scd.setWhatWentWell(BookingService.sanitizeAndTruncate(contactDetailSanitizer, blankToNull(req.recapWhatWentWell()), 500));

        if (!homeworkDrillIds.isEmpty()) {
            try {
                scd.setHomeworkDrillIds(objectMapper.writeValueAsString(homeworkDrillIds));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize homeworkDrillIds for booking {}", bookingId, e);
            }
        }
        if (!"LIVE".equals(mode)) {
            // skillars-deferred-163 AC2.4: QUICK's session plan stays editable until the delayed
            // BookingCompletedEvent (parent confirm or QuickCompleteTimeoutService's timeout)
            // eventually fires, so snapshot the plan's blocks now, while they are still trustworthy.
            scd.setPlanBlocksSnapshot(capturePlanBlocksSnapshot(bookingId));
        }
        completionDataRepository.save(scd);

        if ("LIVE".equals(mode)) {
            try {
                bookingService.transition(bookingId, BookingEvent.QUICK_COMPLETE, new TransitionContext(ActorRole.COACH, coachUserId));
            } catch (OptimisticLockingFailureException e) {
                throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
            }
            // skillars-deferred-163 AC2.3: use the saved (sanitised) scd values for all three recap
            // fields, matching confirmCompletion's QUICK path — not the raw request text this used to
            // mix in for the first two fields while already using scd for the third.
            eventPublisher.publishEvent(new BookingCompletedEvent(
                this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
                scd.isPlayerAttended(), req.effortRating(), req.focusRating(), req.techniqueRating(),
                homeworkDrillIds,
                scd.getWhatWeDid(), scd.getWhatsNext(), scd.getWhatWentWell()
            ));
        } else {
            // fix/audit3-completion-dispute-integrity: normally already sent when the booking entered
            // COMPLETED_PENDING_CONFIRMATION (a no-op then); still sent here for a booking that entered it
            // before V213 stamped confirmation_requested_at.
            requestFamilyConfirmation(booking);
        }
    }

    /**
     * fix/audit3-completion-dispute-integrity: the coach's wrap-up of a session the family already confirmed
     * ({@link #confirmCompletion} with no wrap-up wrote an {@code awaitingWrapUp} placeholder and published only
     * the settlement side). Fills that row in with the real attendance, ratings, homework and recap, clears the
     * flag — under the booking row lock the caller holds, so a duplicate submit then finds an ordinary completed
     * row and is refused — and publishes the development side (SLU, timeline, homework) exactly once. No status
     * change and no payout: the booking is already COMPLETED and paid. A wrap-up saying the player did not attend
     * is recorded as such (no SLU, no coach timeline access), but the booking is NOT turned into a no-show after
     * the family confirmed it — that would be a forfeit decided after the fact; a dispute is the route for it.
     */
    private void completeAwaitedWrapUp(Booking booking, CoachProfile coach, SessionCompletionData scd,
                                       WrapUpRequest req) {
        UUID bookingId = booking.getId();
        Editability verdict = recapEditability(booking, coach, Instant.now(ClockProvider.getClock()));
        if (!verdict.editable()) {
            // The development side (SLU, timeline, homework) of this already-paid session is then never recorded
            // — an erased player / coach, a recap-locking dispute or the closed edit window all win over it.
            log.warn("[AWAITED_WRAP_UP_REFUSED] booking {} — wrap-up after the family's confirm refused ({}); "
                + "its SLU / timeline / homework will not be recorded", bookingId, verdict.reason());
            throw new OperationNotAllowedException("Recap cannot be edited", verdict.reason());
        }
        List<UUID> homeworkDrillIds = validateHomeworkDrills(req.homeworkDrillIds(), coach);
        scd.setPlayerAttended(Boolean.TRUE.equals(req.playerAttended()));
        scd.setEffortRating(req.effortRating());
        scd.setFocusRating(req.focusRating());
        scd.setTechniqueRating(req.techniqueRating());
        scd.setWhatWeDid(BookingService.sanitizeAndTruncate(contactDetailSanitizer, req.recapWhatWeDid(), 500));
        scd.setWhatsNext(BookingService.sanitizeAndTruncate(contactDetailSanitizer, req.recapWhatsNext(), 500));
        scd.setWhatWentWell(BookingService.sanitizeAndTruncate(contactDetailSanitizer, blankToNull(req.recapWhatWentWell()), 500));
        if (!homeworkDrillIds.isEmpty()) {
            try {
                scd.setHomeworkDrillIds(objectMapper.writeValueAsString(homeworkDrillIds));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize homeworkDrillIds for booking {}", bookingId, e);
            }
        }
        // The plan was locked at the family's confirm (SessionPlanService), so this snapshot is what was planned.
        scd.setPlanBlocksSnapshot(capturePlanBlocksSnapshot(bookingId));
        scd.setAwaitingWrapUp(false);
        completionDataRepository.save(scd);
        eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
            scd.isPlayerAttended(), scd.getEffortRating(), scd.getFocusRating(), scd.getTechniqueRating(),
            homeworkDrillIds, scd.getWhatWeDid(), scd.getWhatsNext(), scd.getWhatWentWell(),
            BookingCompletedEvent.Scope.DEVELOPMENT_ONLY));
    }

    /**
     * skillars-deferred-163 AC2.4: the QUICK-mode session plan's blocks as they stand right now
     * (wrap-up time) — drill refs, their order and each block's duration, so that BOTH the timeline's
     * drill list and SLU (which needs per-block durations) are computed from this one snapshot and
     * cannot disagree (code review 2026-10-27, Decision 1). A missing or CANCELLED plan snapshots as
     * {@code "[]"}, never {@code null}: readers treat {@code null} as "no snapshot, use the live plan",
     * so a plan created after wrap-up would otherwise leak in. Refs with a null drill id are dropped
     * here, matching the readers' own null-id filtering. {@code null} only if serialisation fails.
     */
    private String capturePlanBlocksSnapshot(UUID bookingId) {
        List<SessionBlockData> blocks = sessionRepository.findByBookingId(bookingId)
            .filter(session -> !SessionStatus.CANCELLED.equals(session.getStatus()))
            .map(Session::getBlocks)
            .orElse(null);
        List<SessionBlockData> snapshot = blocks == null ? List.of() : blocks.stream()
            .filter(Objects::nonNull)
            .map(block -> new SessionBlockData(block.blockType(), block.blockName(), block.durationMinutes(),
                block.drills() == null ? null : block.drills().stream()
                    .filter(ref -> ref != null && ref.drillId() != null)
                    .toList()))
            .toList();
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize plan-blocks snapshot for booking {}", bookingId, e);
            return null;
        }
    }

    /**
     * skillars-deferred-159 AC2: every homework drill must be an ACTIVE PLATFORM drill or one of this
     * coach's own ACTIVE PRIVATE drills ({@link DrillUsagePolicy#isAssignableAsHomework}, the same rule
     * {@code DrillSuggestionService.allCandidates} applies). Unknown, foreign-PRIVATE, archived and null ids all get the same 404 so the response
     * never confirms that another coach's drill exists. Returns the de-duplicated list, order kept.
     */
    private List<UUID> validateHomeworkDrills(List<UUID> requested, CoachProfile coach) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.stream().anyMatch(Objects::isNull)) {
            throw new ResourceNotFoundException("Homework drill not found", "drill");
        }
        List<UUID> distinct = requested.stream().distinct().toList();
        Set<UUID> usable = drillRepository.findAllById(distinct).stream()
            .filter(d -> DrillUsagePolicy.isAssignableAsHomework(d, coach.getId()))
            .map(Drill::getId)
            .collect(Collectors.toSet());
        if (!usable.containsAll(distinct)) {
            throw new ResourceNotFoundException("Homework drill not found", "drill");
        }
        return distinct;
    }

    /**
     * skillars-deferred-153 AC1: shared by the stuck-completion sweeper (SYSTEM) and the admin
     * force-complete action (ADMIN) for a booking left in COMPLETED_PENDING_CONFIRMATION with no
     * SessionCompletionData row at all (an abandoned wrap-up). Modeled on {@link #submitWrapUp},
     * not {@link #confirmCompletion} — that method only reads an existing row and hard-fails when
     * it's missing, while this one must build one. {@code playerAttended = false}: the wizard's
     * attendance checkbox is never persisted until the final submit, so there is no real signal to
     * recover for an abandoned session; {@code false} is the conservative default (mirrors
     * TimelineEventListener's own {@code !isPlayerAttended() → null coachId} principle for exactly
     * this question). Every rating/recap/voice-note field is left {@code null} — nullable at the
     * schema level, so no migration is needed; the row's presence signals "this booking completed,"
     * its null recap fields signal "no coach-authored recap was ever captured."
     *
     * <p>Releases the coach's payout, exactly as {@code QuickCompleteTimeoutService}'s own
     * timeout-based auto-confirm already does — see {@code CoachPayoutEnqueueListener}'s Javadoc.
     *
     * <p><b>Exception classification belongs to the callers, not here (code review, 2026-10-17).</b>
     * An earlier revision caught {@code DataIntegrityViolationException} around the {@code save} and
     * {@code BookingStateTransitionException}/{@code OptimisticLockingFailureException} around the
     * transition, returning normally from each. Neither could work. The DIV catch was unreachable:
     * {@code SessionCompletionData.id} uses {@code @GeneratedValue(GenerationType.UUID)}, an in-VM
     * generator, so {@code persist} only queues the INSERT and nothing flushes before this method
     * returns — the UNIQUE violation surfaces at commit, outside any catch here. And swallowing the
     * transition exception could not help either: {@code BookingService.transition} is
     * {@code @Transactional} REQUIRED, so by the time it propagates the shared transaction is
     * already rollback-only and a normal return just fails at commit with
     * {@code UnexpectedRollbackException}. This repo documents that rule twice — see
     * {@code PendingProviderAssetTracker} and {@code ReviewSubmissionService}. So the two realistic
     * cases are now cheap pre-checks that return an outcome, and a genuine concurrent race is left
     * to propagate to the caller's own boundary, where it is actually catchable (the sweeper catches
     * it outside its {@code TransactionTemplate}, mirroring {@code QuickCompleteTimeoutService}).
     *
     * @return which of the three outcomes occurred; never {@code null}
     */
    @Transactional
    public ForceCompleteOutcome forceCompleteWithPlaceholderData(UUID bookingId, ActorRole actorRole, Long actorUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);

        // Pre-checks, not race guards. The sweeper's own query already filters on both conditions, but
        // the admin endpoint accepts any bookingId straight from a support ticket. Without these, a
        // misdirected id would write the placeholder row first and only then have the state machine
        // reject the transition — leaving an orphan row that permanently blocks the coach's own
        // submitWrapUp through the session_completion_data_booking_id_key UNIQUE constraint.
        if (BookingStatus.valueOf(booking.getStatus()) != BookingStatus.COMPLETED_PENDING_CONFIRMATION) {
            return ForceCompleteOutcome.NOT_STUCK;
        }
        if (completionDataRepository.findByBookingId(bookingId).isPresent()) {
            return ForceCompleteOutcome.ALREADY_RESOLVED;
        }

        completeWithPlaceholderData(booking, actorRole, actorUserId, false);
        return ForceCompleteOutcome.FORCE_COMPLETED;
    }

    /**
     * Writes the placeholder completion row (see {@link #forceCompleteWithPlaceholderData}), completes the
     * booking and publishes {@link BookingCompletedEvent}. Shared with {@link #confirmCompletion}'s
     * no-wrap-up path (fix/audit3-completion-dispute-integrity), which passes {@code awaitingWrapUp}: the row is
     * flagged for the coach's later wrap-up to fill in, and only the settlement side is published (payout, plan
     * lock) — SLU, timeline and homework wait for that wrap-up ({@link #completeAwaitedWrapUp}). The caller has
     * checked the status and that no completion row exists.
     */
    private void completeWithPlaceholderData(Booking booking, ActorRole actorRole, Long actorUserId,
                                             boolean awaitingWrapUp) {
        UUID bookingId = booking.getId();
        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(bookingId);
        scd.setCoachId(booking.getCoachId());
        scd.setPlayerId(booking.getPlayerId());
        scd.setPlayerAttended(false);
        scd.setCompletionMode("QUICK");
        scd.setAwaitingWrapUp(awaitingWrapUp);
        completionDataRepository.save(scd);

        bookingService.transition(bookingId, BookingEvent.COMPLETE, new TransitionContext(actorRole, actorUserId));

        eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
            scd.isPlayerAttended(), null, null, null, List.of(), null, null, null,
            awaitingWrapUp ? BookingCompletedEvent.Scope.SETTLEMENT_ONLY : BookingCompletedEvent.Scope.FULL
        ));
    }

    // ---- skillars-12-7: recap lifecycle (edit / resume / nudge) -----------------------------------

    /** Upper bound on the missing-recap list; {@code count} still reports the true total. */
    static final int MISSING_RECAP_LIST_CAP = 50;

    private record Editability(boolean editable, BookingError reason) {}

    private long recapEditWindowDays() {
        return configService.getBoundedLong(ConfigBounds.BOOKING_RECAP_EDIT_WINDOW_DAYS);
    }

    /**
     * The single "may this coach write this recap right now" verdict, called by BOTH the GET (for the
     * {@code editable} flag) and the PUT, so the two can never disagree. Adds the erasure checks that the
     * pure {@link Booking#isRecapEditable} cannot know: GdprErasureService keeps the coach_profiles row
     * and only locks the user, so a profile that still resolves says nothing about erasure.
     */
    private Editability recapEditability(Booking booking, CoachProfile coach, Instant now) {
        // Two signals, both needed (skillars-12-7 code review, Decision 1(a)): the erasure's user lock
        // commits LAST (outer transaction) while its recap scrub commits first, so between the two only
        // the recap_data_erased_at tombstone -- written inside the scrub transaction -- is visible. Read
        // as a fresh scalar query AFTER updateRecap's SCD row lock, never from the cached entity.
        boolean coachErased = coachProfileRepository.isRecapDataErased(coach.getId())
            || (coach.getUserId() != null
                && userRepository.findById(coach.getUserId()).map(User::isLocked).orElse(true));
        if (coachErased) {
            return new Editability(false, BookingError.RECAP_NOT_EDITABLE_ERASED);
        }
        boolean playerErased = playerProfileRepository.findById(booking.getPlayerId())
            .map(p -> p.getDevelopmentDataErasedAt() != null).orElse(true);
        if (playerErased) {
            return new Editability(false, BookingError.RECAP_NOT_EDITABLE_ERASED);
        }
        // skillars-deferred-162 AC6.2: a disputed or refunded session is no longer a normal completed one.
        // After the erasure checks (erasure wins the error key), before the window check.
        if (bookingDisputeLookup.isRecapLockedByDispute(booking.getId())) {
            return new Editability(false, BookingError.RECAP_NOT_EDITABLE_DISPUTED);
        }
        if (!booking.isRecapEditable(now, recapEditWindowDays())) {
            return new Editability(false, BookingError.RECAP_EDIT_WINDOW_CLOSED);
        }
        return new Editability(true, null);
    }

    @Transactional(readOnly = true)
    public SessionRecapResponse getRecap(UUID bookingId, Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        SessionCompletionData scd = completionDataRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Session completion data not found", "session_completion_data"));
        Instant now = Instant.now(ClockProvider.getClock());
        // fix/audit3-completion-dispute-integrity: an awaiting placeholder is completed through the wrap-up, never
        // the recap-only dialog (see updateRecap).
        Editability verdict = scd.isAwaitingWrapUp()
            ? new Editability(false, BookingError.WRAP_UP_AWAITED)
            : recapEditability(booking, coach, now);
        return toRecapResponse(booking, scd, verdict);
    }

    @Transactional
    public SessionRecapResponse updateRecap(UUID bookingId, Long coachUserId, UpdateSessionRecapRequest req) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);
        verifyStatus(booking, BookingStatus.COMPLETED);
        // Lock the row BEFORE the erasure checks: a concurrent player erasure (anonymizeByPlayerId) then
        // either finishes first (we see its tombstone below) or waits for us. Writes only this row, so
        // the REQUIRES_NEW same-row self-deadlock trap does not apply.
        SessionCompletionData scd = completionDataRepository.findForUpdateByBookingId(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Session completion data not found", "session_completion_data"));
        if (scd.isAwaitingWrapUp()) {
            // fix/audit3-completion-dispute-integrity: the family confirmed before the wrap-up. Saving the recap text
            // here would leave attendance / ratings / homework unrecorded and the development side never run —
            // point the coach at the wrap-up instead (submitWrapUp → completeAwaitedWrapUp).
            throw new OperationNotAllowedException("Finish the wrap-up for this session instead",
                BookingError.WRAP_UP_AWAITED);
        }
        Instant now = Instant.now(ClockProvider.getClock());
        Editability verdict = recapEditability(booking, coach, now);
        if (!verdict.editable()) {
            throw new OperationNotAllowedException("Recap cannot be edited", verdict.reason());
        }
        // skillars-deferred-163 AC2.3: sanitised exactly like submitWrapUp — this is the path AC2.2
        // now routes the sweeper/force-complete recovery flow into, so leaving it unsanitised would
        // defeat the point of sanitising the other write path.
        scd.setWhatWeDid(BookingService.sanitizeAndTruncate(contactDetailSanitizer, req.whatWeDid().trim(), 500));
        scd.setWhatsNext(BookingService.sanitizeAndTruncate(contactDetailSanitizer, req.whatsNext().trim(), 500));
        scd.setWhatWentWell(BookingService.sanitizeAndTruncate(contactDetailSanitizer, blankToNull(req.whatWentWell()), 500));
        completionDataRepository.save(scd);
        eventPublisher.publishEvent(new SessionRecapUpdatedEvent(this, bookingId, booking.getCoachId(), booking.getPlayerId()));
        return toRecapResponse(booking, scd, verdict);
    }

    private SessionRecapResponse toRecapResponse(Booking booking, SessionCompletionData scd, Editability verdict) {
        return new SessionRecapResponse(booking.getId(), scd.getWhatWeDid(), scd.getWhatsNext(),
            scd.getWhatWentWell(), verdict.editable(), booking.recapEditableUntil(recapEditWindowDays()),
            verdict.reason() == null ? null : verdict.reason().getErrorCode());
    }

    @Transactional(readOnly = true)
    public MissingRecapsResponse getMissingRecaps(Long coachUserId) {
        CoachProfile coach = resolveCoach(coachUserId);
        Instant cutoff = Instant.now(ClockProvider.getClock()).minus(java.time.Duration.ofDays(recapEditWindowDays()));
        long total = bookingRepository.countBookingsMissingRecap(coach.getId(), cutoff);
        if (total == 0) {
            return new MissingRecapsResponse(0, List.of());
        }
        List<Booking> bookings = bookingRepository.findBookingsMissingRecap(
            coach.getId(), cutoff, PageRequest.of(0, MISSING_RECAP_LIST_CAP));
        Map<Long, String> names = bookingService.resolvePlayerNames(
            bookings.stream().map(Booking::getPlayerId).distinct().toList());
        // fix/audit3-completion-dispute-integrity: one batched lookup — a session the family confirmed before the
        // wrap-up is listed too (no recap yet), flagged so the nudge resumes the wrap-up instead of the recap dialog.
        Set<UUID> awaiting = completionDataRepository.findByBookingIdIn(bookings.stream().map(Booking::getId).toList())
            .stream().filter(SessionCompletionData::isAwaitingWrapUp).map(SessionCompletionData::getBookingId)
            .collect(Collectors.toSet());
        List<MissingRecapItem> items = bookings.stream()
            .map(b -> new MissingRecapItem(b.getId(), b.getPlayerId(), names.get(b.getPlayerId()), b.getRequestedEndTime(),
                awaiting.contains(b.getId())))
            .toList();
        return new MissingRecapsResponse((int) total, items);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Transactional
    public void confirmCompletion(UUID bookingId, Long parentUserId) {
        Booking unlocked = bookingService.getBookingOrThrow(bookingId);
        if (!Objects.equals(unlocked.getParentId(), parentUserId)) {
            throw new ResourceNotFoundException("Booking not found", "booking");
        }
        // fix/audit3-completion-dispute-integrity: the family is now asked to confirm as soon as the coach
        // quick-completes, so they can confirm before any wrap-up exists. Lock the row first (after the
        // ownership check, so a non-owner cannot pin it): a concurrent submitWrapUp, which takes the same lock,
        // then either committed its completion row before this read or sees COMPLETED and gets its
        // "add the recap instead" answer — never a second completion row.
        Booking booking = bookingService.lockBookingForUpdate(bookingId);
        verifyStatus(booking, BookingStatus.COMPLETED_PENDING_CONFIRMATION);
        Optional<SessionCompletionData> existing = completionDataRepository.findByBookingId(bookingId);
        if (existing.isEmpty()) {
            // fix/audit3-completion-dispute-integrity: confirmed before the coach submitted a wrap-up. This used
            // to 404 after the transition. Complete (and pay) now with a placeholder row flagged awaitingWrapUp:
            // the coach's later wrap-up fills in attendance, ratings, homework and recap and records SLU /
            // timeline / homework then (submitWrapUp → completeAwaitedWrapUp).
            completeWithPlaceholderData(booking, ActorRole.PARENT, parentUserId, true);
            return;
        }
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE, new TransitionContext(ActorRole.PARENT, parentUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

        SessionCompletionData scd = existing.get();

        List<UUID> homeworkDrillIds = HomeworkDrillIds.parse(objectMapper, scd.getHomeworkDrillIds());
        eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
            scd.isPlayerAttended(), scd.getEffortRating(), scd.getFocusRating(), scd.getTechniqueRating(),
            homeworkDrillIds, scd.getWhatWeDid(), scd.getWhatsNext(), scd.getWhatWentWell()
        ));
    }

    private CoachProfile resolveCoach(Long coachUserId) {
        return coachProfileRepository.findByUserId(coachUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Coach profile not found", "coach_profile"));
    }

    private void verifyCoachOwnership(Booking booking, CoachProfile coach) {
        if (!Objects.equals(booking.getCoachId(), coach.getId())) {
            throw new OperationNotAllowedException("Coach does not own this booking", SecurityError.MISSING_RIGHTS);
        }
    }

    /**
     * skillars-deferred-153 AC1 (code review, 2026-10-17): recognises a row written by
     * {@code forceCompleteWithPlaceholderData} rather than by a real coach submission — all
     * recap fields null and the player recorded absent, which a genuine wrap-up never produces
     * (step 5 is mandatory, so at least {@code whatWeDid} is always set on a real submit).
     *
     * <p>skillars-deferred-163 AC6.1: no longer reads {@code voiceNoteText} — step 1 of a
     * three-release sequence to drop the column (see {@code migration-conventions.md} rule 2).
     * The entity field, both GDPR scrub references and this method's placeholder test without it
     * are unaffected: {@code whatWeDid}/{@code whatsNext} being null is already sufficient to
     * detect the placeholder signature.
     */
    private boolean isSweeperPlaceholder(SessionCompletionData existing) {
        return !existing.isAwaitingWrapUp()
            && !existing.isPlayerAttended()
            && existing.getWhatWeDid() == null
            && existing.getWhatsNext() == null;
    }

    private void verifyStatus(Booking booking, BookingStatus expected) {
        BookingStatus current = BookingStatus.valueOf(booking.getStatus());
        if (current != expected) {
            throw new OperationNotAllowedException(
                "Booking is in status " + current + ", expected " + expected, SecurityError.MISSING_RIGHTS);
        }
    }

    // skillars-deferred-151 AC6: USER DECISION — gate all three UPCOMING-gated coach actions on the
    // booking's scheduled start time, not just recordNoShowCoach (BookingService.java), which already
    // has this exact check. Mirrors that method's own Instant.now()/isBefore shape character-for-
    // character rather than introducing a second, differently-worded time check.
    private void verifyNotBeforeStartTime(Booking booking) {
        if (Instant.now().isBefore(booking.getRequestedStartTime())) {
            throw new OperationNotAllowedException(
                "Cannot start or complete a session before the booking's scheduled start time",
                Map.of("requested start time", booking.getRequestedStartTime()),
                BookingError.SESSION_NOT_YET_STARTED);
        }
    }
}
