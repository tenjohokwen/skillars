package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.AssessmentType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RadarCompositeCalculatorTest {

    @Mock
    private RadarAssessmentRepository radarRepository;

    @Mock
    private PlayerRadarCompositeRepository compositeRepository;

    @Mock
    private PlayerRadarBaselineRepository baselineRepository;

    private RadarCompositeCalculationService service;

    private static final Long PLAYER_ID = 9580000001L;
    private static final Long PARENT_ID = 9580000010L;

    @BeforeEach
    void setUp() {
        service = new RadarCompositeCalculationService(radarRepository, compositeRepository, baselineRepository);
    }

    @Test
    void onRadarEntrySubmitted_singleCoachObjective_computesWeightedComposite() {
        // OBJECTIVE avg=80 → composite = 80 × 0.50 = 40.00
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), scoreCaptor.capture(), eq(1));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("40.00");
    }

    @Test
    void onRadarEntrySubmitted_allThreeTypes_computesCorrectComposite() {
        // OBJECTIVE avg=80, MATCH_OBS avg=70, COACH_EVAL avg=60 → 80×0.50 + 70×0.30 + 60×0.20 = 73.00
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE",         80.0, 1L});
        rows.add(new Object[]{"PAC", "MATCH_OBSERVATION", 70.0, 1L});
        rows.add(new Object[]{"PAC", "COACH_EVALUATION",  60.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), scoreCaptor.capture(), eq(3));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("73.00");
    }

    @Test
    void onRadarEntrySubmitted_multipleCoaches_aggregatesAcrossAllCoaches() {
        // coach A OBJECTIVE=80, coach B OBJECTIVE=60 → avgObjective=70 → composite=70×0.50=35.00; count=2
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 70.0, 2L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC")))
            .thenReturn(rows);

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("PAC"), scoreCaptor.capture(), eq(2));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("35.00");
    }

    @Test
    void onRadarEntrySubmitted_onlyMatchObservation_computesPartialComposite() {
        // MATCH_OBS avg=50 → composite = 50×0.30 = 15.00 (partial)
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"SHO", "MATCH_OBSERVATION", 50.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("SHO")))
            .thenReturn(rows);

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("SHO")));

        ArgumentCaptor<BigDecimal> scoreCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(compositeRepository).upsertComposite(eq(PLAYER_ID), eq("SHO"), scoreCaptor.capture(), eq(1));
        assertThat(scoreCaptor.getValue()).isEqualByComparingTo("15.00");
    }

    @Test
    void onRadarEntrySubmitted_multipleSkills_upsertCalledPerSkill() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"PAC", "OBJECTIVE", 80.0, 1L});
        rows.add(new Object[]{"SHO", "OBJECTIVE", 60.0, 1L});
        when(radarRepository.findAggregatesByPlayerAndSkills(PLAYER_ID, PARENT_ID, Set.of("PAC", "SHO")))
            .thenReturn(rows);

        service.onRadarEntrySubmitted(new RadarEntrySubmittedEvent(PLAYER_ID, PARENT_ID, Set.of("PAC", "SHO")));

        verify(compositeRepository, times(2)).upsertComposite(eq(PLAYER_ID), any(), any(), anyInt());
    }
}
