package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.platform.development.contract.AssessmentType;
import com.softropic.skillars.platform.development.contract.RadarEntrySubmittedEvent;
import com.softropic.skillars.platform.development.repo.PlayerRadarBaselineRepository;
import com.softropic.skillars.platform.development.repo.PlayerRadarCompositeRepository;
import com.softropic.skillars.platform.development.repo.RadarAssessmentRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final PlayerRadarBaselineRepository baselineRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final PessimisticLockRetryer lockRetryer;
    private final EntityManager entityManager;
    private final RadarCompositeDlqService dlqService;

    // recalculateComposite is @Transactional; calling it as `this.recalculateComposite(...)` from
    // onRadarEntrySubmitted below would bypass the Spring proxy and silently drop that annotation
    // (same pitfall documented on BookingService.acceptAndInitiatePayment). Mirrors
    // TimelineEventListener's identical @Lazy @Autowired self field for the same reason.
    @Autowired
    @Lazy
    private RadarCompositeCalculationService self;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRadarEntrySubmitted(RadarEntrySubmittedEvent event) {
        Long playerId    = event.playerId();
        Long parentId    = event.parentId();
        Set<String> skills = event.skillCodes();

        try {
            self.recalculateComposite(playerId, parentId, skills);
        } catch (Exception e) {
            log.error("Composite recalculation failed for player={} skills={} — composite is now stale, queued to DLQ",
                playerId, skills, e);
            dlqService.emitFailedCompositeCalculation(playerId, parentId, skills, e);
        }
    }

    /**
     * Called both from {@link #onRadarEntrySubmitted} (via {@link #self}) and from
     * {@link RadarCompositeDlqProcessor} (scheduled retry poller, a separate bean) — always through
     * this bean's Spring proxy, never a direct same-instance call.
     */
    @Transactional
    public void recalculateComposite(Long playerId, Long parentId, Set<String> skills) {
        // Deferred-77 AC10 Phase 1: two concurrent submissions for the same player both used to
        // read aggregates before either upserted, so the later commit silently clobbered the
        // earlier one's result (last-writer-wins, not a merge). Locking the player row for the
        // duration of read+upsert serializes concurrent recalculations for the same player.
        var playerProfile = lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
            .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")));
        entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE);

        List<Object[]> aggregates = radarRepository.findAggregatesByPlayerAndSkills(playerId, parentId, skills);
        List<Object[]> distinctCoachCounts =
            radarRepository.findDistinctCoachCountsByPlayerAndSkills(playerId, parentId, skills);

        Map<String, Integer> distinctCoachCountBySkill = new HashMap<>();
        for (Object[] row : distinctCoachCounts) {
            distinctCoachCountBySkill.put((String) row[0], ((Number) row[1]).intValue());
        }

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
                totalCount += toSafeIntCount(types.get(AssessmentType.OBJECTIVE)[1]);
            }
            if (types.containsKey(AssessmentType.MATCH_OBSERVATION)) {
                composite  += types.get(AssessmentType.MATCH_OBSERVATION)[0] * WEIGHT_MATCH_OBS.doubleValue();
                totalCount += toSafeIntCount(types.get(AssessmentType.MATCH_OBSERVATION)[1]);
            }
            if (types.containsKey(AssessmentType.COACH_EVALUATION)) {
                composite  += types.get(AssessmentType.COACH_EVALUATION)[0] * WEIGHT_COACH_EVAL.doubleValue();
                totalCount += toSafeIntCount(types.get(AssessmentType.COACH_EVALUATION)[1]);
            }

            BigDecimal compositeScore = BigDecimal.valueOf(composite)
                .setScale(2, java.math.RoundingMode.HALF_UP);
            int distinctCoachCount = distinctCoachCountBySkill.getOrDefault(skill, 0);
            compositeRepository.upsertComposite(playerId, skill, compositeScore, totalCount, distinctCoachCount);
            baselineRepository.insertBaselineIfAbsent(playerId, skill, compositeScore);
            log.debug("Composite updated: player={} skill={} score={} entries={} distinctCoaches={}",
                playerId, skill, compositeScore, totalCount, distinctCoachCount);
        }
    }

    /**
     * Recovers the exact integral count a native-query {@code long} was cast to {@code double} for
     * aggregation, narrowing it back to {@code int} with an overflow guard instead of silently wrapping.
     */
    private static int toSafeIntCount(double count) {
        return Math.toIntExact(Math.round(count));
    }
}
