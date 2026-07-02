package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.CorrelationInsight;
import com.softropic.skillars.platform.development.contract.CorrelationInsightType;
import com.softropic.skillars.platform.development.contract.CorrelationResponse;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaseline;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarComposite;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.SluRepository;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.service.CoachProfileService;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DevelopmentCorrelationService {

    private static final BigDecimal IMPROVEMENT_THRESHOLD = BigDecimal.valueOf(3.0);

    private static final Map<CorrelationInsightType, String> I18N_KEYS = Map.of(
        CorrelationInsightType.HIGH_SLU_IMPROVEMENT,    "development.radar.correlation.insight.highSluImprovement",
        CorrelationInsightType.HIGH_SLU_NO_IMPROVEMENT, "development.radar.correlation.insight.highSluNoImprovement",
        CorrelationInsightType.LOW_SLU_IMPROVEMENT,     "development.radar.correlation.insight.lowSluImprovement",
        CorrelationInsightType.LOW_SLU_STABLE,          "development.radar.correlation.insight.lowSluStable"
    );

    private final SluRepository sluRepository;
    private final PlayerRadarCompositeRepository compositeRepository;
    private final PlayerRadarBaselineRepository baselineRepository;
    private final CoachProfileService coachProfileService;
    private final ConfigService configService;
    private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;

    public CorrelationResponse getInsights(Long playerId, UUID coachId) {
        coachPlayerAuthorizationService.requireCoachPlayerRelationship(coachId, playerId);
        CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
        if (tier != CoachSubscriptionTier.ACADEMY) {
            throw new FeatureGatedException("development.correlation", "ACADEMY");
        }

        long minSessionCount = configService.getLong("development.correlation.minSessionCount");
        Long distinctSessions = sluRepository.countDistinctSessions(playerId);
        if (distinctSessions == null || distinctSessions < minSessionCount) {
            return new CorrelationResponse(true, minSessionCount, List.of(), 0);
        }

        // Build SLU map: skill_code → cumulative SLU
        List<Object[]> sluRows = sluRepository.sumSluBySkill(playerId);
        Map<String, BigDecimal> sluBySkill = new HashMap<>();
        for (Object[] row : sluRows) {
            String skillCode = (String) row[0];
            BigDecimal totalSlu = row[1] instanceof BigDecimal bd
                ? bd
                : BigDecimal.valueOf(((Number) row[1]).doubleValue());
            sluBySkill.put(skillCode, totalSlu);
        }

        if (sluBySkill.isEmpty()) {
            return new CorrelationResponse(true, minSessionCount, List.of(), 0);
        }

        // Compute cross-skill mean SLU (player's own average)
        BigDecimal totalSluSum = sluBySkill.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal meanSlu = totalSluSum.divide(
            BigDecimal.valueOf(sluBySkill.size()), 4, RoundingMode.HALF_UP);

        // Load composites and baselines
        Map<String, PlayerRadarComposite> compositeMap = new HashMap<>();
        for (PlayerRadarComposite c : compositeRepository.findByIdPlayerId(playerId)) {
            compositeMap.put(c.getId().getSkillCode(), c);
        }
        Map<String, PlayerRadarBaseline> baselineMap = new HashMap<>();
        for (PlayerRadarBaseline b : baselineRepository.findByIdPlayerId(playerId)) {
            baselineMap.put(b.getId().getSkillCode(), b);
        }

        List<CorrelationInsight> insights = new ArrayList<>();
        int excludedSkillCount = 0;

        for (Map.Entry<String, BigDecimal> entry : sluBySkill.entrySet()) {
            String skillCode = entry.getKey();
            BigDecimal skillSlu = entry.getValue();

            PlayerRadarComposite composite = compositeMap.get(skillCode);
            if (composite == null) {
                // SLU data exists but no radar assessment — cannot classify
                excludedSkillCount++;
                continue;
            }

            BigDecimal currentScore = composite.getCompositeScore();
            PlayerRadarBaseline baseline = baselineMap.get(skillCode);
            // If no baseline, improvement cannot be measured — treat as zero
            BigDecimal compositeImprovement = baseline != null
                ? currentScore.subtract(baseline.getBaselineScore())
                : BigDecimal.ZERO;

            boolean sluHigh = skillSlu.compareTo(meanSlu) > 0;
            boolean compositeImproved = compositeImprovement.compareTo(IMPROVEMENT_THRESHOLD) > 0;

            CorrelationInsightType insightType;
            if (sluHigh && compositeImproved) {
                insightType = CorrelationInsightType.HIGH_SLU_IMPROVEMENT;
            } else if (sluHigh) {
                insightType = CorrelationInsightType.HIGH_SLU_NO_IMPROVEMENT;
            } else if (compositeImproved) {
                insightType = CorrelationInsightType.LOW_SLU_IMPROVEMENT;
            } else {
                insightType = CorrelationInsightType.LOW_SLU_STABLE;
            }

            insights.add(new CorrelationInsight(
                skillCode,
                skillSlu,
                currentScore,
                insightType,
                I18N_KEYS.get(insightType)
            ));
        }

        insights.sort(Comparator.comparing(CorrelationInsight::cumulativeSlu).reversed());

        return new CorrelationResponse(false, minSessionCount, insights, excludedSkillCount);
    }
}
