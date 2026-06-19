package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.AssessmentType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class RadarCompositeCalculationService {

    private static final BigDecimal WEIGHT_OBJECTIVE  = new BigDecimal("0.50");
    private static final BigDecimal WEIGHT_MATCH_OBS  = new BigDecimal("0.30");
    private static final BigDecimal WEIGHT_COACH_EVAL = new BigDecimal("0.20");

    private final RadarAssessmentRepository radarRepository;
    private final PlayerRadarCompositeRepository compositeRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRadarEntrySubmitted(RadarEntrySubmittedEvent event) {
        Long playerId    = event.playerId();
        Long parentId    = event.parentId();
        Set<String> skills = event.skillCodes();

        try {
            List<Object[]> aggregates = radarRepository.findAggregatesByPlayerAndSkills(playerId, parentId, skills);

            Map<String, Map<AssessmentType, double[]>> bySkill = new HashMap<>();
            for (Object[] row : aggregates) {
                String skill = (String) row[0];
                AssessmentType type = AssessmentType.valueOf((String) row[1]);
                double avg   = ((Number) row[2]).doubleValue();
                long count   = ((Number) row[3]).longValue();
                bySkill.computeIfAbsent(skill, k -> new HashMap<>())
                    .put(type, new double[]{avg, count});
            }

            for (Map.Entry<String, Map<AssessmentType, double[]>> skillEntry : bySkill.entrySet()) {
                String skill = skillEntry.getKey();
                Map<AssessmentType, double[]> types = skillEntry.getValue();

                double composite = 0.0;
                int totalCount   = 0;

                if (types.containsKey(AssessmentType.OBJECTIVE)) {
                    composite  += types.get(AssessmentType.OBJECTIVE)[0] * WEIGHT_OBJECTIVE.doubleValue();
                    totalCount += (int) types.get(AssessmentType.OBJECTIVE)[1];
                }
                if (types.containsKey(AssessmentType.MATCH_OBSERVATION)) {
                    composite  += types.get(AssessmentType.MATCH_OBSERVATION)[0] * WEIGHT_MATCH_OBS.doubleValue();
                    totalCount += (int) types.get(AssessmentType.MATCH_OBSERVATION)[1];
                }
                if (types.containsKey(AssessmentType.COACH_EVALUATION)) {
                    composite  += types.get(AssessmentType.COACH_EVALUATION)[0] * WEIGHT_COACH_EVAL.doubleValue();
                    totalCount += (int) types.get(AssessmentType.COACH_EVALUATION)[1];
                }

                BigDecimal compositeScore = BigDecimal.valueOf(composite)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
                compositeRepository.upsertComposite(playerId, skill, compositeScore, totalCount);
                log.debug("Composite updated: player={} skill={} score={} entries={}",
                    playerId, skill, compositeScore, totalCount);
            }
        } catch (Exception e) {
            log.error("Composite recalculation failed for player={} skills={} — composite is now stale",
                playerId, skills, e);
        }
    }
}
