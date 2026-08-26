package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingCompletedEvent;
import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.session.contract.CreateSessionPlanRequest;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockRequest;
import com.softropic.skillars.platform.session.contract.SessionDnaScore;
import com.softropic.skillars.platform.session.contract.SessionDrillRefRequest;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.contract.UpdateSessionPlanRequest;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No test class for SessionPlanService existed before skillars-deferred-22 AC4 — this is
 * greenfield coverage, not an addition to an existing net. Its primary job is to pin the
 * drillRepository.findAllById dedup: buildResponse used to independently re-fetch the same
 * drill ids resolveMetaMap had just fetched.
 */
@ExtendWith(MockitoExtension.class)
class SessionPlanServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private DrillRepository drillRepository;
    @Mock private BookingQueryService bookingQueryService;
    @Mock private CoachProfileService coachProfileService;
    @Mock private DrillLibraryService drillLibraryService;
    @Mock private SessionDnaCalculator dnaCalculator;
    @Mock private EquipmentListService equipmentListService;

    private SessionPlanService service;

    private static final Long COACH_USER_ID = 9600000010L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID DRILL_ID_1 = UUID.randomUUID();
    private static final UUID DRILL_ID_2 = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SessionPlanService(sessionRepository, drillRepository, bookingQueryService,
            coachProfileService, drillLibraryService, dnaCalculator, equipmentListService);
    }

    private DrillMetadata metadata() {
        return new DrillMetadata(List.of("PASSING"), List.of(), Map.of(), 5, 3, 2, 2, 3, false,
            "INTERMEDIATE", List.of("cones"), "SMALL", List.of("keep it tight"), null);
    }

    private Drill drill(UUID id) {
        Drill d = new Drill();
        d.setId(id);
        d.setName("Drill " + id);
        d.setDescription("desc");
        d.setLibraryType("PLATFORM");
        d.setStatus("ACTIVE");
        d.setMetadata(metadata());
        return d;
    }

    private DrillResponse drillResponse(UUID id) {
        return new DrillResponse(id, "Drill " + id, "desc", "PLATFORM", null, "ACTIVE",
            metadata(), false, null, null, Instant.now(), List.of(), null, null, false);
    }

    private SessionBlockRequest blockRequest(UUID... drillIds) {
        List<SessionDrillRefRequest> refs = new ArrayList<>();
        int order = 0;
        for (UUID id : drillIds) {
            refs.add(new SessionDrillRefRequest(id, order++));
        }
        return new SessionBlockRequest("WARMUP", "Warmup", 10, refs);
    }

    private void stubDrillFetchAndDerivedResponses() {
        List<Drill> drills = List.of(drill(DRILL_ID_1), drill(DRILL_ID_2));
        when(drillRepository.findAllById(any())).thenReturn(drills);
        when(drillLibraryService.toResponse(any(Drill.class), eq(false), any(), any(), any(), any()))
            .thenAnswer(inv -> drillResponse(((Drill) inv.getArgument(0)).getId()));
        when(dnaCalculator.calculate(any())).thenReturn(new SessionDnaScore(1, 1, 1, 1, 1));
        when(equipmentListService.generate(any())).thenReturn(List.of("cones"));
    }

    private void assertBothDrillsPresent(SessionPlanResponse response) {
        assertThat(response.blocks()).hasSize(1);
        assertThat(response.blocks().get(0).drills()).hasSize(2);
        assertThat(response.blocks().get(0).drills())
            .extracting(d -> d.drill() != null ? d.drill().id() : null)
            .containsExactlyInAnyOrder(DRILL_ID_1, DRILL_ID_2);
    }

    // ── createSession ──────────────────────────────────────────────────────

    @Test
    void createSession_returnsExpectedResponse_fetchesDrillsExactlyOnce() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        BookingSnapshot booking = new BookingSnapshot(BOOKING_ID, COACH_ID, 500L, "CONFIRMED");
        when(bookingQueryService.getBookingSnapshot(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(sessionRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
        stubDrillFetchAndDerivedResponses();
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> {
            Session s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            return s;
        });

        CreateSessionPlanRequest req = new CreateSessionPlanRequest(BOOKING_ID,
            List.of(blockRequest(DRILL_ID_1, DRILL_ID_2)), List.of("PASSING"));

        SessionPlanResponse response = service.createSession(req, COACH_USER_ID);

        assertThat(response.bookingId()).isEqualTo(BOOKING_ID);
        assertBothDrillsPresent(response);
        verify(drillRepository, times(1)).findAllById(any());
    }

    @Test
    void createSession_blockWithNoDrills_skipsRepositoryFetchEntirely() {
        // fetchDrills short-circuits to List.of() when no block references any drill — proving
        // that path never issues a findAllById call at all (not just "once" but "zero").
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        BookingSnapshot booking = new BookingSnapshot(BOOKING_ID, COACH_ID, 500L, "CONFIRMED");
        when(bookingQueryService.getBookingSnapshot(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(sessionRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
        when(dnaCalculator.calculate(any())).thenReturn(new SessionDnaScore(0, 0, 0, 0, 0));
        when(equipmentListService.generate(any())).thenReturn(List.of());
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> {
            Session s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            s.setCreatedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            return s;
        });

        CreateSessionPlanRequest req = new CreateSessionPlanRequest(BOOKING_ID,
            List.of(blockRequest()), List.of("PASSING"));

        SessionPlanResponse response = service.createSession(req, COACH_USER_ID);

        assertThat(response.blocks()).hasSize(1);
        assertThat(response.blocks().get(0).drills()).isEmpty();
        verify(drillRepository, never()).findAllById(any());
    }

    // ── updateSession ──────────────────────────────────────────────────────

    @Test
    void updateSession_returnsExpectedResponse_fetchesDrillsExactlyOnce() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        UUID sessionId = UUID.randomUUID();
        Session existing = new Session();
        existing.setId(sessionId);
        existing.setCoachId(COACH_ID);
        existing.setBookingId(BOOKING_ID);
        existing.setPlayerId(500L);
        existing.setStatus("DRAFT");
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));
        stubDrillFetchAndDerivedResponses();
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateSessionPlanRequest req = new UpdateSessionPlanRequest(
            List.of(blockRequest(DRILL_ID_1, DRILL_ID_2)), List.of("PASSING"), "SAVED");

        SessionPlanResponse response = service.updateSession(sessionId, req, COACH_USER_ID);

        assertThat(response.status()).isEqualTo("SAVED");
        assertBothDrillsPresent(response);
        verify(drillRepository, times(1)).findAllById(any());
    }

    @Test
    void updateSession_notOwnedByCoach_throws() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        UUID sessionId = UUID.randomUUID();
        Session existing = new Session();
        existing.setId(sessionId);
        existing.setCoachId(UUID.randomUUID());
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));

        UpdateSessionPlanRequest req = new UpdateSessionPlanRequest(
            List.of(blockRequest(DRILL_ID_1)), List.of("PASSING"), "SAVED");

        assertThatThrownBy(() -> service.updateSession(sessionId, req, COACH_USER_ID))
            .isInstanceOf(com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException.class);
        verify(drillRepository, times(0)).findAllById(any());
    }

    // ── getSession ─────────────────────────────────────────────────────────

    @Test
    void getSession_returnsExpectedResponse_fetchesDrillsExactlyOnce() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        UUID sessionId = UUID.randomUUID();
        Session existing = new Session();
        existing.setId(sessionId);
        existing.setCoachId(COACH_ID);
        existing.setBookingId(BOOKING_ID);
        existing.setPlayerId(500L);
        existing.setStatus("DRAFT");
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        existing.setBlocks(List.of(new com.softropic.skillars.platform.session.contract.SessionBlockData(
            "WARMUP", "Warmup", 10,
            List.of(new com.softropic.skillars.platform.session.contract.SessionDrillRef(DRILL_ID_1, 0),
                new com.softropic.skillars.platform.session.contract.SessionDrillRef(DRILL_ID_2, 1)))));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));

        List<Drill> drills = List.of(drill(DRILL_ID_1), drill(DRILL_ID_2));
        when(drillRepository.findAllById(any())).thenReturn(drills);
        when(drillLibraryService.toResponse(any(Drill.class), eq(false), any(), any(), any(), any()))
            .thenAnswer(inv -> drillResponse(((Drill) inv.getArgument(0)).getId()));

        SessionPlanResponse response = service.getSession(sessionId, COACH_USER_ID);

        assertBothDrillsPresent(response);
        verify(drillRepository, times(1)).findAllById(any());
    }

    @Test
    void getSession_notOwnedByCoach_throwsResourceNotFound() {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        UUID sessionId = UUID.randomUUID();
        Session existing = new Session();
        existing.setId(sessionId);
        existing.setCoachId(UUID.randomUUID());
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.getSession(sessionId, COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(drillRepository, times(0)).findAllById(any());
    }

    // ── handleBookingCompleted ─────────────────────────────────────────────

    @Test
    void handleBookingCompleted_draftSessionFound_transitionsToCompletedAndSaves() {
        Session existing = new Session();
        existing.setId(UUID.randomUUID());
        existing.setBookingId(BOOKING_ID);
        existing.setStatus("DRAFT");
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));

        BookingCompletedEvent event = new BookingCompletedEvent(this, BOOKING_ID, COACH_ID, 500L,
            null, true, 3, 3, 3, List.of());

        service.handleBookingCompleted(event);

        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        verify(sessionRepository, times(1)).save(existing);
    }

    @Test
    void handleBookingCompleted_noSessionForBooking_noOp() {
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        BookingCompletedEvent event = new BookingCompletedEvent(this, BOOKING_ID, COACH_ID, 500L,
            null, true, 3, 3, 3, List.of());

        service.handleBookingCompleted(event);

        verify(sessionRepository, never()).save(any(Session.class));
    }

    @Test
    void handleBookingCompleted_alreadyCompleted_noRedundantSave() {
        Session existing = new Session();
        existing.setId(UUID.randomUUID());
        existing.setBookingId(BOOKING_ID);
        existing.setStatus("COMPLETED");
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));

        BookingCompletedEvent event = new BookingCompletedEvent(this, BOOKING_ID, COACH_ID, 500L,
            null, true, 3, 3, 3, List.of());

        service.handleBookingCompleted(event);

        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        verify(sessionRepository, never()).save(any(Session.class));
    }

    @Test
    void handleBookingCompleted_saveThrowsDataIntegrityViolation_isCaughtAndDoesNotPropagate() {
        Session existing = new Session();
        existing.setId(UUID.randomUUID());
        existing.setBookingId(BOOKING_ID);
        existing.setStatus("DRAFT");
        when(sessionRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(existing));
        doThrow(new DataIntegrityViolationException("constraint violation"))
            .when(sessionRepository).save(existing);

        BookingCompletedEvent event = new BookingCompletedEvent(this, BOOKING_ID, COACH_ID, 500L,
            null, true, 3, 3, 3, List.of());

        service.handleBookingCompleted(event);

        verify(sessionRepository, times(1)).save(existing);
    }
}
