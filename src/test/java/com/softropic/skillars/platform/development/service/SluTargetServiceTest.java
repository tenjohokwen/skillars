package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.development.contract.NeglectedSkillResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

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

    private SluTargetService service;

    private static final Long PLAYER_ID = 500L;
    private static final UUID COACH_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SluTargetService(sluTargetRepository, flagRepository, coachPlayerAuthorizationService);
        lenient().when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());
        lenient().when(sluTargetRepository.findByIdCoachIdAndIdPlayerId(COACH_ID, PLAYER_ID)).thenReturn(List.of());
    }

    // skillars-deferred-153 AC6: the inline requireCoachPlayerRelationshipIfCoach predicate was
    // consolidated onto CoachPlayerAuthorizationService — this service now unconditionally
    // delegates to that one shared method.
    @Test
    void getNeglectedSkills_delegatesRelationshipCheckToSharedMethod() {
        List<NeglectedSkillResponse> result = service.getNeglectedSkills(PLAYER_ID);

        assertThat(result).isEmpty();
        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationshipIfCoach(PLAYER_ID);
    }

    @Test
    void getNeglectedSkills_sharedMethodDenies_throwsAndNeverQueriesFlags() {
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipIfCoach(PLAYER_ID);

        assertThatThrownBy(() -> service.getNeglectedSkills(PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(flagRepository, never()).findByPlayerIdAndResolvedAtIsNull(any());
    }

    // skillars-deferred-153 AC5: getTargets is a read endpoint — widened to the roster's own
    // membership status width via requireCoachPlayerRelationshipForRead, not the narrow
    // requireCoachPlayerRelationship setTargets (write) still uses.
    @Test
    void getTargets_usesWidenedReadRelationshipCheck() {
        service.getTargets(COACH_ID, PLAYER_ID);

        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(COACH_ID, PLAYER_ID);
        verify(coachPlayerAuthorizationService, never()).requireCoachPlayerRelationship(any(UUID.class), any());
    }

    @Test
    void getTargets_sharedMethodDenies_throwsAndNeverQueriesTargets() {
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationshipForRead(COACH_ID, PLAYER_ID);

        assertThatThrownBy(() -> service.getTargets(COACH_ID, PLAYER_ID))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(sluTargetRepository, never()).findByIdCoachIdAndIdPlayerId(any(), any());
    }
}
