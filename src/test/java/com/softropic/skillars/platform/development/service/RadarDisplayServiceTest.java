package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.development.contract.RadarDisplayResponse;
import com.softropic.skillars.platform.development.repo.CoachRadarPreferenceRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.service.DevelopmentDataErasureGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-153 AC6: unit coverage for the consolidated
 * {@code requireCoachPlayerRelationshipIfCoach} call — this class's own prior coverage
 * (RadarDisplayResourceIT) is integration-level only, so this closes the unit-test gap for the
 * relationship-check delegation specifically, not full-service coverage.
 */
@ExtendWith(MockitoExtension.class)
class RadarDisplayServiceTest {

    @Mock private PlayerRadarCompositeRepository compositeRepository;
    @Mock private PlayerRadarBaselineRepository baselineRepository;
    @Mock private SkillDefinitionRepository skillDefinitionRepository;
    @Mock private CoachRadarPreferenceRepository preferenceRepository;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    @Mock private DevelopmentDataErasureGuard erasureGuard;

    private RadarDisplayService service;

    private static final Long PLAYER_ID = 500L;

    @BeforeEach
    void setUp() {
        service = new RadarDisplayService(
            compositeRepository, baselineRepository, skillDefinitionRepository,
            preferenceRepository, coachPlayerAuthorizationService, erasureGuard);
        lenient().when(skillDefinitionRepository.findAllByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());
        lenient().when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of());
        lenient().when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of());
    }

    @Test
    void getRadarDisplay_delegatesRelationshipCheckToSharedMethod() {
        RadarDisplayResponse result = service.getRadarDisplay(PLAYER_ID);

        assertThat(result.skills()).isEmpty();
        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationshipIfCoach(PLAYER_ID);
    }

    @Test
    void getRadarDisplay_sharedMethodDenies_throwsAndNeverQueriesComposites() {
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        assertThatThrownBy(() -> service.getRadarDisplay(PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(compositeRepository, never()).findByIdPlayerId(PLAYER_ID);
    }

    // ── skillars-12-10 AC1 (G19): preferences endpoints carry the relationship check ─────────────

    private static final java.util.UUID COACH_ID = java.util.UUID.randomUUID();

    @Test
    void getPreferences_unrelatedCoach_throwsAndNeverReadsPreferences() {
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(COACH_ID, PLAYER_ID);

        assertThatThrownBy(() -> service.getPreferences(COACH_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(preferenceRepository, never()).findByIdCoachIdAndIdPlayerId(COACH_ID, PLAYER_ID);
    }

    @Test
    void savePreferences_unrelatedCoach_throwsBeforeAnyUpsert() {
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(COACH_ID, PLAYER_ID);

        assertThatThrownBy(() -> service.savePreferences(COACH_ID, PLAYER_ID,
            new com.softropic.skillars.platform.development.contract.CoachRadarPreferenceRequest(List.of("PASS"))))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(preferenceRepository, never()).findByIdCoachIdAndIdPlayerId(COACH_ID, PLAYER_ID);
        verify(preferenceRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ── skillars-deferred-171 AC1: an erased player gets no new radar preferences ───────────────────

    @Test
    void savePreferences_erasedPlayer_throwsNotFoundAndWritesNothing() {
        when(erasureGuard.isErasedUnderLock(org.mockito.ArgumentMatchers.eq(PLAYER_ID),
            org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.savePreferences(COACH_ID, PLAYER_ID,
            new com.softropic.skillars.platform.development.contract.CoachRadarPreferenceRequest(List.of("PASS"))))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(e -> ((ResourceNotFoundException) e).getResourceName()).isEqualTo("player_profile");
        verify(preferenceRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void savePreferences_notErasedPlayer_checksTheGuardThenSaves() {
        when(preferenceRepository.findByIdCoachIdAndIdPlayerId(COACH_ID, PLAYER_ID)).thenReturn(java.util.Optional.empty());
        when(preferenceRepository.save(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> inv.getArgument(0));

        service.savePreferences(COACH_ID, PLAYER_ID,
            new com.softropic.skillars.platform.development.contract.CoachRadarPreferenceRequest(List.of("PASS")));

        verify(erasureGuard).isErasedUnderLock(org.mockito.ArgumentMatchers.eq(PLAYER_ID),
            org.mockito.ArgumentMatchers.anyString());
        verify(preferenceRepository).save(org.mockito.ArgumentMatchers.any());
    }
}
