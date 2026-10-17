package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.booking.contract.ActorRole;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.QuickCompleteConfirmationRequiredEvent;
import com.softropic.skillars.platform.booking.contract.TransitionContext;
import com.softropic.skillars.platform.booking.contract.WrapUpRequest;
import com.softropic.skillars.platform.booking.repo.Booking;
import com.softropic.skillars.platform.booking.repo.SessionCompletionData;
import com.softropic.skillars.platform.booking.repo.SessionCompletionDataRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingCompletionService {

    private final BookingService bookingService;
    private final SessionCompletionDataRepository completionDataRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final UserRepository userRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

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
    }

    @Transactional
    public void submitWrapUp(UUID bookingId, Long coachUserId, WrapUpRequest req) {
        CoachProfile coach = resolveCoach(coachUserId);
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        verifyCoachOwnership(booking, coach);

        // skillars-deferred-153 AC1 (code review, 2026-10-17): distinguish "the stuck-completion
        // sweeper already auto-completed this booking" from a generic status mismatch. This is the one
        // case where the coach is about to lose recap text they just typed, and verifyStatus's raw
        // "Booking is in status COMPLETED, expected COMPLETED_PENDING_CONFIRMATION" tells them nothing
        // actionable. The transition is one-way (BookingStateMachine has no path back to
        // COMPLETED_PENDING_CONFIRMATION) and there is no update path for an existing row, so the
        // honest message is that the session is closed — not that something went wrong.
        if (BookingStatus.valueOf(booking.getStatus()) == BookingStatus.COMPLETED) {
            Optional<SessionCompletionData> existing = completionDataRepository.findByBookingId(bookingId);
            if (existing.isPresent() && isSweeperPlaceholder(existing.get())) {
                throw new OperationNotAllowedException(
                    "This session was auto-completed after the wrap-up was left unsubmitted and can no longer be edited",
                    SecurityError.MISSING_RIGHTS);
            }
        }
        verifyStatus(booking, BookingStatus.COMPLETED_PENDING_CONFIRMATION);

        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(bookingId);
        scd.setCoachId(coach.getId());
        scd.setPlayerId(booking.getPlayerId());
        scd.setPlayerAttended(Boolean.TRUE.equals(req.playerAttended()));
        scd.setEffortRating(req.effortRating());
        scd.setFocusRating(req.focusRating());
        scd.setTechniqueRating(req.techniqueRating());
        scd.setVoiceNoteText(req.voiceNoteText());
        scd.setCompletionMode(req.mode());
        scd.setWhatWeDid(req.recapWhatWeDid());
        scd.setWhatsNext(req.recapWhatsNext());

        if (req.homeworkDrillIds() != null && !req.homeworkDrillIds().isEmpty()) {
            try {
                scd.setHomeworkDrillIds(objectMapper.writeValueAsString(req.homeworkDrillIds()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize homeworkDrillIds for booking {}", bookingId, e);
            }
        }
        try {
            completionDataRepository.save(scd);
        } catch (DataIntegrityViolationException e) {
            // Idempotency guard: a previous request already saved completion data for this booking
            log.warn("Session completion data already exists for booking {} — ignoring duplicate submitWrapUp", bookingId);
            return;
        }

        if ("LIVE".equals(req.mode())) {
            try {
                bookingService.transition(bookingId, BookingEvent.QUICK_COMPLETE, new TransitionContext(ActorRole.COACH, coachUserId));
            } catch (OptimisticLockingFailureException e) {
                throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
            }
            eventPublisher.publishEvent(new BookingCompletedEvent(
                this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
                scd.isPlayerAttended(), req.effortRating(), req.focusRating(), req.techniqueRating(),
                req.homeworkDrillIds() != null ? req.homeworkDrillIds() : List.of(),
                req.recapWhatWeDid(), req.recapWhatsNext()
            ));
        } else {
            String parentEmail = userRepository.findById(booking.getParentId())
                .map(u -> u.getEmail()).orElse("");
            String coachDisplayName = coach.getDisplayName();
            String playerName = playerProfileRepository.findById(booking.getPlayerId())
                .map(PlayerProfile::getName).orElse("Player");
            eventPublisher.publishEvent(new QuickCompleteConfirmationRequiredEvent(
                this, bookingId, booking.getParentId(), parentEmail, coachDisplayName,
                booking.getRequestedStartTime(), booking.getCanonicalTimezone(), playerName
            ));
        }
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

        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(bookingId);
        scd.setCoachId(booking.getCoachId());
        scd.setPlayerId(booking.getPlayerId());
        scd.setPlayerAttended(false);
        scd.setCompletionMode("QUICK");
        completionDataRepository.save(scd);

        bookingService.transition(bookingId, BookingEvent.COMPLETE, new TransitionContext(actorRole, actorUserId));

        eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
            scd.isPlayerAttended(), null, null, null, List.of(), null, null
        ));
        return ForceCompleteOutcome.FORCE_COMPLETED;
    }

    @Transactional
    public void confirmCompletion(UUID bookingId, Long parentUserId) {
        Booking booking = bookingService.getBookingOrThrow(bookingId);
        if (!Objects.equals(booking.getParentId(), parentUserId)) {
            throw new ResourceNotFoundException("Booking not found", "booking");
        }
        verifyStatus(booking, BookingStatus.COMPLETED_PENDING_CONFIRMATION);
        try {
            bookingService.transition(bookingId, BookingEvent.COMPLETE, new TransitionContext(ActorRole.PARENT, parentUserId));
        } catch (OptimisticLockingFailureException e) {
            throw new OperationNotAllowedException(BookingError.CONCURRENT_MODIFICATION_MESSAGE, e, BookingError.CONCURRENT_MODIFICATION);
        }

        SessionCompletionData scd = completionDataRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Session completion data not found", "session_completion_data"));

        List<UUID> homeworkDrillIds = deserializeHomeworkDrillIds(scd.getHomeworkDrillIds());
        eventPublisher.publishEvent(new BookingCompletedEvent(
            this, bookingId, booking.getCoachId(), booking.getPlayerId(), booking.getParentId(),
            scd.isPlayerAttended(), scd.getEffortRating(), scd.getFocusRating(), scd.getTechniqueRating(),
            homeworkDrillIds, scd.getWhatWeDid(), scd.getWhatsNext()
        ));
    }

    @SuppressWarnings("unchecked")
    private List<UUID> deserializeHomeworkDrillIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> ids = objectMapper.readValue(json, List.class);
            return ids.stream().map(UUID::fromString).toList();
        } catch (Exception e) {
            log.warn("Failed to deserialize homeworkDrillIds from JSON '{}': {}", json, e.getMessage());
            return List.of();
        }
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
     * {@code forceCompleteWithPlaceholderData} rather than by a real coach submission — all three
     * recap fields null and the player recorded absent, which a genuine wrap-up never produces
     * (step 5 is mandatory, so at least {@code whatWeDid} is always set on a real submit).
     */
    private boolean isSweeperPlaceholder(SessionCompletionData existing) {
        return !existing.isPlayerAttended()
            && existing.getWhatWeDid() == null
            && existing.getWhatsNext() == null
            && existing.getVoiceNoteText() == null;
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
