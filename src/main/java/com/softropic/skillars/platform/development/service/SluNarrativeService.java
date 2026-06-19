package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.NarrativeKeyDto;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SluNarrativeService {

    private final SluWeeklySnapshotRepository snapshotRepository;

    public List<NarrativeKeyDto> generate(Long playerId) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime from = now.minusWeeks(7).with(DayOfWeek.MONDAY);
        short fromYear = (short) from.get(IsoFields.WEEK_BASED_YEAR);
        short fromWeek = (short) from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<PlayerSluWeeklySnapshot> snapshots =
            snapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek);

        // Current 4-week block = weeks N-3 to N; prior block = weeks N-7 to N-4
        short currentYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short currentWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        ZonedDateTime boundaryDt = now.minusWeeks(3).with(DayOfWeek.MONDAY);
        short boundaryYear = (short) boundaryDt.get(IsoFields.WEEK_BASED_YEAR);
        short boundaryWeek = (short) boundaryDt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        Map<String, BigDecimal> currentBlock = new HashMap<>();
        Map<String, BigDecimal> priorBlock = new HashMap<>();

        for (PlayerSluWeeklySnapshot s : snapshots) {
            short sy = s.getId().getIsoYear();
            short sw = s.getId().getIsoWeek();
            String skill = s.getId().getSkillCode();
            boolean inCurrent = isOnOrAfter(sy, sw, boundaryYear, boundaryWeek)
                && isOnOrBefore(sy, sw, currentYear, currentWeek);
            if (inCurrent) {
                currentBlock.merge(skill, s.getTotalSlu(), BigDecimal::add);
            } else {
                priorBlock.merge(skill, s.getTotalSlu(), BigDecimal::add);
            }
        }

        record SkillDelta(String skill, double deltaPercent) {}
        List<SkillDelta> deltas = new ArrayList<>();

        Set<String> allSkills = new HashSet<>(currentBlock.keySet());
        allSkills.addAll(priorBlock.keySet());

        for (String skill : allSkills) {
            BigDecimal current = currentBlock.getOrDefault(skill, BigDecimal.ZERO);
            BigDecimal prior = priorBlock.getOrDefault(skill, BigDecimal.ZERO);
            if (prior.compareTo(BigDecimal.ZERO) == 0) continue;
            double delta = current.subtract(prior)
                .divide(prior.max(new BigDecimal("0.0001")), 6, RoundingMode.HALF_UP)
                .doubleValue() * 100;
            delta = Math.max(-1000.0, Math.min(1000.0, delta));
            deltas.add(new SkillDelta(skill, delta));
        }

        deltas.sort(Comparator.comparingDouble(d -> -Math.abs(d.deltaPercent())));

        List<NarrativeKeyDto> result = new ArrayList<>();
        for (int i = 0; i < Math.min(3, deltas.size()); i++) {
            SkillDelta d = deltas.get(i);
            String key = d.deltaPercent() > 0
                ? "development.narrative.increased"
                : "development.narrative.decreased";
            long rounded = Math.abs(Math.round(d.deltaPercent()));
            result.add(new NarrativeKeyDto(key, Map.of("skill", d.skill(), "percent", String.valueOf(rounded))));
        }
        return result;
    }

    private boolean isOnOrAfter(short year, short week, short refYear, short refWeek) {
        return year > refYear || (year == refYear && week >= refWeek);
    }

    private boolean isOnOrBefore(short year, short week, short refYear, short refWeek) {
        return year < refYear || (year == refYear && week <= refWeek);
    }
}
