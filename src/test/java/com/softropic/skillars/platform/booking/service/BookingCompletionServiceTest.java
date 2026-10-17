package com.softropic.skillars.platform.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingError;
import com.softropic.skillars.platform.booking.contract.BookingEvent;
import com.softropic.skillars.platform.booking.contract.BookingStateTransitionException;
import com.softropic.skillars.platform.booking.contract.BookingStatus;
import com.softropic.skillars.platform.booking.contract.ForceCompleteOutcome;
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
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BookingCompletionServiceTest {

    @Mock private BookingService bookingService;
    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BookingCompletionService service;

    private static final UUID BOOKING_ID   = UUID.randomUUID();
    private static final UUID COACH_ID     = UUID.randomUUID();
    private static final Long COACH_USER_ID = 100L;
    private static final Long PLAYER_ID    = 200L;
    private static final Long PARENT_ID    = 300L;

    private CoachProfile coach;
    private Booking booking;

    @BeforeEach
    void setUp() {
        service = new BookingCompletionService(
            bookingService, completionDataRepository,
            coachProfileRepository, userRepository, playerProfileRepository,
            eventPublisher, new ObjectMapper()
        );

        coach = new CoachProfile();
        coach.setId(COACH_ID);
        coach.setUserId(COACH_USER_ID);
        coach.setDisplayName("Test Coach");

        booking = new Booking();
        booking.setCoachId(COACH_ID);
        booking.setPlayerId(PLAYER_ID);
        booking.setParentId(PARENT_ID);
        // skillars-deferred-151 AC6: an explicit past offset, not a bare Instant.now() — the new
        // SESSION_NOT_YET_STARTED time gate calls Instant.now() again at check time, and relying on
        // two separate Instant.now() calls to land in the "right" order is this project's own
        // documented Instant.now()-pair flake class (see chk_bkg_end_after_start's history). Several
        // tests below set status to UPCOMING and expect startSession/initiateQuickComplete to
        // proceed past this gate.
        booking.setRequestedStartTime(Instant.now().minusSeconds(1));
        booking.setCanonicalTimezone("Europe/Berlin");
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());

        lenient().when(coachProfileRepository.findByUserId(COACH_USER_ID)).thenReturn(Optional.of(coach));
        lenient().when(bookingService.getBookingOrThrow(BOOKING_ID)).thenReturn(booking);
    }

    @Test
    void submitWrapUp_liveMode_completesBookingAndDeductsCredit() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, null, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills");

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.QUICK_COMPLETE), any(TransitionContext.class));

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BookingCompletedEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.isPlayerAttended()).isTrue();
        // skillars-12-2 code review (P5): the LIVE-mode publish reads the recap straight from
        // the request's two trailing args — assert both land on the right getter, not transposed.
        assertThat(event.getWhatWeDid()).isEqualTo("Worked on first touch");
        assertThat(event.getWhatsNext()).isEqualTo("Continue weak-foot drills");
    }

    @Test
    void submitWrapUp_liveMode_doesNotTouchAnySessionPackDependency() {
        // Regression guard for the accept/completion double-deduction bug (Story 11.2 Task 2):
        // BookingCompletionService must have no dependency on PackSessionService,
        // SessionPackPurchaseRepository, or legacy SessionPackService at all — accept-time
        // deduction is owned entirely by PaymentLifecycleService. Asserting this via reflection
        // on declared fields is a stronger guard than a runtime mock-verify: it fails at the
        // class level the moment such a dependency is reintroduced, regardless of whether any
        // test happens to exercise the code path that would call it.
        List<String> dependencyTypeNames = java.util.Arrays.stream(BookingCompletionService.class.getDeclaredFields())
            .map(f -> f.getType().getSimpleName())
            .toList();

        assertThat(dependencyTypeNames)
            .doesNotContain("PackSessionService", "SessionPackPurchaseRepository", "SessionPackService");
    }

    @Test
    void submitWrapUp_quickMode_doesNotTransitionStatusAndPublishesConfirmationEvent() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 3, 4, 4, "Good session", List.of(), "QUICK",
            "Worked on passing accuracy", "Focus on finishing next session");

        User parent = new User();
        parent.setEmail("parent@test.com");
        PlayerProfile player = new PlayerProfile();
        player.setName("Test Player");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.QUICK_COMPLETE), any());

        ArgumentCaptor<QuickCompleteConfirmationRequiredEvent> captor =
            ArgumentCaptor.forClass(QuickCompleteConfirmationRequiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        QuickCompleteConfirmationRequiredEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.getParentEmail()).isEqualTo("parent@test.com");
        assertThat(event.getPlayerName()).isEqualTo("Test Player");
    }

    @Test
    void confirmCompletion_validParent_firesCompleteTransitionAndDeductsCredit() {
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());

        SessionCompletionData scd = new SessionCompletionData();
        scd.setPlayerAttended(true);
        scd.setWhatWeDid("Worked on passing accuracy");
        scd.setWhatsNext("Focus on finishing next session");
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(scd));

        service.confirmCompletion(BOOKING_ID, PARENT_ID);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE), any(TransitionContext.class));

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BookingCompletedEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.getWhatWeDid()).isEqualTo("Worked on passing accuracy");
        assertThat(event.getWhatsNext()).isEqualTo("Focus on finishing next session");
    }

    @Test
    void startSession_bookingNotUpcoming_throwsException() {
        booking.setStatus(BookingStatus.IN_PROGRESS.name());

        assertThatThrownBy(() -> service.startSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.START), any());
    }

    @Test
    void startSession_coachDoesNotOwnBooking_throwsException() {
        booking.setCoachId(UUID.randomUUID()); // different coach
        booking.setStatus(BookingStatus.UPCOMING.name());

        assertThatThrownBy(() -> service.startSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
    }

    // skillars-deferred-151 AC6: neither startSession nor initiateQuickComplete had a time-based
    // gate before this fix, unlike their sibling BookingService.recordNoShowCoach.
    @Test
    void startSession_beforeScheduledStartTime_throwsSessionNotYetStarted() {
        booking.setStatus(BookingStatus.UPCOMING.name());
        booking.setRequestedStartTime(Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> service.startSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.SESSION_NOT_YET_STARTED);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.START), any());
    }

    @Test
    void initiateQuickComplete_beforeScheduledStartTime_throwsSessionNotYetStarted() {
        booking.setStatus(BookingStatus.UPCOMING.name());
        booking.setRequestedStartTime(Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> service.initiateQuickComplete(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.SESSION_NOT_YET_STARTED);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.COMPLETE_PENDING), any());
    }

    @Test
    void initiateQuickComplete_upcomingBooking_firesCompletePendingTransition() {
        booking.setStatus(BookingStatus.UPCOMING.name());

        service.initiateQuickComplete(BOOKING_ID, COACH_USER_ID);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE_PENDING), any(TransitionContext.class));
    }

    @Test
    void startSession_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.UPCOMING.name());
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.START), any(TransitionContext.class));

        assertThatThrownBy(() -> service.startSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    @Test
    void initiateQuickComplete_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.UPCOMING.name());
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE_PENDING), any(TransitionContext.class));

        assertThatThrownBy(() -> service.initiateQuickComplete(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    @Test
    void submitWrapUp_liveMode_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, null, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills");
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.QUICK_COMPLETE), any(TransitionContext.class));

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    @Test
    void endSession_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.IN_PROGRESS.name());
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE_PENDING), any(TransitionContext.class));

        assertThatThrownBy(() -> service.endSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    @Test
    void pauseSession_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.IN_PROGRESS.name());
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.PAUSE), any(TransitionContext.class));

        assertThatThrownBy(() -> service.pauseSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    @Test
    void resumeSession_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.PAUSED.name());
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.RESUME), any(TransitionContext.class));

        assertThatThrownBy(() -> service.resumeSession(BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    @Test
    void confirmCompletion_concurrentModification_throwsRetryableException() {
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());
        doThrow(new OptimisticLockingFailureException("stale version"))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE), any(TransitionContext.class));

        assertThatThrownBy(() -> service.confirmCompletion(BOOKING_ID, PARENT_ID))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasCauseInstanceOf(OptimisticLockingFailureException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode()).isEqualTo(BookingError.CONCURRENT_MODIFICATION);
    }

    // skillars-deferred-153 AC1
    @Test
    void forceCompleteWithPlaceholderData_savesPlaceholderRowAndTransitionsToComplete() {
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // skillars-deferred-153 code review (2026-10-17): returns an outcome, not void.
        ForceCompleteOutcome outcome = service.forceCompleteWithPlaceholderData(
            BOOKING_ID, com.softropic.skillars.platform.booking.contract.ActorRole.SYSTEM, null);
        assertThat(outcome).isEqualTo(ForceCompleteOutcome.FORCE_COMPLETED);

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        SessionCompletionData saved = scdCaptor.getValue();
        assertThat(saved.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(saved.getCoachId()).isEqualTo(COACH_ID);
        assertThat(saved.getPlayerId()).isEqualTo(PLAYER_ID);
        assertThat(saved.isPlayerAttended()).isFalse();
        assertThat(saved.getCompletionMode()).isEqualTo("QUICK");
        assertThat(saved.getEffortRating()).isNull();
        assertThat(saved.getWhatWeDid()).isNull();

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE),
            eq(new TransitionContext(com.softropic.skillars.platform.booking.contract.ActorRole.SYSTEM, null)));

        ArgumentCaptor<BookingCompletedEvent> eventCaptor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(eventCaptor.getValue().isPlayerAttended()).isFalse();
    }

    @Test
    void forceCompleteWithPlaceholderData_admin_passesAdminActorThrough() {
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ForceCompleteOutcome outcome = service.forceCompleteWithPlaceholderData(
            BOOKING_ID, com.softropic.skillars.platform.booking.contract.ActorRole.ADMIN, 999L);

        assertThat(outcome).isEqualTo(ForceCompleteOutcome.FORCE_COMPLETED);
        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE),
            eq(new TransitionContext(com.softropic.skillars.platform.booking.contract.ActorRole.ADMIN, 999L)));
    }

    // skillars-deferred-153 code review (2026-10-17): RE-AUTHORED. This previously stubbed save() to
    // throw DataIntegrityViolationException and asserted a benign skip — behavior that no longer
    // exists (and never really could; see forceCompleteWithPlaceholderData's Javadoc on why the DIV
    // catch was unreachable with an in-VM @GeneratedValue id generator). The realistic "a real
    // wrap-up landed first" case is now a cheap pre-check that returns ALREADY_RESOLVED before any
    // write at all.
    @Test
    void forceCompleteWithPlaceholderData_completionDataAlreadyExists_returnsAlreadyResolvedWithoutWriting() {
        SessionCompletionData existing = new SessionCompletionData();
        existing.setBookingId(BOOKING_ID);
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));

        ForceCompleteOutcome outcome = service.forceCompleteWithPlaceholderData(
            BOOKING_ID, com.softropic.skillars.platform.booking.contract.ActorRole.SYSTEM, null);

        assertThat(outcome).isEqualTo(ForceCompleteOutcome.ALREADY_RESOLVED);
        verify(completionDataRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), eq(BookingEvent.COMPLETE), any());
        verify(eventPublisher, never()).publishEvent(any(BookingCompletedEvent.class));
    }

    // skillars-deferred-153 code review (2026-10-17): RE-AUTHORED, same reason as above — the status
    // mismatch is now a pre-check returning NOT_STUCK, so no orphan placeholder row is written for a
    // misdirected bookingId (which would have permanently blocked the coach's own submitWrapUp
    // through the session_completion_data_booking_id_key UNIQUE constraint).
    @Test
    void forceCompleteWithPlaceholderData_bookingNotPendingConfirmation_returnsNotStuckWithoutWriting() {
        booking.setStatus(BookingStatus.COMPLETED.name());

        ForceCompleteOutcome outcome = service.forceCompleteWithPlaceholderData(
            BOOKING_ID, com.softropic.skillars.platform.booking.contract.ActorRole.SYSTEM, null);

        assertThat(outcome).isEqualTo(ForceCompleteOutcome.NOT_STUCK);
        // The NOT_STUCK pre-check runs BEFORE the completion-data read, so that read never happens.
        verify(completionDataRepository, never()).findByBookingId(any());
        verify(completionDataRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), eq(BookingEvent.COMPLETE), any());
        verify(eventPublisher, never()).publishEvent(any(BookingCompletedEvent.class));
    }

    // skillars-deferred-153 code review (2026-10-17): a genuine concurrent race must PROPAGATE to the
    // caller's own transaction boundary, where it is actually catchable, instead of being swallowed
    // here and failing at commit with UnexpectedRollbackException. StuckCompletionSweeperService
    // catches exactly these three types OUTSIDE its TransactionTemplate.
    @Test
    void forceCompleteWithPlaceholderData_transitionLosesRace_propagatesToCaller() {
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new BookingStateTransitionException(BookingStatus.COMPLETED, BookingEvent.COMPLETE))
            .when(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.COMPLETE), any(TransitionContext.class));

        assertThatThrownBy(() -> service.forceCompleteWithPlaceholderData(
                BOOKING_ID, com.softropic.skillars.platform.booking.contract.ActorRole.SYSTEM, null))
            .isInstanceOf(BookingStateTransitionException.class);

        verify(eventPublisher, never()).publishEvent(any(BookingCompletedEvent.class));
    }

    @Test
    void forceCompleteWithPlaceholderData_duplicateRowConstraintViolation_propagatesToCaller() {
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
            .when(completionDataRepository).save(any());

        assertThatThrownBy(() -> service.forceCompleteWithPlaceholderData(
                BOOKING_ID, com.softropic.skillars.platform.booking.contract.ActorRole.SYSTEM, null))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        verify(bookingService, never()).transition(any(), eq(BookingEvent.COMPLETE), any());
        verify(eventPublisher, never()).publishEvent(any(BookingCompletedEvent.class));
    }

    // skillars-deferred-153 AC1 code review (2026-10-17): NEW — the coach-facing half of the sweeper.
    // A coach who finishes the wizard after the sweeper already auto-completed the booking must get
    // an actionable message, not verifyStatus's raw "Booking is in status COMPLETED, expected
    // COMPLETED_PENDING_CONFIRMATION".
    @Test
    void submitWrapUp_bookingAutoCompletedBySweeper_throwsNoLongerEditable() {
        booking.setStatus(BookingStatus.COMPLETED.name());
        SessionCompletionData placeholder = new SessionCompletionData();
        placeholder.setBookingId(BOOKING_ID);
        placeholder.setPlayerAttended(false);   // all four isSweeperPlaceholder conditions
        placeholder.setCompletionMode("QUICK"); // whatWeDid/whatsNext/voiceNoteText left null
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(placeholder));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, null, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills");

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("auto-completed after the wrap-up was left unsubmitted");

        verify(completionDataRepository, never()).save(any());
    }

    // The guard is deliberately narrow: a COMPLETED booking whose existing row is a REAL coach
    // submission (recap text present) must still fall through to verifyStatus's generic message —
    // the placeholder-specific wording would be a lie there.
    @Test
    void submitWrapUp_bookingCompletedWithRealWrapUpRow_fallsThroughToGenericStatusMismatch() {
        booking.setStatus(BookingStatus.COMPLETED.name());
        SessionCompletionData real = new SessionCompletionData();
        real.setBookingId(BOOKING_ID);
        real.setPlayerAttended(true);
        real.setWhatWeDid("Worked on first touch");
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(real));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, null, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills");

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("Booking is in status COMPLETED, expected COMPLETED_PENDING_CONFIRMATION");

        verify(completionDataRepository, never()).save(any());
    }
}
