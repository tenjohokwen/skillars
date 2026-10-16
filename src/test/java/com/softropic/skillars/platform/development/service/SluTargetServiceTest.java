package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.development.contract.NeglectedSkillResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluTargetServiceTest {

    @Mock private SluTargetRepository sluTargetRepository;
    @Mock private NeglectedSkillFlagRepository flagRepository;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PlayerProfileRepository playerProfileRepository;

    private SluTargetService service;

    private static final Long PLAYER_ID = 500L;
    private static final Long COACH_USER_ID = 777L;

    @BeforeEach
    void setUp() {
        service = new SluTargetService(
            sluTargetRepository, flagRepository, coachPlayerAuthorizationService,
            securityUtil, playerProfileRepository);
        lenient().when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());
    }

    @Test
    void getNeglectedSkills_asParentOrPlayer_relationshipCheckNeverInvoked() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(false);

        List<NeglectedSkillResponse> result = service.getNeglectedSkills(PLAYER_ID);

        assertThat(result).isEmpty();
        verify(coachPlayerAuthorizationService, never()).requireCoachPlayerRelationship(any(Long.class), any());
    }

    @Test
    void getNeglectedSkills_asCoachWithRelationship_succeeds() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);

        List<NeglectedSkillResponse> result = service.getNeglectedSkills(PLAYER_ID);

        assertThat(result).isEmpty();
        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationship(COACH_USER_ID, PLAYER_ID);
    }

    @Test
    void getNeglectedSkills_asCoachWithNoRelationship_throws() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationship(COACH_USER_ID, PLAYER_ID);

        assertThatThrownBy(() -> service.getNeglectedSkills(PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(flagRepository, never()).findByPlayerIdAndResolvedAtIsNull(any());
    }

    @Test
    void getNeglectedSkills_asCoachWhoIsAlsoPlayersParent_relationshipCheckSkipped() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(true);

        List<NeglectedSkillResponse> result = service.getNeglectedSkills(PLAYER_ID);

        assertThat(result).isEmpty();
        verify(coachPlayerAuthorizationService, never()).requireCoachPlayerRelationship(any(Long.class), any());
    }
}
