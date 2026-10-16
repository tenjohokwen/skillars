package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.development.contract.CoachContributionDto;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluContributionServiceTest {

    @Mock private SluRepository sluRepository;
    @Mock private CoachProfileService coachProfileService;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    @Mock private SecurityUtil securityUtil;
    @Mock private PlayerProfileRepository playerProfileRepository;

    private SluContributionService service;

    private static final Long PLAYER_ID = 500L;
    private static final Long COACH_USER_ID = 777L;
    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SluContributionService(
            sluRepository, coachProfileService, coachPlayerAuthorizationService,
            securityUtil, playerProfileRepository);
        lenient().when(sluRepository.findCoachContributionsByPlayerId(PLAYER_ID, SINCE)).thenReturn(List.of());
    }

    @Test
    void getCoachContributions_computesPercentagesAndResolvesDisplayNames() {
        UUID coachA = UUID.randomUUID();
        UUID coachB = UUID.randomUUID();
        List<Object[]> rows = List.of(
            new Object[]{coachA, "PAC", BigDecimal.valueOf(30)},
            new Object[]{coachB, "PAC", BigDecimal.valueOf(70)}
        );
        when(sluRepository.findCoachContributionsByPlayerId(PLAYER_ID, SINCE)).thenReturn(rows);
        when(coachProfileService.getDisplayNamesByIds(anySet()))
            .thenReturn(Map.of(coachA, "Coach A", coachB, "Coach B"));

        List<CoachContributionDto> result = service.getCoachContributions(PLAYER_ID, SINCE);

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(dto -> {
            assertThat(dto.coachDisplayName()).isEqualTo("Coach A");
            assertThat(dto.percentageContribution()).isEqualByComparingTo("30.0");
        });
        assertThat(result).anySatisfy(dto -> {
            assertThat(dto.coachDisplayName()).isEqualTo("Coach B");
            assertThat(dto.percentageContribution()).isEqualByComparingTo("70.0");
        });
    }

    @Test
    void getCoachContributions_noRows_returnsEmptyList() {
        when(sluRepository.findCoachContributionsByPlayerId(PLAYER_ID, SINCE)).thenReturn(List.of());

        assertThat(service.getCoachContributions(PLAYER_ID, SINCE)).isEmpty();
    }

    @Test
    void getCoachContributions_nonUuidCoachId_throwsBadSqlGrammarException() {
        List<Object[]> rows = List.<Object[]>of(
            new Object[]{"not-a-uuid", "PAC", BigDecimal.valueOf(30)}
        );
        when(sluRepository.findCoachContributionsByPlayerId(PLAYER_ID, SINCE)).thenReturn(rows);

        assertThatThrownBy(() -> service.getCoachContributions(PLAYER_ID, SINCE))
            .isInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void getCoachContributions_displayNameFetchFails_fallsBackToUnknown() {
        UUID coachA = UUID.randomUUID();
        List<Object[]> rows = List.<Object[]>of(
            new Object[]{coachA, "PAC", BigDecimal.valueOf(30)}
        );
        when(sluRepository.findCoachContributionsByPlayerId(PLAYER_ID, SINCE)).thenReturn(rows);
        when(coachProfileService.getDisplayNamesByIds(anySet()))
            .thenThrow(new RuntimeException("db down"));

        List<CoachContributionDto> result = service.getCoachContributions(PLAYER_ID, SINCE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).coachDisplayName()).isEqualTo("Unknown");
    }

    @Test
    void getCoachContributions_asParentOrPlayer_relationshipCheckNeverInvoked() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(false);

        service.getCoachContributions(PLAYER_ID, SINCE);

        verify(coachPlayerAuthorizationService, never()).requireCoachPlayerRelationship(any(Long.class), any());
    }

    @Test
    void getCoachContributions_asCoachWithRelationship_succeeds() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);

        assertThat(service.getCoachContributions(PLAYER_ID, SINCE)).isEmpty();

        verify(coachPlayerAuthorizationService).requireCoachPlayerRelationship(COACH_USER_ID, PLAYER_ID);
    }

    @Test
    void getCoachContributions_asCoachWithNoRelationship_throws() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(false);
        when(securityUtil.getCurrentCoachUserId()).thenReturn(COACH_USER_ID);
        doThrow(new OperationNotAllowedException("no relationship", SecurityError.MISSING_RIGHTS))
            .when(coachPlayerAuthorizationService).requireCoachPlayerRelationship(COACH_USER_ID, PLAYER_ID);

        assertThatThrownBy(() -> service.getCoachContributions(PLAYER_ID, SINCE))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(sluRepository, never()).findCoachContributionsByPlayerId(any(), any());
    }

    @Test
    void getCoachContributions_asCoachWhoIsAlsoPlayersParent_relationshipCheckSkipped() {
        when(securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)).thenReturn(true);
        when(securityUtil.requireCurrentUserId()).thenReturn(COACH_USER_ID);
        when(playerProfileRepository.existsByIdAndParentId(PLAYER_ID, COACH_USER_ID)).thenReturn(true);

        assertThat(service.getCoachContributions(PLAYER_ID, SINCE)).isEmpty();

        verify(coachPlayerAuthorizationService, never()).requireCoachPlayerRelationship(any(Long.class), any());
    }
}
