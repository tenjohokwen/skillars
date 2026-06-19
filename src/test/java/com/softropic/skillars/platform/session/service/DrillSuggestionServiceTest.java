package com.softropic.skillars.platform.session.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import org.mockito.ArgumentCaptor;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import org.instancio.Instancio;

import static org.instancio.Select.field;
import com.softropic.skillars.platform.session.contract.DrillMetadata;
import com.softropic.skillars.platform.session.contract.DrillResponse;
import com.softropic.skillars.platform.session.contract.SessionBlockData;
import com.softropic.skillars.platform.session.contract.SessionDrillRef;
import com.softropic.skillars.platform.session.repo.Drill;
import com.softropic.skillars.platform.session.repo.DrillRepository;
import com.softropic.skillars.platform.session.repo.Session;
import com.softropic.skillars.platform.session.repo.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrillSuggestionServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private DrillRepository drillRepository;
    @Mock private DrillLibraryService drillLibraryService;
    @Mock private CoachProfileService coachProfileService;
    @Mock private NeglectedSkillFlagRepository neglectedSkillFlagRepository;

    private DrillSuggestionService service;

    private static final Long COACH_USER_ID = 9900000001L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Long PLAYER_ID = 1234L;

    @BeforeEach
    void setUp() {
        service = new DrillSuggestionService(sessionRepository, drillRepository, drillLibraryService, coachProfileService, neglectedSkillFlagRepository);
        when(neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(any())).thenReturn(List.of());
        when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
    }

    @Test
    void suggest_noFocus_returnsFallbackPlatformDrills() {
        Session session = buildSession(COACH_ID, List.of());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        Drill early = buildDrill(UUID.randomUUID(), "PLATFORM", null, Instant.ofEpochSecond(1000));
        Drill late  = buildDrill(UUID.randomUUID(), "PLATFORM", null, Instant.ofEpochSecond(2000));
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(late, early));
        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        List<DrillResponse> result = service.suggest(SESSION_ID, COACH_USER_ID, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(early.getId());
        assertThat(result.get(1).id()).isEqualTo(late.getId());
    }

    @Test
    void suggest_withTechnicalFocus_ranksHighIntensityDrillsFirst() {
        Session session = buildSession(COACH_ID, List.of("technical"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        UUID highIntensityId = UUID.randomUUID();
        UUID lowIntensityId  = UUID.randomUUID();
        Drill highDrill = buildDrillWithMetadata(highIntensityId, 5, 5, 1, 1, false);
        Drill lowDrill  = buildDrillWithMetadata(lowIntensityId,  1, 1, 1, 1, false);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(lowDrill, highDrill));
        when(drillRepository.findByOwnerCoachIdAndStatus(COACH_ID, "ACTIVE")).thenReturn(List.of());
        when(sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(PLAYER_ID)).thenReturn(List.of());
        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        List<DrillResponse> result = service.suggest(SESSION_ID, COACH_USER_ID, 10);

        assertThat(result.get(0).id()).isEqualTo(highIntensityId);
    }

    @Test
    void suggest_recentlyUsedDrill_isRankedLower() {
        Session session = buildSession(COACH_ID, List.of("technical"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        UUID recentId = UUID.randomUUID();
        UUID freshId  = UUID.randomUUID();
        Drill recentDrill = buildDrillWithMetadata(recentId, 5, 5, 1, 1, false);
        Drill freshDrill  = buildDrillWithMetadata(freshId,  5, 5, 1, 1, false);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(recentDrill, freshDrill));
        when(drillRepository.findByOwnerCoachIdAndStatus(COACH_ID, "ACTIVE")).thenReturn(List.of());

        Session recentSession = buildSession(COACH_ID, List.of("technical"));
        SessionDrillRef ref = new SessionDrillRef(recentId, 0);
        SessionBlockData block = new SessionBlockData("WARM_UP", "Warm-Up", 10, List.of(ref));
        recentSession.setBlocks(List.of(block));
        when(sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(PLAYER_ID)).thenReturn(List.of(recentSession));

        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        List<DrillResponse> result = service.suggest(SESSION_ID, COACH_USER_ID, 10);

        assertThat(result.get(0).id()).isEqualTo(freshId);
    }

    @Test
    void suggest_scoutCoach_throwsFeatureGatedException() {
        doThrow(new FeatureGatedException("session_builder", "INSTRUCTOR"))
            .when(drillLibraryService).checkSessionBuilderGate(COACH_USER_ID);

        assertThatThrownBy(() -> service.suggest(SESSION_ID, COACH_USER_ID, 10))
            .isInstanceOf(FeatureGatedException.class);

        verify(sessionRepository, never()).findById(any());
    }

    @Test
    void suggest_sessionNotFound_throws404() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suggest(SESSION_ID, COACH_USER_ID, 10))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suggest_sessionOwnedByOtherCoach_throws404() {
        UUID otherCoachId = UUID.randomUUID();
        Session session = buildSession(otherCoachId, List.of("technical"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.suggest(SESSION_ID, COACH_USER_ID, 10))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suggest_includesPrivateDrills() {
        Session session = buildSession(COACH_ID, List.of("cognitive"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        UUID privateDrillId = UUID.randomUUID();
        Drill platformDrill = buildDrillWithMetadata(UUID.randomUUID(), 1, 1, 3, 1, false);
        Drill privateDrill  = buildDrillWithMetadata(privateDrillId,    1, 1, 5, 1, false);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(platformDrill));
        when(drillRepository.findByOwnerCoachIdAndStatus(COACH_ID, "ACTIVE")).thenReturn(List.of(privateDrill));
        when(sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(PLAYER_ID)).thenReturn(List.of());
        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        List<DrillResponse> result = service.suggest(SESSION_ID, COACH_USER_ID, 10);

        assertThat(result.stream().map(DrillResponse::id)).contains(privateDrillId);
    }

    @Test
    void suggest_drillsAlreadyInSession_areExcluded() {
        UUID usedDrillId = UUID.randomUUID();
        Session session = buildSession(COACH_ID, List.of("physical"));
        SessionBlockData block = new SessionBlockData("WARM_UP", "Warm-Up", 10,
            List.of(new SessionDrillRef(usedDrillId, 0)));
        session.setBlocks(List.of(block));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        Drill usedDrill  = buildDrillWithMetadata(usedDrillId,         5, 1, 1, 1, false);
        Drill freshDrill = buildDrillWithMetadata(UUID.randomUUID(),    3, 1, 1, 1, false);

        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(usedDrill, freshDrill));
        when(drillRepository.findByOwnerCoachIdAndStatus(COACH_ID, "ACTIVE")).thenReturn(List.of());
        when(sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(PLAYER_ID)).thenReturn(List.of());
        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        List<DrillResponse> result = service.suggest(SESSION_ID, COACH_USER_ID, 10);

        assertThat(result.stream().map(DrillResponse::id)).doesNotContain(usedDrillId);
    }

    @Test
    void suggest_withNeglectedSkill_tagsMatchingDrills() {
        when(neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID))
            .thenReturn(List.of(makeNeglectedFlag("PAC")));

        Session session = buildSession(COACH_ID, List.of("technical"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrillWithSkill(drillId, "PAC");
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));
        when(drillRepository.findByOwnerCoachIdAndStatus(COACH_ID, "ACTIVE")).thenReturn(List.of());
        when(sessionRepository.findTop5ByPlayerIdOrderByCreatedAtDesc(PLAYER_ID)).thenReturn(List.of());
        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        service.suggest(SESSION_ID, COACH_USER_ID, 10);

        ArgumentCaptor<Boolean> neglectedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(drillLibraryService).toResponse(any(), eq(false), any(), any(), any(), any(), neglectedCaptor.capture());
        assertThat(neglectedCaptor.getValue()).isTrue();
    }

    @Test
    void suggest_noFocus_withNeglectedSkill_fallbackTagsMatchingDrills() {
        when(neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID))
            .thenReturn(List.of(makeNeglectedFlag("PAC")));

        Session session = buildSession(COACH_ID, List.of());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        UUID drillId = UUID.randomUUID();
        Drill drill = buildDrillWithSkill(drillId, "PAC");
        when(drillRepository.findByLibraryTypeAndStatus("PLATFORM", "ACTIVE")).thenReturn(List.of(drill));
        when(drillLibraryService.toResponse(any(), eq(false), any(), any(), any(), any(), anyBoolean()))
            .thenAnswer(inv -> mockDrillResponse(((Drill) inv.getArgument(0)).getId()));

        service.suggest(SESSION_ID, COACH_USER_ID, 10);

        ArgumentCaptor<Boolean> neglectedCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(drillLibraryService).toResponse(any(), eq(false), any(), any(), any(), any(), neglectedCaptor.capture());
        assertThat(neglectedCaptor.getValue()).isTrue();
    }

    // --- helpers ---

    private Session buildSession(UUID coachId, List<String> focus) {
        Session s = new Session();
        s.setCoachId(coachId);
        s.setPlayerId(PLAYER_ID);
        s.setDevelopmentFocus(focus);
        s.setBlocks(List.of());
        return s;
    }

    private Drill buildDrill(UUID id, String library, UUID ownerCoachId, Instant createdAt) {
        return Instancio.of(Drill.class)
            .set(field(Drill.class, "id"), id)
            .set(field(Drill.class, "libraryType"), library)
            .set(field(Drill.class, "ownerCoachId"), ownerCoachId)
            .set(field(Drill.class, "status"), "ACTIVE")
            .set(field(Drill.class, "metadata"), buildMeta(1, 1, 1, 1, false))
            .set(field(Drill.class, "createdAt"), createdAt)
            .create();
    }

    private Drill buildDrillWithMetadata(UUID id, int intensity, int pressure, int cognitive, int matchRealism, boolean weakFoot) {
        return Instancio.of(Drill.class)
            .set(field(Drill.class, "id"), id)
            .set(field(Drill.class, "libraryType"), "PLATFORM")
            .set(field(Drill.class, "status"), "ACTIVE")
            .set(field(Drill.class, "metadata"), buildMeta(intensity, pressure, cognitive, matchRealism, weakFoot))
            .create();
    }

    private DrillMetadata buildMeta(int intensity, int pressure, int cognitive, int matchRealism, boolean weakFoot) {
        return new DrillMetadata(
            List.of("dribbling"), List.of(), Map.of("dribbling", 100),
            10, intensity, pressure, cognitive, matchRealism,
            weakFoot, "U12", List.of("ball"), "2", List.of("Keep simple"), null
        );
    }

    private Drill buildDrillWithSkill(UUID id, String skillCode) {
        DrillMetadata meta = new DrillMetadata(
            List.of(skillCode), List.of(), Map.of(skillCode, 1),
            10, 3, 3, 3, 3, false, "U12", List.of("ball"), "2", List.of(), null
        );
        return Instancio.of(Drill.class)
            .set(field(Drill.class, "id"), id)
            .set(field(Drill.class, "libraryType"), "PLATFORM")
            .set(field(Drill.class, "status"), "ACTIVE")
            .set(field(Drill.class, "metadata"), meta)
            .set(field(Drill.class, "createdAt"), Instant.ofEpochSecond(1000))
            .create();
    }

    private NeglectedSkillFlag makeNeglectedFlag(String skillCode) {
        NeglectedSkillFlag f = new NeglectedSkillFlag();
        f.setPlayerId(PLAYER_ID);
        f.setSkillCode(skillCode);
        f.setDetectedAt(Instant.now().minusSeconds(3600));
        return f;
    }

    private DrillResponse mockDrillResponse(UUID id) {
        return new DrillResponse(id, "Drill " + id, null, "PLATFORM", null, "ACTIVE",
            null, false, null, null, Instant.now(), List.of(), null, null, false);
    }

}

