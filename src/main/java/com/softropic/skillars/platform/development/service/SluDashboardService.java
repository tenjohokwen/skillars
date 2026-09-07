package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.development.contract.NarrativeKeyDto;
import com.softropic.skillars.platform.development.contract.SkillExposureResponse;
import com.softropic.skillars.platform.development.contract.SkillTrend;
import com.softropic.skillars.platform.development.contract.SkillTrendResponse;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
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
        ZonedDateTime now = ZonedDateTime.now(ClockProvider.getClock()).withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime from = now.minusWeeks(weeksBack - 1);
        short fromYear = (short) from.get(IsoFields.WEEK_BASED_YEAR);
        short fromWeek = (short) from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short currentYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short currentWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<PlayerSluWeeklySnapshot> snapshots =
            snapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek, currentYear, currentWeek);

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

    /**
     * Per-skill weekly SLU trend (improving / flat / declining) over the last {@code weeksBack}
     * ISO weeks. skillars-deferred-98 AC1b (ledger line ~1292): a coaching/analytics signal that is
     * independent of the neglected-skill gating flag — it answers "is this skill on the way up or
     * sliding?", nothing is blocked by it.
     *
     * <p>Read-time derivation over the existing immutable weekly rows (same
     * {@link SluWeeklySnapshotRepository#findByPlayerIdFromWeek} series the exposure card already
     * uses) — no new schema, no scheduled job. Classification is delegated to the pure
     * {@link SluTrendClassifier}.
     */
    public SkillTrendResponse getSkillTrends(Long playerId, int weeksBack) {
        requireCoachPlayerRelationshipIfCoach(playerId);
        ZonedDateTime now = ZonedDateTime.now(ClockProvider.getClock()).withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime from = now.minusWeeks(weeksBack - 1);
        short fromYear = (short) from.get(IsoFields.WEEK_BASED_YEAR);
        short fromWeek = (short) from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short currentYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short currentWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<PlayerSluWeeklySnapshot> snapshots =
            snapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek, currentYear, currentWeek);

        // The query is ordered (isoYear, isoWeek) ASC and (player, skill, year, week) is unique, so
        // appending per skill in iteration order yields each skill's chronological weekly series.
        Map<String, List<BigDecimal>> seriesBySkill = new TreeMap<>();
        for (PlayerSluWeeklySnapshot s : snapshots) {
            seriesBySkill.computeIfAbsent(s.getId().getSkillCode(), k -> new ArrayList<>())
                .add(s.getTotalSlu());
        }

        List<SkillTrend> trends = seriesBySkill.entrySet().stream()
            .map(e -> {
                SluTrendClassifier.Result r = SluTrendClassifier.classify(e.getValue());
                return new SkillTrend(e.getKey(), r.direction(), r.slopePerWeek(), r.weeksObserved());
            })
            .collect(Collectors.toList());

        return new SkillTrendResponse(trends);
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
