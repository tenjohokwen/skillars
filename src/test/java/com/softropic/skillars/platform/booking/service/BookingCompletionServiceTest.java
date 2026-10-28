package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;

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
    @Mock private com.softropic.skillars.platform.booking.repo.BookingRepository bookingRepository;
    @Mock private com.softropic.skillars.platform.config.service.ConfigService configService;
    @Mock private SessionCompletionDataRepository completionDataRepository;
    @Mock private CoachProfileRepository coachProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.softropic.skillars.platform.session.repo.DrillRepository drillRepository;
    // skillars-deferred-162 AC6: unstubbed -> false, i.e. no dispute locks the recap.
    @Mock private com.softropic.skillars.platform.booking.contract.BookingDisputeLookup bookingDisputeLookup;
    @Mock private com.softropic.skillars.platform.session.repo.SessionRepository sessionRepository;
    @Mock private com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer contactDetailSanitizer;

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
            bookingService, bookingRepository, completionDataRepository,
            coachProfileRepository, userRepository, playerProfileRepository,
            eventPublisher, new ObjectMapper(), configService, drillRepository, bookingDisputeLookup,
            sessionRepository, contactDetailSanitizer
        );
        // skillars-deferred-163 AC2.3: passthrough default, matching BookingServiceTest's own
        // convention — tests exercising the actual redaction/truncation wiring override this.
        lenient().when(contactDetailSanitizer.sanitize(anyString())).thenAnswer(
            inv -> new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                inv.getArgument(0), false));

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
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills", null);

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.QUICK_COMPLETE), any(TransitionContext.class));

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BookingCompletedEvent event = captor.getValue();
        assertThat(event.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(event.isPlayerAttended()).isTrue();
        // skillars-12-2 code review (P5): assert both land on the right getter, not transposed.
        // skillars-deferred-163 AC2.3: the LIVE-mode publish now reads from the saved (sanitised)
        // scd values rather than the raw request — the passthrough sanitizer stub in setUp makes
        // these equal the request text here; see the dedicated sanitization test below for the
        // case where they differ.
        assertThat(event.getWhatWeDid()).isEqualTo("Worked on first touch");
        assertThat(event.getWhatsNext()).isEqualTo("Continue weak-foot drills");
    }

    /**
     * skillars-deferred-163 AC2.3: the LIVE-branch event must carry the SAVED scd values, not the
     * raw request — this is the one case where they can actually differ (the sanitizer redacts).
     */
    @Test
    void submitWrapUp_liveMode_eventCarriesSanitisedScdValues_notRawRequestText() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(contactDetailSanitizer.sanitize("Call me at 555-0100-123 about first touch")).thenReturn(
            new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                "[contact details removed] about first touch", true));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Call me at 555-0100-123 about first touch", "Continue weak-foot drills", null);

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        assertThat(scdCaptor.getValue().getWhatWeDid()).isEqualTo("[contact details removed] about first touch");

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getWhatWeDid()).isEqualTo("[contact details removed] about first touch");
    }

    /**
     * skillars-deferred-163 AC2.3: both submitWrapUp and updateRecap must sanitise — a
     * contact-detail-bearing recap is redacted before save, on either write path.
     */
    @Test
    void submitWrapUp_sanitizesRecapTextBeforeSave() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(contactDetailSanitizer.sanitize("email me at coach@example.com")).thenReturn(
            new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                "[contact details removed]", true));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "email me at coach@example.com", "Continue weak-foot drills", null);

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        assertThat(scdCaptor.getValue().getWhatWeDid()).isEqualTo("[contact details removed]");
        verify(contactDetailSanitizer).sanitize("email me at coach@example.com");
    }

    /**
     * skillars-deferred-163 AC2.1: an admin force-complete (not just the sweeper) produces the
     * identical placeholder signature, so a coach resuming after EITHER cause gets the same
     * dedicated error — built end-to-end through forceCompleteWithPlaceholderData itself, not a
     * hand-built placeholder, to prove the two methods' placeholder shapes actually agree.
     */
    @Test
    void submitWrapUp_afterAdminForceComplete_throwsSameDedicatedRecapStillEditableCode() {
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());
        ArgumentCaptor<SessionCompletionData> savedCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        when(completionDataRepository.save(savedCaptor.capture())).thenAnswer(i -> i.getArgument(0));

        service.forceCompleteWithPlaceholderData(BOOKING_ID,
            com.softropic.skillars.platform.booking.contract.ActorRole.ADMIN, 999L);

        SessionCompletionData placeholder = savedCaptor.getValue();
        arrangeEditableRecap(Instant.now().minusSeconds(3600)); // -> COMPLETED, recap still editable
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(placeholder));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills", null);

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE);
    }

    /**
     * skillars-deferred-163 AC2.4 (code review 2026-10-27, Decision 1): QUICK's session plan stays
     * editable until the delayed BookingCompletedEvent eventually fires, so the plan's BLOCKS (drill refs
     * and per-block durations, everything SLU needs) are snapshotted at wrap-up time. LIVE's event fires
     * immediately, so no snapshot is needed there.
     */
    @Test
    void submitWrapUp_quickMode_capturesPlanBlocksSnapshot() throws Exception {
        UUID drillId = UUID.randomUUID();
        var session = new com.softropic.skillars.platform.session.repo.Session();
        session.setStatus(com.softropic.skillars.platform.session.repo.SessionStatus.SAVED);
        session.setBlocks(List.of(new com.softropic.skillars.platform.session.contract.SessionBlockData(
            "WARMUP", "Warmup", 10, List.of(new com.softropic.skillars.platform.session.contract.SessionDrillRef(drillId, 0)))));
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(session));

        SessionCompletionData saved = submitQuickWrapUp();

        assertThat(readBlocks(saved.getPlanBlocksSnapshot())).containsExactly(
            new com.softropic.skillars.platform.session.contract.SessionBlockData(
                "WARMUP", "Warmup", 10, List.of(new com.softropic.skillars.platform.session.contract.SessionDrillRef(drillId, 0))));
    }

    /**
     * No plan at wrap-up snapshots as "[]", never null: readers treat null as "use the live plan", so a
     * plan created after wrap-up would otherwise leak into the timeline and SLU.
     */
    @Test
    void submitWrapUp_quickModeWithNoPlan_capturesEmptySnapshotNotNull() {
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        assertThat(submitQuickWrapUp().getPlanBlocksSnapshot()).isEqualTo("[]");
    }

    @Test
    void submitWrapUp_quickModeWithCancelledPlan_capturesEmptySnapshot() {
        var session = new com.softropic.skillars.platform.session.repo.Session();
        session.setStatus(com.softropic.skillars.platform.session.repo.SessionStatus.CANCELLED);
        session.setBlocks(List.of(new com.softropic.skillars.platform.session.contract.SessionBlockData(
            "MAIN", "Main", 30, List.of(new com.softropic.skillars.platform.session.contract.SessionDrillRef(UUID.randomUUID(), 0)))));
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(session));

        assertThat(submitQuickWrapUp().getPlanBlocksSnapshot()).isEqualTo("[]");
    }

    /** A ref with a null drill id is dropped at capture, so it can never poison the snapshot's readers. */
    @Test
    void submitWrapUp_quickMode_dropsRefsWithANullDrillId() throws Exception {
        UUID drillId = UUID.randomUUID();
        var session = new com.softropic.skillars.platform.session.repo.Session();
        session.setStatus(com.softropic.skillars.platform.session.repo.SessionStatus.DRAFT);
        session.setBlocks(List.of(new com.softropic.skillars.platform.session.contract.SessionBlockData(
            "MAIN", "Main", 30, List.of(
                new com.softropic.skillars.platform.session.contract.SessionDrillRef(null, 0),
                new com.softropic.skillars.platform.session.contract.SessionDrillRef(drillId, 1)))));
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(session));

        var blocks = readBlocks(submitQuickWrapUp().getPlanBlocksSnapshot());

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).drills()).containsExactly(
            new com.softropic.skillars.platform.session.contract.SessionDrillRef(drillId, 1));
    }

    @Test
    void submitWrapUp_liveMode_doesNotCapturePlanBlocksSnapshot() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE", "did", "next", null);

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        assertThat(scdCaptor.getValue().getPlanBlocksSnapshot()).isNull();
        verifyNoInteractions(sessionRepository);
    }

    private SessionCompletionData submitQuickWrapUp() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        User parent = new User();
        parent.setEmail("parent@test.com");
        when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(new PlayerProfile()));

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID,
            new WrapUpRequest(true, 4, 3, 5, List.of(), "QUICK", "did", "next", null));

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        return scdCaptor.getValue();
    }

    private static List<com.softropic.skillars.platform.session.contract.SessionBlockData> readBlocks(String json)
            throws Exception {
        return new ObjectMapper().readValue(json,
            new com.fasterxml.jackson.core.type.TypeReference<List<com.softropic.skillars.platform.session.contract.SessionBlockData>>() {});
    }

    private static com.softropic.skillars.platform.session.repo.Drill drill(UUID id, String library, UUID owner, String status) {
        var d = new com.softropic.skillars.platform.session.repo.Drill();
        d.setId(id);
        d.setLibraryType(library);
        d.setOwnerCoachId(owner);
        d.setStatus(status);
        return d;
    }

    private WrapUpRequest wrapUpWithHomework(String mode, List<UUID> ids) {
        return new WrapUpRequest(true, 4, 3, 5, ids, mode, "did", "next", null);
    }

    private void assertHomeworkRejected(List<UUID> ids) {
        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, wrapUpWithHomework("LIVE", ids)))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(completionDataRepository, never()).save(any());
        verify(bookingService, never()).transition(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void submitWrapUp_homework_ownPrivateAndPlatformActive_accepted() {
        UUID own = UUID.randomUUID();
        UUID platform = UUID.randomUUID();
        when(drillRepository.findAllById(List.of(own, platform))).thenReturn(List.of(
            drill(own, "PRIVATE", COACH_ID, "ACTIVE"), drill(platform, "PLATFORM", null, "ACTIVE")));
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, wrapUpWithHomework("LIVE", List.of(own, platform)));

        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getHomeworkDrillIds()).containsExactly(own, platform);
    }

    @Test
    void submitWrapUp_homework_otherCoachsPrivateDrill_rejectedWithNoSaveAndNoTransition() {
        UUID foreign = UUID.randomUUID();
        when(drillRepository.findAllById(List.of(foreign))).thenReturn(List.of(
            drill(foreign, "PRIVATE", UUID.randomUUID(), "ACTIVE")));
        assertHomeworkRejected(List.of(foreign));
    }

    @Test
    void submitWrapUp_homework_validPlusForeignPrivate_wholeRequestRejected() {
        UUID platform = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(drillRepository.findAllById(List.of(platform, foreign))).thenReturn(List.of(
            drill(platform, "PLATFORM", null, "ACTIVE"), drill(foreign, "PRIVATE", UUID.randomUUID(), "ACTIVE")));
        assertHomeworkRejected(List.of(platform, foreign));
    }

    @Test
    void submitWrapUp_homework_unknownId_rejected() {
        UUID unknown = UUID.randomUUID();
        when(drillRepository.findAllById(List.of(unknown))).thenReturn(List.of());
        assertHomeworkRejected(List.of(unknown));
    }

    @Test
    void submitWrapUp_homework_archivedDrill_rejected() {
        UUID archived = UUID.randomUUID();
        when(drillRepository.findAllById(List.of(archived))).thenReturn(List.of(
            drill(archived, "PLATFORM", null, "ARCHIVED")));
        assertHomeworkRejected(List.of(archived));
    }

    @Test
    void submitWrapUp_homework_nullElement_rejected() {
        assertHomeworkRejected(java.util.Collections.singletonList(null));
    }

    @Test
    void submitWrapUp_homework_duplicateIds_acceptedAndPersistedOnce() {
        UUID a = UUID.randomUUID();
        when(drillRepository.findAllById(List.of(a))).thenReturn(List.of(drill(a, "PLATFORM", null, "ACTIVE")));
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, wrapUpWithHomework("LIVE", List.of(a, a)));

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        assertThat(scdCaptor.getValue().getHomeworkDrillIds()).isEqualTo("[\"" + a + "\"]");
        ArgumentCaptor<BookingCompletedEvent> captor = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getHomeworkDrillIds()).containsExactly(a);
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
        WrapUpRequest req = new WrapUpRequest(true, 3, 4, 4, List.of(), "QUICK",
            "Worked on passing accuracy", "Focus on finishing next session", null);

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

    /** Code review (Decision 2): the live start is recorded so an abandoned wrap-up resumes LIVE. */
    @Test
    void startSession_upcomingBooking_recordsLiveStartedAt() {
        booking.setStatus(BookingStatus.UPCOMING.name());

        service.startSession(BOOKING_ID, COACH_USER_ID);

        verify(bookingService).transition(eq(BOOKING_ID), eq(BookingEvent.START), any(TransitionContext.class));
        assertThat(booking.getLiveStartedAt()).isNotNull();
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
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills", null);
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
    // A coach who finishes the wizard after the sweeper (or an admin force-complete) already
    // auto-completed the booking must get an actionable, dedicated error — not verifyStatus's raw
    // "Booking is in status COMPLETED, expected COMPLETED_PENDING_CONFIRMATION", and not a message
    // that falsely claims the sweeper is the exclusive cause (skillars-deferred-163 AC2.1).
    @Test
    void submitWrapUp_bookingAutoCompletedBySweeper_throwsDedicatedRecapStillEditableCode() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600)); // code review 2026-10-27: PUT /recap would accept
        SessionCompletionData placeholder = new SessionCompletionData();
        placeholder.setBookingId(BOOKING_ID);
        placeholder.setPlayerAttended(false);   // all isSweeperPlaceholder conditions
        placeholder.setCompletionMode("QUICK"); // whatWeDid/whatsNext left null
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(placeholder));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills", null);

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_STILL_EDITABLE_AFTER_FORCE_COMPLETE);

        verify(completionDataRepository, never()).save(any());
    }

    /**
     * Code review 2026-10-27: the redirect is offered only when PUT /recap would actually accept the
     * text. A placeholder booking past the recap edit window gets that verdict's own reason instead of a
     * "you can still save it" redirect into a read-only dialog.
     */
    @Test
    void submitWrapUp_placeholderButRecapWindowClosed_throwsTheVerdictReasonNotTheRedirect() {
        arrangeEditableRecap(Instant.now().minusSeconds(40L * 86_400)); // past the 30-day window
        SessionCompletionData placeholder = new SessionCompletionData();
        placeholder.setBookingId(BOOKING_ID);
        placeholder.setPlayerAttended(false);
        placeholder.setCompletionMode("QUICK");
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(placeholder));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills", null);

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_EDIT_WINDOW_CLOSED);
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
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE",
            "Worked on first touch", "Continue weak-foot drills", null);

        assertThatThrownBy(() -> service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("Booking is in status COMPLETED, expected COMPLETED_PENDING_CONFIRMATION");

        verify(completionDataRepository, never()).save(any());
    }

    // skillars-deferred-165 AC12: submitWrapUp_placeholderWithLegacyVoiceNoteText_isStillRecognisedAsPlaceholder
    // removed here (step 2 of 3) -- its whole premise was an in-memory SessionCompletionData
    // carrying a voiceNoteText, which is no longer constructible once the field itself is gone.
    // isSweeperPlaceholder's own condition already ignored this field entirely (deferred-163 AC6.1,
    // step 1), so no other test covered a behavior this deletion could silently lose.

    // ---- skillars-12-7 ----

    @Test
    void submitWrapUp_storesWhatWentWellTrimmed_andEventCarriesIt() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        WrapUpRequest req = new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE", "did", "next", "  Great tempo  ");

        service.submitWrapUp(BOOKING_ID, COACH_USER_ID, req);

        ArgumentCaptor<SessionCompletionData> scd = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scd.capture());
        assertThat(scd.getValue().getWhatWentWell()).isEqualTo("Great tempo");
        ArgumentCaptor<BookingCompletedEvent> ev = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().getWhatWentWell()).isEqualTo("Great tempo");
    }

    @Test
    void submitWrapUp_blankWhatWentWell_isStoredAsNull() {
        when(completionDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        service.submitWrapUp(BOOKING_ID, COACH_USER_ID,
            new WrapUpRequest(true, 4, 3, 5, List.of(), "LIVE", "did", "next", "   "));

        ArgumentCaptor<SessionCompletionData> scd = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scd.capture());
        assertThat(scd.getValue().getWhatWentWell()).isNull();
    }

    @Test
    void confirmCompletion_eventCarriesWhatWentWell() {
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());
        SessionCompletionData scd = new SessionCompletionData();
        scd.setWhatWeDid("did");
        scd.setWhatsNext("next");
        scd.setWhatWentWell("well");
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(scd));

        service.confirmCompletion(BOOKING_ID, PARENT_ID);

        ArgumentCaptor<BookingCompletedEvent> ev = ArgumentCaptor.forClass(BookingCompletedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().getWhatWentWell()).isEqualTo("well");
    }

    private void arrangeEditableRecap(Instant end) {
        booking.setId(BOOKING_ID);
        booking.setStatus(BookingStatus.COMPLETED.name());
        booking.setRequestedEndTime(end);
        User coachUser = new User();
        coachUser.setLocked(false);
        lenient().when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(coachUser));
        PlayerProfile player = new PlayerProfile();
        lenient().when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        lenient().when(configService.getBoundedLong(ConfigBounds.BOOKING_RECAP_EDIT_WINDOW_DAYS))
            .thenReturn(30L);
        SessionCompletionData scd = new SessionCompletionData();
        scd.setBookingId(BOOKING_ID);
        lenient().when(completionDataRepository.findForUpdateByBookingId(BOOKING_ID)).thenReturn(Optional.of(scd));
    }

    @Test
    void updateRecap_inWindow_writesRowLocksItFirstAndPublishesIdsOnlyEvent() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));

        var response = service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("  did ", " next ", " "));

        assertThat(response.whatWeDid()).isEqualTo("did");
        assertThat(response.whatWentWell()).isNull();
        assertThat(response.editable()).isTrue();
        verify(completionDataRepository).save(any(SessionCompletionData.class));
        ArgumentCaptor<com.softropic.skillars.platform.booking.contract.SessionRecapUpdatedEvent> ev =
            ArgumentCaptor.forClass(com.softropic.skillars.platform.booking.contract.SessionRecapUpdatedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(ev.getValue().getCoachId()).isEqualTo(COACH_ID);
        assertThat(ev.getValue().getPlayerId()).isEqualTo(PLAYER_ID);
    }

    /** skillars-deferred-163 AC2.3/AC2.5: updateRecap sanitises too — the path AC2.2's recovery flow saves through. */
    @Test
    void updateRecap_sanitizesRecapTextBeforeSave() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        when(contactDetailSanitizer.sanitize("email me at coach@example.com")).thenReturn(
            new com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer.SanitizerResult(
                "[contact details removed]", true));

        var response = service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest(
                "  email me at coach@example.com ", "next", null));

        ArgumentCaptor<SessionCompletionData> scdCaptor = ArgumentCaptor.forClass(SessionCompletionData.class);
        verify(completionDataRepository).save(scdCaptor.capture());
        assertThat(scdCaptor.getValue().getWhatWeDid()).isEqualTo("[contact details removed]");
        assertThat(response.whatWeDid()).isEqualTo("[contact details removed]");
        // trimmed BEFORE sanitising, matching updateRecap's pre-existing trim behavior
        verify(contactDetailSanitizer).sanitize("email me at coach@example.com");
    }

    @Test
    void updateRecap_outsideWindow_throwsDedicatedCode_andGetReportsNotEditable() {
        arrangeEditableRecap(Instant.now().minusSeconds(40L * 86_400));
        var req = new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null);

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_EDIT_WINDOW_CLOSED);
        verify(completionDataRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        // the read side uses the SAME verdict
        SessionCompletionData existing = new SessionCompletionData();
        existing.setBookingId(BOOKING_ID);
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));
        assertThat(service.getRecap(BOOKING_ID, COACH_USER_ID).editable()).isFalse();
    }

    // skillars-deferred-162 AC6.2/AC6.6: a dispute-locked recap is refused with its own key, on GET and PUT alike.
    @Test
    void updateRecap_lockedByDispute_throwsDisputedCode_andGetReportsNotEditable() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        when(bookingDisputeLookup.isRecapLockedByDispute(BOOKING_ID)).thenReturn(true);
        var req = new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null);

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_NOT_EDITABLE_DISPUTED);
        verify(completionDataRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        SessionCompletionData existing = new SessionCompletionData();
        existing.setBookingId(BOOKING_ID);
        when(completionDataRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));
        assertThat(service.getRecap(BOOKING_ID, COACH_USER_ID).editable()).isFalse();
    }

    @Test
    void updateRecap_erasedPlayerAndDisputed_reportsErasureFirst() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        PlayerProfile erased = new PlayerProfile();
        erased.setDevelopmentDataErasedAt(Instant.now());
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(erased));
        lenient().when(bookingDisputeLookup.isRecapLockedByDispute(BOOKING_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null)))
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_NOT_EDITABLE_ERASED);
    }

    @Test
    void updateRecap_disputedButOutsideWindow_reportsDisputeBeforeWindow() {
        arrangeEditableRecap(Instant.now().minusSeconds(40L * 86_400));
        when(bookingDisputeLookup.isRecapLockedByDispute(BOOKING_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null)))
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_NOT_EDITABLE_DISPUTED);
    }

    /** Code review (Decision 1(a)): the scrub-transaction tombstone alone refuses the edit, user not yet locked. */
    @Test
    void updateRecap_coachRecapTombstone_isRejectedAsErased_evenWhileUserStillUnlocked() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        when(coachProfileRepository.isRecapDataErased(COACH_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_NOT_EDITABLE_ERASED);
        verify(completionDataRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void updateRecap_lockedCoachUser_isRejectedAsErased_eventWhenProfileStillResolves() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        User locked = new User();
        locked.setLocked(true);
        when(userRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_NOT_EDITABLE_ERASED);
        verify(completionDataRepository, never()).save(any());
    }

    @Test
    void updateRecap_erasedPlayer_isRejected_nothingResurrected() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        PlayerProfile erased = new PlayerProfile();
        erased.setDevelopmentDataErasedAt(Instant.now());
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(erased));

        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID,
            new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null)))
            .isInstanceOf(OperationNotAllowedException.class)
            .extracting(t -> ((OperationNotAllowedException) t).getErrorCode())
            .isEqualTo(BookingError.RECAP_NOT_EDITABLE_ERASED);
        verify(completionDataRepository, never()).save(any());
    }

    @Test
    void updateRecap_wrongCoach_orNotCompleted_isRejectedBeforeAnyLock() {
        arrangeEditableRecap(Instant.now().minusSeconds(3600));
        booking.setCoachId(UUID.randomUUID());
        var req = new com.softropic.skillars.platform.booking.contract.UpdateSessionRecapRequest("d", "n", null);
        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);

        booking.setCoachId(COACH_ID);
        booking.setStatus(BookingStatus.COMPLETED_PENDING_CONFIRMATION.name());
        assertThatThrownBy(() -> service.updateRecap(BOOKING_ID, COACH_USER_ID, req))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(completionDataRepository, never()).findForUpdateByBookingId(any());
    }

    @Test
    void getMissingRecaps_zeroCount_skipsTheListQuery() {
        when(configService.getBoundedLong(ConfigBounds.BOOKING_RECAP_EDIT_WINDOW_DAYS)).thenReturn(30L);
        when(bookingRepository.countBookingsMissingRecap(eq(COACH_ID), any())).thenReturn(0L);

        var response = service.getMissingRecaps(COACH_USER_ID);

        assertThat(response.count()).isZero();
        assertThat(response.items()).isEmpty();
        verify(bookingRepository, never()).findBookingsMissingRecap(any(), any(), any());
    }

    @Test
    void getMissingRecaps_returnsItemsWithBatchedNames() {
        when(configService.getBoundedLong(ConfigBounds.BOOKING_RECAP_EDIT_WINDOW_DAYS)).thenReturn(30L);
        when(bookingRepository.countBookingsMissingRecap(eq(COACH_ID), any())).thenReturn(1L);
        booking.setId(BOOKING_ID);
        booking.setRequestedEndTime(Instant.now().minusSeconds(3600));
        when(bookingRepository.findBookingsMissingRecap(eq(COACH_ID), any(), any())).thenReturn(List.of(booking));
        when(bookingService.resolvePlayerNames(any())).thenReturn(java.util.Map.of(PLAYER_ID, "Alex"));

        var response = service.getMissingRecaps(COACH_USER_ID);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.items().get(0).playerName()).isEqualTo("Alex");
        assertThat(response.items().get(0).bookingId()).isEqualTo(BOOKING_ID);
    }
}
