package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.platform.development.contract.NarrativeKeyDto;
import com.softropic.skillars.platform.development.contract.SkillExposureResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlag;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluDashboardServiceTest {

    @Mock private SluWeeklySnapshotRepository snapshotRepository;
    @Mock private NeglectedSkillFlagRepository flagRepository;
    @Mock private SluNarrativeService narrativeService;

    private SluDashboardService service;

    private static final Long PLAYER_ID = 1000L;

    @BeforeEach
    void setUp() {
        service = new SluDashboardService(snapshotRepository, flagRepository, narrativeService);
    }

    @Test
    void getWeeklyExposure_returnsCurrentWeekSluPerSkill() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        short curYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short curWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<PlayerSluWeeklySnapshot> snapshots = List.of(
            makeSnapshot(curYear, curWeek, "PAC", new BigDecimal("10.00")),
            makeSnapshot(curYear, curWeek, "SHO", new BigDecimal("5.00")),
            makeSnapshot(curYear, curWeek, "DRI", new BigDecimal("7.50"))
        );
        when(snapshotRepository.findByPlayerIdFromWeek(anyLong(), anyShort(), anyShort()))
            .thenReturn(snapshots);
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());

        SkillExposureResponse response = service.getWeeklyExposure(PLAYER_ID, 8);

        assertThat(response.currentWeek()).containsKeys("PAC", "SHO", "DRI");
        assertThat(response.currentWeek().get("PAC")).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void getWeeklyExposure_withFewerThanRequestedWeeks_returnsAvailableWeeks() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        short curYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short curWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short prevWeek = curWeek > 1 ? (short) (curWeek - 1) : 52;
        short prevPrevWeek = prevWeek > 1 ? (short) (prevWeek - 1) : 52;

        List<PlayerSluWeeklySnapshot> snapshots = List.of(
            makeSnapshot(curYear, curWeek, "PAC", new BigDecimal("10.00")),
            makeSnapshot(curYear, prevWeek, "PAC", new BigDecimal("8.00")),
            makeSnapshot(curYear, prevPrevWeek, "PAC", new BigDecimal("6.00"))
        );
        when(snapshotRepository.findByPlayerIdFromWeek(anyLong(), anyShort(), anyShort()))
            .thenReturn(snapshots);
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());

        SkillExposureResponse response = service.getWeeklyExposure(PLAYER_ID, 8);

        assertThat(response.trend()).hasSize(3);
    }

    @Test
    void getWeeklyExposure_withNoData_returnsEmptyCurrentWeekAndEmptyTrend() {
        when(snapshotRepository.findByPlayerIdFromWeek(anyLong(), anyShort(), anyShort()))
            .thenReturn(List.of());
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());

        SkillExposureResponse response = service.getWeeklyExposure(PLAYER_ID, 8);

        assertThat(response.currentWeek()).isEmpty();
        assertThat(response.trend()).isEmpty();
    }

    @Test
    void getNarrativeSummary_withIncreasing_returnsIncreasedKey() {
        NarrativeKeyDto increasing = new NarrativeKeyDto(
            "development.narrative.increased",
            java.util.Map.of("skill", "PAC", "percent", "42")
        );
        when(narrativeService.generate(PLAYER_ID)).thenReturn(List.of(increasing));

        List<NarrativeKeyDto> result = service.getNarrativeSummary(PLAYER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("development.narrative.increased");
    }

    @Test
    void getNarrativeSummary_withDecreasing_returnsDecreasedKey() {
        NarrativeKeyDto decreasing = new NarrativeKeyDto(
            "development.narrative.decreased",
            java.util.Map.of("skill", "SHO", "percent", "15")
        );
        when(narrativeService.generate(PLAYER_ID)).thenReturn(List.of(decreasing));

        List<NarrativeKeyDto> result = service.getNarrativeSummary(PLAYER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("development.narrative.decreased");
    }

    @Test
    void getNarrativeSummary_withZeroPreviousMonth_excludesThatSkill() {
        // SluNarrativeService is responsible for exclusion; dashboard just delegates
        when(narrativeService.generate(PLAYER_ID)).thenReturn(List.of());

        List<NarrativeKeyDto> result = service.getNarrativeSummary(PLAYER_ID);

        assertThat(result).isEmpty();
    }

    private PlayerSluWeeklySnapshot makeSnapshot(short year, short week, String skill, BigDecimal slu) {
        PlayerSluWeeklySnapshot.PlayerSluSnapshotId id = new PlayerSluWeeklySnapshot.PlayerSluSnapshotId();
        id.setPlayerId(PLAYER_ID);
        id.setSkillCode(skill);
        id.setIsoYear(year);
        id.setIsoWeek(week);
        PlayerSluWeeklySnapshot s = new PlayerSluWeeklySnapshot();
        s.setId(id);
        s.setTotalSlu(slu);
        return s;
    }
}
