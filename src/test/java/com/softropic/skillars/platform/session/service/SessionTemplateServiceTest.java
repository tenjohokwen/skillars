package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.booking.contract.BookingSnapshot;
import com.softropic.skillars.platform.booking.service.BookingQueryService;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.contract.SessionPlanResponse;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import com.softropic.skillars.platform.session.repo.SessionTemplate;
import com.softropic.skillars.platform.session.repo.SessionTemplateRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-162 AC4.2/AC4.3: deployTemplate saves a session directly (not through
 * SessionPlanService), so it carries its own copy of the plan-path drill-ownership rule.
 */
@ExtendWith(MockitoExtension.class)
class SessionTemplateServiceTest {

    @Mock private SessionTemplateRepository sessionTemplateRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private DrillRepository drillRepository;
    @Mock private CoachProfileService coachProfileService;
    @Mock private DrillLibraryService drillLibraryService;
    @Mock private BookingQueryService bookingQueryService;

    private SessionTemplateService service;

    private static final Long COACH_USER_ID = 9600000020L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID OTHER_COACH_ID = UUID.randomUUID();
    private static final UUID TEMPLATE_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID OWN_DRILL = UUID.randomUUID();
    private static final UUID FOREIGN_DRILL = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SessionTemplateService(sessionTemplateRepository, sessionRepository, drillRepository,
            coachProfileService, drillLibraryService, bookingQueryService);
    }

    private static DrillMetadata metadata() {
        return new DrillMetadata(List.of("PASSING"), List.of(), Map.of(), 5, 3, 2, 2, 3, false,
            "INTERMEDIATE", List.of("cones"), "SMALL", List.of("keep it tight"), null);
    }

    private static Drill privateDrill(UUID id, UUID owner) {
        Drill d = new Drill();
        d.setId(id);
        d.setName("Drill " + id);
        d.setLibraryType("PRIVATE");
        d.setOwnerCoachId(owner);
        d.setStatus("ACTIVE");
        d.setMetadata(metadata());
        return d;
    }

    private static List<SessionBlockData> blocks(UUID... drillIds) {
        List<SessionDrillRef> refs = new java.util.ArrayList<>();
        for (int i = 0; i < drillIds.length; i++) refs.add(new SessionDrillRef(drillIds[i], i));
        return List.of(new SessionBlockData("MAIN", "Main", 20, refs));
    }

    private void stubDeployableTemplate(List<SessionBlockData> blocks) {
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        SessionTemplate t = new SessionTemplate();
        t.setId(TEMPLATE_ID);
        t.setCoachId(COACH_ID);
        t.setName("Tuesday");
        t.setStatus("ACTIVE");
        t.setBlocks(blocks);
        when(sessionTemplateRepository.findByIdAndCoachId(TEMPLATE_ID, COACH_ID)).thenReturn(Optional.of(t));
        when(bookingQueryService.getBookingSnapshot(BOOKING_ID))
            .thenReturn(Optional.of(new BookingSnapshot(BOOKING_ID, COACH_ID, 500L, "CONFIRMED")));
        when(sessionRepository.existsByBookingId(BOOKING_ID)).thenReturn(false);
    }

    private static DrillResponse drillResponse(UUID id) {
        return new DrillResponse(id, "Drill " + id, "desc", "PRIVATE", null, "ACTIVE",
            metadata(), false, null, null, Instant.now(), List.of(), null, null, false);
    }

    @Test
    void deployTemplate_withAnotherCoachsPrivateDrill_is404_andNoSessionIsSaved() {
        stubDeployableTemplate(blocks(OWN_DRILL, FOREIGN_DRILL));
        when(drillRepository.findAllById(any())).thenReturn(
            List.of(privateDrill(OWN_DRILL, COACH_ID), privateDrill(FOREIGN_DRILL, OTHER_COACH_ID)));

        assertThatThrownBy(() -> service.deployTemplate(TEMPLATE_ID, BOOKING_ID, COACH_USER_ID))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(sessionRepository, never()).save(any());
        verify(sessionTemplateRepository, never()).incrementDeployCount(any(), any());
    }

    @Test
    void deployTemplate_withOwnDrills_savesTheSession() {
        stubDeployableTemplate(blocks(OWN_DRILL));
        when(drillRepository.findAllById(any())).thenReturn(List.of(privateDrill(OWN_DRILL, COACH_ID)));
        when(sessionRepository.save(any(Session.class))).thenAnswer(inv -> inv.getArgument(0));
        when(drillLibraryService.toResponse(any(Drill.class), eq(false), any(), any(), any(), any()))
            .thenAnswer(inv -> drillResponse(((Drill) inv.getArgument(0)).getId()));

        SessionPlanResponse response = service.deployTemplate(TEMPLATE_ID, BOOKING_ID, COACH_USER_ID);

        assertThat(response.blocks().get(0).drills()).extracting(d -> d.drill().id()).containsExactly(OWN_DRILL);
        verify(sessionTemplateRepository).incrementDeployCount(eq(TEMPLATE_ID), any());
    }

    @Test
    void buildResponse_omitsAnotherCoachsPrivateDrill() {
        Session session = new Session();
        session.setCoachId(COACH_ID);
        session.setBlocks(blocks(OWN_DRILL, FOREIGN_DRILL));
        when(drillRepository.findAllById(any())).thenReturn(
            List.of(privateDrill(OWN_DRILL, COACH_ID), privateDrill(FOREIGN_DRILL, OTHER_COACH_ID)));
        when(drillLibraryService.toResponse(any(Drill.class), eq(false), any(), any(), any(), any()))
            .thenAnswer(inv -> drillResponse(((Drill) inv.getArgument(0)).getId()));

        SessionPlanResponse response = ReflectionTestUtils.invokeMethod(service, "buildResponse", session);

        assertThat(response.blocks().get(0).drills())
            .extracting(d -> d.drill() != null ? d.drill().id() : null)
            .containsExactly(OWN_DRILL, null);
    }
}
