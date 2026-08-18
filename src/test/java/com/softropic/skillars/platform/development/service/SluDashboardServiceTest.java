package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.development.contract.NarrativeKeyDto;
import com.softropic.skillars.platform.development.contract.SkillExposureResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.softropic.skillars.platform.security.service.SecurityUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SluDashboardServiceTest {

    @Mock private SluWeeklySnapshotRepository snapshotRepository;
    @Mock private NeglectedSkillFlagRepository flagRepository;
    @Mock private SluNarrativeService narrativeService;
    @Mock private SecurityUtil securityUtil;
    @Mock private CoachPlayerAuthorizationService coachPlayerAuthorizationService;
    @Mock private PlayerProfileRepository playerProfileRepository;

    private SluDashboardService service;

    private static final Long PLAYER_ID = 1000L;

    @BeforeEach
    void setUp() {
        service = new SluDashboardService(
            snapshotRepository, flagRepository, narrativeService, securityUtil,
            coachPlayerAuthorizationService, playerProfileRepository);
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    @Test
    void getWeeklyExposure_returnsCurrentWeekSluPerSkill() {
        // Literal expectations rather than a test-side re-implementation of the production formula.
        // NOT because the old form could not detect a date-math regression — it could: the removed
        // `now.minusWeeks(8 - 1)` was computed from a test-local literal, so mutating production to
        // `minusWeeks(weeksBack)` desynced the eq() matchers and MockitoExtension's default
        // STRICT_STUBS failed the test with PotentialStubbingProblem. (The skillars-deferred-30 AC5
        // premise that the old test "would stay green" was wrong; its code review corrected it.)
        // What the literals actually buy is that the expected values are readable and independently
        // checkable at a glance, and that the clock is pinned to a rollover-spanning date instead of
        // an arbitrary mid-year one: 2027-01-06 is a Wednesday in ISO week 1 of 2027, and
        // minusWeeks(7) lands on 2026-11-18, ISO week 47 of 2026 — so the from/cur pair straddles a
        // week-based-year boundary the old 2026-08-19 pin never exercised. Verified by hand.
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2027-01-06T10:00:00Z"), ZoneOffset.UTC));
        short curYear = (short) 2027;
        short curWeek = (short) 1;
        short fromYear = (short) 2026;
        short fromWeek = (short) 47;

        List<PlayerSluWeeklySnapshot> snapshots = List.of(
            makeSnapshot(curYear, curWeek, "PAC", new BigDecimal("10.00")),
            makeSnapshot(curYear, curWeek, "SHO", new BigDecimal("5.00")),
            makeSnapshot(curYear, curWeek, "DRI", new BigDecimal("7.50"))
        );
        when(snapshotRepository.findByPlayerIdFromWeek(eq(PLAYER_ID), eq(fromYear), eq(fromWeek), eq(curYear), eq(curWeek)))
            .thenReturn(snapshots);
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());

        SkillExposureResponse response = service.getWeeklyExposure(PLAYER_ID, 8);

        assertThat(response.currentWeek()).containsKeys("PAC", "SHO", "DRI");
        assertThat(response.currentWeek().get("PAC")).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void getWeeklyExposure_withFewerThanRequestedWeeks_returnsAvailableWeeks() {
        // Literal expectations for the mocked findByPlayerIdFromWeek call, per the same rationale as
        // getWeeklyExposure_returnsCurrentWeekSluPerSkill above. fromYear/fromWeek/curYear/curWeek
        // are that call's arguments; curYear/curWeek additionally build fixture snapshots, as do the
        // prev* pairs. prevYear/prevWeek/prevPrevYear/prevPrevWeek are NOT call arguments, so they
        // stay derived from real calendar arithmetic off the pinned clock rather than hardcoded —
        // which is what makes them roll back correctly across the year boundary, per the note below.
        // At this clock they land on ISO weeks 53 and 52 of 2026; 2026 is a 53-week ISO year, so
        // this fixture exercises the long-year case as well as the rollover.
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2027-01-06T10:00:00Z"), ZoneOffset.UTC));
        ZonedDateTime now = ZonedDateTime.now(TestClockProvider.getClock()).withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime prevWeekDt = now.minusWeeks(1);
        ZonedDateTime prevPrevWeekDt = now.minusWeeks(2);

        short curYear = (short) 2027;
        short curWeek = (short) 1;
        // Derive prev/prevPrev year+week from actual calendar arithmetic (not curWeek-1/-2) so
        // ISO week 1 (early January) correctly rolls back into the prior ISO week-based year
        // instead of wrapping to a hardcoded week 52 of the CURRENT year.
        short prevYear = (short) prevWeekDt.get(IsoFields.WEEK_BASED_YEAR);
        short prevWeek = (short) prevWeekDt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short prevPrevYear = (short) prevPrevWeekDt.get(IsoFields.WEEK_BASED_YEAR);
        short prevPrevWeek = (short) prevPrevWeekDt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short fromYear = (short) 2026;
        short fromWeek = (short) 47;

        List<PlayerSluWeeklySnapshot> snapshots = List.of(
            makeSnapshot(curYear, curWeek, "PAC", new BigDecimal("10.00")),
            makeSnapshot(prevYear, prevWeek, "PAC", new BigDecimal("8.00")),
            makeSnapshot(prevPrevYear, prevPrevWeek, "PAC", new BigDecimal("6.00"))
        );
        when(snapshotRepository.findByPlayerIdFromWeek(eq(PLAYER_ID), eq(fromYear), eq(fromWeek), eq(curYear), eq(curWeek)))
            .thenReturn(snapshots);
        when(flagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());

        SkillExposureResponse response = service.getWeeklyExposure(PLAYER_ID, 8);

        assertThat(response.trend()).hasSize(3);
    }

    @Test
    void getWeeklyExposure_withNoData_returnsEmptyCurrentWeekAndEmptyTrend() {
        // Literal expectations — see getWeeklyExposure_returnsCurrentWeekSluPerSkill above.
        TestClockProvider.setClock(Clock.fixed(Instant.parse("2027-01-06T10:00:00Z"), ZoneOffset.UTC));
        short curYear = (short) 2027;
        short curWeek = (short) 1;
        short fromYear = (short) 2026;
        short fromWeek = (short) 47;

        when(snapshotRepository.findByPlayerIdFromWeek(eq(PLAYER_ID), eq(fromYear), eq(fromWeek), eq(curYear), eq(curWeek)))
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
