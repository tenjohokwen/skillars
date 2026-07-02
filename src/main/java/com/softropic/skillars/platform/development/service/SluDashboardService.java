package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.NarrativeKeyDto;
import com.softropic.skillars.platform.development.contract.SkillExposureResponse;
import com.softropic.skillars.platform.development.contract.WeeklySkillTotals;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.security.contract.util.AuthoritiesConstants;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SluDashboardService {

    private final SluWeeklySnapshotRepository snapshotRepository;
    private final NeglectedSkillFlagRepository flagRepository;
    private final SluNarrativeService narrativeService;
    private final SecurityUtil securityUtil;
    private final CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    private final PlayerProfileRepository playerProfileRepository;

    public SkillExposureResponse getWeeklyExposure(Long playerId, int weeksBack) {
        requireCoachPlayerRelationshipIfCoach(playerId);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime from = now.minusWeeks(weeksBack - 1).with(DayOfWeek.MONDAY);
        short fromYear = (short) from.get(IsoFields.WEEK_BASED_YEAR);
        short fromWeek = (short) from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short currentYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short currentWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<PlayerSluWeeklySnapshot> snapshots =
            snapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek);

        // Group by week key for trend
        Map<String, Map<String, BigDecimal>> weekMap = new TreeMap<>();
        Map<String, BigDecimal> currentWeekMap = new HashMap<>();

        for (PlayerSluWeeklySnapshot s : snapshots) {
            short sy = s.getId().getIsoYear();
            short sw = s.getId().getIsoWeek();
            String weekKey = sy + "-" + String.format("%02d", sw);
            weekMap.computeIfAbsent(weekKey, k -> new HashMap<>())
                .merge(s.getId().getSkillCode(), s.getTotalSlu(), BigDecimal::add);
            if (sy == currentYear && sw == currentWeek) {
                currentWeekMap.merge(s.getId().getSkillCode(), s.getTotalSlu(), BigDecimal::add);
            }
        }

        List<WeeklySkillTotals> trend = weekMap.entrySet().stream()
            .map(e -> {
                String[] parts = e.getKey().split("-");
                short sy = Short.parseShort(parts[0]);
                short sw = Short.parseShort(parts[1]);
                return new WeeklySkillTotals(sy, sw, e.getValue());
            })
            .collect(Collectors.toList());

        List<String> neglectedCodes = flagRepository.findByPlayerIdAndResolvedAtIsNull(playerId)
            .stream()
            .map(f -> f.getSkillCode())
            .collect(Collectors.toList());

        return new SkillExposureResponse(currentWeekMap, trend, neglectedCodes);
    }

    public List<NarrativeKeyDto> getNarrativeSummary(Long playerId) {
        requireCoachPlayerRelationshipIfCoach(playerId);
        return narrativeService.generate(playerId);
    }

    private void requireCoachPlayerRelationshipIfCoach(Long playerId) {
        if (securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)
                && !playerProfileRepository.existsByIdAndParentId(playerId, securityUtil.requireCurrentUserId())) {
            coachPlayerAuthorizationService.requireCoachPlayerRelationship(
                securityUtil.getCurrentCoachUserId(), playerId);
        }
    }
}
