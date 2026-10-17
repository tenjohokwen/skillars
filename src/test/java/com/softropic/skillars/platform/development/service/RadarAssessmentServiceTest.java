package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.development.contract.RadarAssessmentListResponse;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.development.repo.SkillDefinitionRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.marketplace.service.PlayerProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-153 AC5: getMyEntries is a read endpoint, widened to match the roster's own
 * membership status width — no unit test file existed for this service before this story
 * (RadarAssessmentResourceIT covers it at the integration level); this closes the unit-test gap
 * for the specific widening call-site swap, not full-service coverage.
 */
@ExtendWith(MockitoExtension.class)
class RadarAssessmentServiceTest {

    @Mock private RadarAssessmentRepository radarRepository;
    @Mock private CoachProfileService coachProfileService;
    @Mock private PlayerProfileService playerProfileService;
    @Mock private SkillDefinitionRepository skillDefinitionRepository;
    @Mock private ApplicationEventPublisher publisher;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    private RadarAssessmentService service;

    private static final Long COACH_USER_ID = 100L;
    private static final Long PLAYER_ID = 200L;
    private static final Long PARENT_ID = 300L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RadarAssessmentService(
            radarRepository, coachProfileService, playerProfileService,
            skillDefinitionRepository, publisher, coachPlayerAuthorizationService);
        org.mockito.Mockito.lenient().when(coachProfileService.getCoachIdByUserId(COACH_USER_ID)).thenReturn(COACH_ID);
        org.mockito.Mockito.lenient().when(coachProfileService.getCoachSubscriptionTier(COACH_ID))
            .thenReturn(CoachSubscriptionTier.INSTRUCTOR);
        org.mockito.Mockito.lenient().when(playerProfileService.getParentIdByPlayerId(PLAYER_ID)).thenReturn(PARENT_ID);
        org.mockito.Mockito.lenient().when(radarRepository.findByPlayerIdAndCoachIdOrderByAssessmentDateDesc(
            PLAYER_ID, PARENT_ID, COACH_ID)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(radarRepository.countDistinctOtherCoachesBySkill(
            PLAYER_ID, PARENT_ID, COACH_ID)).thenReturn(List.of());
    }

    @Test
    void getMyEntries_usesWidenedReadRelationshipCheck() {
        RadarAssessmentListResponse result = service.getMyEntries(COACH_USER_ID, PLAYER_ID);

        assertThat(result.entries()).isEmpty();
        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID);
        verify(coachPlayerAuthorizationService, never()).requireCoachPlayerRelationship(any(Long.class), any());
    }

    @Test
    void getMyEntries_sharedMethodDenies_throwsAndNeverQueriesEntries() {
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(COACH_USER_ID, PLAYER_ID);

        assertThatThrownBy(() -> service.getMyEntries(COACH_USER_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(radarRepository, never()).findByPlayerIdAndCoachIdOrderByAssessmentDateDesc(any(), any(), any());
    }
}
