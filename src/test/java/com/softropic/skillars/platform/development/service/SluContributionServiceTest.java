package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.CoachContributionDto;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluContributionServiceTest {

    @Mock private SluRepository sluRepository;
    @Mock private CoachProfileService coachProfileService;

    private SluContributionService service;

    private static final Long PLAYER_ID = 500L;
    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SluContributionService(sluRepository, coachProfileService);
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
}
