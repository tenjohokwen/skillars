package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.CorrelationInsightType;
import com.softropic.skillars.platform.development.contract.CorrelationResponse;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaseline;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineId;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarComposite;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeId;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevelopmentCorrelationServiceTest {

    @Mock private SluRepository sluRepository;
    @Mock private PlayerRadarCompositeRepository compositeRepository;
    @Mock private PlayerRadarBaselineRepository baselineRepository;
    @Mock private CoachProfileService coachProfileService;
    @Mock private ConfigService configService;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    private DevelopmentCorrelationService service;

    private static final UUID COACH_ID  = UUID.randomUUID();
    private static final Long PLAYER_ID = 1001L;

    @BeforeEach
    void setUp() {
        service = new DevelopmentCorrelationService(
            sluRepository, compositeRepository, baselineRepository,
            coachProfileService, configService, coachPlayerAuthorizationService
        );
    }

    @Test
    void getInsights_nonAcademyCoach_throwsFeatureGatedException() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID))
            .thenReturn(CoachSubscriptionTier.INSTRUCTOR);

        assertThatThrownBy(() -> service.getInsights(PLAYER_ID, COACH_ID))
            .isInstanceOf(FeatureGatedException.class);
    }

    @Test
    void getInsights_insufficientSessions_returnsInsufficiencyResponse() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID))
            .thenReturn(CoachSubscriptionTier.ACADEMY);
        when(configService.getLong("development.correlation.minSessionCount")).thenReturn(5L);
        when(sluRepository.countDistinctSessions(PLAYER_ID)).thenReturn(3L);

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        assertThat(response.insufficientData()).isTrue();
        assertThat(response.insights()).isEmpty();
        assertThat(response.minimumSessionCount()).isEqualTo(5L);
    }

    @Test
    void getInsights_highSluImprovedComposite_returnsHighSluImprovement() {
        stubAcademyWithSessions();
        // PAC SLU=150, WEF SLU=50 → mean=100; PAC is high
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("150.00")},
            new Object[]{"WEF", new BigDecimal("50.00")}
        ));
        // PAC composite=60, baseline=50 → improvement=10 > 3.0
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00")),
            makeComposite("WEF", new BigDecimal("70.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeBaseline("PAC", new BigDecimal("50.00")),
            makeBaseline("WEF", new BigDecimal("70.00"))
        ));

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        assertThat(response.insufficientData()).isFalse();
        var pacInsight = response.insights().stream()
            .filter(i -> i.skillCode().equals("PAC")).findFirst().orElseThrow();
        assertThat(pacInsight.insightType()).isEqualTo(CorrelationInsightType.HIGH_SLU_IMPROVEMENT);
    }

    @Test
    void getInsights_highSluNoImprovement_returnsHighSluNoImprovement() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"SHO", new BigDecimal("150.00")},
            new Object[]{"WEF", new BigDecimal("50.00")}
        ));
        // SHO baseline=70, current=70 → no improvement
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("SHO", new BigDecimal("70.00")),
            makeComposite("WEF", new BigDecimal("65.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeBaseline("SHO", new BigDecimal("70.00")),
            makeBaseline("WEF", new BigDecimal("65.00"))
        ));

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        var shoInsight = response.insights().stream()
            .filter(i -> i.skillCode().equals("SHO")).findFirst().orElseThrow();
        assertThat(shoInsight.insightType()).isEqualTo(CorrelationInsightType.HIGH_SLU_NO_IMPROVEMENT);
    }

    @Test
    void getInsights_lowSluStable_returnsLowSluStable() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("150.00")},
            new Object[]{"WEF", new BigDecimal("50.00")}
        ));
        // WEF SLU=50 < mean=100 (low), baseline=65, current=65 (no change)
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00")),
            makeComposite("WEF", new BigDecimal("65.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeBaseline("PAC", new BigDecimal("55.00")),
            makeBaseline("WEF", new BigDecimal("65.00"))
        ));

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        var wefInsight = response.insights().stream()
            .filter(i -> i.skillCode().equals("WEF")).findFirst().orElseThrow();
        assertThat(wefInsight.insightType()).isEqualTo(CorrelationInsightType.LOW_SLU_STABLE);
    }

    @Test
    void getInsights_lowSluImprovement_returnsLowSluImprovement() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("150.00")},
            new Object[]{"DRI", new BigDecimal("30.00")}
        ));
        // DRI SLU=30 < mean=90 (low), baseline=40, current=50 → improvement=10 > 3.0
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00")),
            makeComposite("DRI", new BigDecimal("50.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeBaseline("PAC", new BigDecimal("57.00")),
            makeBaseline("DRI", new BigDecimal("40.00"))
        ));

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        var driInsight = response.insights().stream()
            .filter(i -> i.skillCode().equals("DRI")).findFirst().orElseThrow();
        assertThat(driInsight.insightType()).isEqualTo(CorrelationInsightType.LOW_SLU_IMPROVEMENT);
    }

    @Test
    void getInsights_noBaselineForSkill_treatsImprovementAsZero() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("150.00")},
            new Object[]{"WEF", new BigDecimal("50.00")}
        ));
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00")),
            makeComposite("WEF", new BigDecimal("65.00"))
        ));
        // PAC has no baseline → compositeImprovement = ZERO → cannot detect improvement → HIGH_SLU_NO_IMPROVEMENT
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeBaseline("WEF", new BigDecimal("65.00"))
            // no PAC baseline
        ));

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        var pacInsight = response.insights().stream()
            .filter(i -> i.skillCode().equals("PAC")).findFirst().orElseThrow();
        // PAC has high SLU but no baseline → treated as no improvement
        assertThat(pacInsight.insightType()).isEqualTo(CorrelationInsightType.HIGH_SLU_NO_IMPROVEMENT);
    }

    @Test
    void getInsights_skillWithSluButNoComposite_isExcluded() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("150.00")},
            new Object[]{"GHOST", new BigDecimal("50.00")}
        ));
        // Only PAC has a composite — GHOST does not
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeBaseline("PAC", new BigDecimal("55.00"))
        ));

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        assertThat(response.excludedSkillCount()).isEqualTo(1);
        assertThat(response.insights()).noneMatch(i -> i.skillCode().equals("GHOST"));
    }

    @Test
    void getInsights_sortedBySluDescending() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("20.00")},
            new Object[]{"SHO", new BigDecimal("100.00")},
            new Object[]{"WEF", new BigDecimal("50.00")}
        ));
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00")),
            makeComposite("SHO", new BigDecimal("70.00")),
            makeComposite("WEF", new BigDecimal("65.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of());

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        assertThat(response.insights()).extracting(i -> i.skillCode())
            .containsExactly("SHO", "WEF", "PAC");
    }

    @Test
    void getInsights_excludedSkillCountZero_whenAllSkillsHaveComposites() {
        stubAcademyWithSessions();
        when(sluRepository.sumSluBySkill(PLAYER_ID)).thenReturn(List.of(
            new Object[]{"PAC", new BigDecimal("100.00")},
            new Object[]{"SHO", new BigDecimal("80.00")}
        ));
        when(compositeRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of(
            makeComposite("PAC", new BigDecimal("60.00")),
            makeComposite("SHO", new BigDecimal("70.00"))
        ));
        when(baselineRepository.findByIdPlayerId(PLAYER_ID)).thenReturn(List.of());

        CorrelationResponse response = service.getInsights(PLAYER_ID, COACH_ID);

        assertThat(response.excludedSkillCount()).isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubAcademyWithSessions() {
        when(coachProfileService.getCoachSubscriptionTier(COACH_ID))
            .thenReturn(CoachSubscriptionTier.ACADEMY);
        when(configService.getLong("development.correlation.minSessionCount")).thenReturn(5L);
        when(sluRepository.countDistinctSessions(PLAYER_ID)).thenReturn(6L);
    }

    private PlayerRadarComposite makeComposite(String skillCode, BigDecimal score) {
        PlayerRadarCompositeId id = new PlayerRadarCompositeId();
        id.setPlayerId(PLAYER_ID);
        id.setSkillCode(skillCode);
        PlayerRadarComposite c = new PlayerRadarComposite();
        c.setId(id);
        c.setCompositeScore(score);
        c.setEntryCount(3);
        c.setLastUpdatedAt(Instant.now());
        return c;
    }

    private PlayerRadarBaseline makeBaseline(String skillCode, BigDecimal score) {
        PlayerRadarBaselineId id = new PlayerRadarBaselineId();
        id.setPlayerId(PLAYER_ID);
        id.setSkillCode(skillCode);
        PlayerRadarBaseline b = new PlayerRadarBaseline();
        b.setId(id);
        b.setBaselineScore(score);
        b.setRecordedAt(Instant.now());
        return b;
    }
}
