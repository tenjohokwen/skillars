package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluTargetRepository;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class NeglectedSkillProcessor {

    private final SluTargetRepository sluTargetRepository;
    private final SluWeeklySnapshotRepository snapshotRepository;
    private final NeglectedSkillFlagRepository flagRepository;

    @Transactional
    public void processPlayer(Long playerId, BigDecimal threshold, short year, short week) {
        Map<String, BigDecimal> maxTargets = sluTargetRepository.findMaxTargetPerSkill(playerId)
            .stream()
            .collect(Collectors.toMap(r -> (String) r[0], r -> (BigDecimal) r[1]));

        Map<String, BigDecimal> actualSlu = snapshotRepository
            .findByPlayerIdAndWeek(playerId, year, week)
            .stream()
            .collect(Collectors.toMap(
                s -> s.getId().getSkillCode(),
                PlayerSluWeeklySnapshot::getTotalSlu
            ));

        // Bulk-load all open flags once to avoid N+1 per skill.
        // Merge function keeps the first flag if duplicates exist (possible before V49 unique index landed).
        Map<String, NeglectedSkillFlag> openFlags = flagRepository
            .findByPlayerIdAndResolvedAtIsNull(playerId)
            .stream()
            .collect(Collectors.toMap(NeglectedSkillFlag::getSkillCode, f -> f, (a, b) -> a));

        BigDecimal oneMinus = BigDecimal.ONE.subtract(threshold);
        for (Map.Entry<String, BigDecimal> entry : maxTargets.entrySet()) {
            String skill = entry.getKey();
            BigDecimal target = entry.getValue();
            BigDecimal actual = actualSlu.getOrDefault(skill, BigDecimal.ZERO);
            BigDecimal lowerBound = target.multiply(oneMinus);
            boolean neglected = actual.compareTo(lowerBound) < 0;

            NeglectedSkillFlag openFlag = openFlags.get(skill);
            if (neglected && openFlag == null) {
                NeglectedSkillFlag flag = new NeglectedSkillFlag();
                flag.setPlayerId(playerId);
                flag.setSkillCode(skill);
                flag.setDetectedAt(Instant.now());
                flagRepository.save(flag);
                log.info("Neglected skill flagged: player={} skill={} actual={} target={}",
                    playerId, skill, actual, target);
            } else if (!neglected && openFlag != null) {
                openFlag.setResolvedAt(Instant.now());
                flagRepository.save(openFlag);
                log.info("Neglected skill resolved: player={} skill={}", playerId, skill);
            }
        }
    }
}
