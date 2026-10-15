package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.RosterPlayerActivityProjection;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.RosterEntryResponse;
import com.softropic.skillars.platform.development.contract.RosterResponse;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * skillars-12-1 code review (2026-10-15) Patch: {@link CoachRosterService#getRoster} does NOT
 * resolve the coach from {@code SecurityUtil} (it takes {@code coachId} as a parameter) and so CAN
 * be unit-tested in isolation — the prior claim to the contrary (in {@code CoachRosterResourceIT}'s
 * Javadoc) was false and had left the empty-roster early return, the lastSessionDate/latestBookingTime
 * merge, the future-booking/dormant split, and the declining-trend loop with no unit coverage at all.
 */
@ExtendWith(MockitoExtension.class)
class CoachRosterServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private PlayerProfileRepository playerProfileRepository;
    @Mock private NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    @Mock private SluWeeklySnapshotRepository snapshotRepository;
    @Mock private ConfigService configService;
    @Mock private CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;

    private CoachRosterService service;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final Long PLAYER_ID = 1000L;
    private static final Instant NOW = Instant.parse("2027-01-06T10:00:00Z");

    // Fixed ISO calendar facts for the NOW instant above (2027-01-06 is ISO week 1 of 2027; 2026 is a
    // 53-week ISO year, so one week back is 2026-W53 and two weeks back is 2026-W52) — same pin and
    // same derived weeks as SluDashboardServiceTest uses for this exact instant.
    private static final short CUR_YEAR = 2027;
    private static final short CUR_WEEK = 1;
    private static final short PREV_YEAR = 2026;
    private static final short PREV_WEEK = 53;
    private static final short PREV_PREV_YEAR = 2026;
    private static final short PREV_PREV_WEEK = 52;

    @BeforeEach
    void setUp() {
        service = new CoachRosterService(
            bookingRepository, playerProfileRepository,
            neglectedSkillFlagRepository, snapshotRepository, configService,
            coachPlayerLastSessionResolver);
        TestClockProvider.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        // Default: no merged signal at all — individual tests override via resolve(PLAYER_ID, COACH_ID)
        // stubs below. lenient() because the no-qualifying-activity test never reaches buildEntry.
        lenient().when(coachPlayerLastSessionResolver.resolve(anyLong(), any()))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(null, null));
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    @Test
    void getRoster_withNoQualifyingActivity_returnsEmptyRosterWithoutFurtherLookups() {
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(eq(COACH_ID), anyList(), any()))
            .thenReturn(List.of());

        RosterResponse response = service.getRoster(COACH_ID);

        assertThat(response.players()).isEmpty();
        verifyNoInteractions(playerProfileRepository, coachPlayerLastSessionResolver, neglectedSkillFlagRepository, snapshotRepository);
    }

    @Test
    void getRoster_noSluHistory_fallsBackToBookingTimeForRecencyAndIsNotDormant() {
        stubActivity(NOW.minusSeconds(3600)); // booked 1 hour ago
        stubPlayerName("Fresh Signing");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.lastSessionDate()).isNull();
        assertThat(entry.dormant()).isFalse();
    }

    @Test
    void getRoster_lastSessionDateAfterLatestBookingTime_usesSessionDateForRecency() {
        Instant bookingTime = NOW.minus(Duration.ofDays(60));
        Instant sessionDate = NOW.minus(Duration.ofDays(5)); // more recent than the booking
        stubActivity(bookingTime);
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(sessionDate, null));
        stubPlayerName("Recently Trained");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        // 5 days since last activity, well under the 30-day threshold.
        assertThat(entry.dormant()).isFalse();
        assertThat(entry.lastSessionDate()).isEqualTo(sessionDate);
    }

    // skillars-12-1 code review (2026-10-15) Patch regression test, owner decision: a future-dated
    // roster-qualifying booking (e.g. ACCEPTED three weeks out) no longer produces a NEGATIVE
    // daysSinceLastActivity (the original bug — see RosterAttentionCalculator's superseded
    // Math.max(...,0) guard) — the resolved formula (`max(lastSessionDate, min(latestBookingTime,
    // now))`) clamps the future booking's contribution to "now" instead, and `dormant` is suppressed
    // via the explicit `hasUpcomingBooking` signal rather than relying on that clamp. The recency
    // score itself still reads as "just active" here (0 days) — a coach who already booked the
    // player's next session doesn't need the score escalated either, independent of how stale their
    // actual last session was; this is the resolved, intended behavior, not a residual gap.
    @Test
    void getRoster_futureBooking_clampsRecencyToNow_andSuppressesDormantRegardlessOfOldSessionHistory() {
        Instant oldSessionDate = NOW.minus(Duration.ofDays(400));
        Instant futureBookingTime = NOW.plus(Duration.ofDays(21));
        stubActivity(futureBookingTime);
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(oldSessionDate, null));
        stubPlayerName("Long Silent But Rebooked");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.dormant()).isFalse();
        assertThat(entry.attentionScore()).isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void getRoster_countsOnlyDecliningSkills_ignoringImprovingAndInsufficientData() {
        stubActivity(NOW.minusSeconds(3600));
        stubPlayerName("Mixed Trends");
        stubNoNeglectedFlags();
        stubDormantAfterDays(30);
        List<PlayerSluWeeklySnapshot> snapshots = new ArrayList<>();
        snapshots.addAll(weeklySeries("PAC", 10, 8, 6));   // declining
        snapshots.addAll(weeklySeries("SHO", 2, 4, 6));    // improving
        snapshots.addAll(weeklySeries("DRI", 5, 5));       // only 2 weeks -> insufficient data
        when(snapshotRepository.findByPlayerIdFromWeek(eq(PLAYER_ID), anyShort(), anyShort(), anyShort(), anyShort()))
            .thenReturn(snapshots);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        // Only the recency (~0) and one declining skill (1/5 cap = 0.2 * 0.25 weight = 0.05) terms
        // contribute; neglected is 0.
        assertThat(entry.attentionScore()).isCloseTo(0.05, offset(1e-9));
    }

    @Test
    void getRoster_mapsPlayerNameFromProfileRepository() {
        stubActivity(NOW.minusSeconds(3600));
        stubPlayerName("Alex Kane");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.playerName()).isEqualTo("Alex Kane");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubActivity(Instant latestBookingTime) {
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(eq(COACH_ID), anyList(), any()))
            .thenReturn(List.of(activity(PLAYER_ID, latestBookingTime)));
    }

    private void stubPlayerName(String name) {
        PlayerProfile profile = new PlayerProfile();
        profile.setId(PLAYER_ID);
        profile.setName(name);
        when(playerProfileRepository.findAllById(List.of(PLAYER_ID))).thenReturn(List.of(profile));
    }

    private void stubNoNeglectedFlags() {
        when(neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(List.of());
    }

    private void stubNoTrendData() {
        when(snapshotRepository.findByPlayerIdFromWeek(eq(PLAYER_ID), anyShort(), anyShort(), anyShort(), anyShort()))
            .thenReturn(List.of());
    }

    private void stubDormantAfterDays(long days) {
        when(configService.getBoundedLong(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(days);
    }

    private RosterEntryResponse singleEntry(RosterResponse response) {
        assertThat(response.players()).hasSize(1);
        return response.players().get(0);
    }

    private static RosterPlayerActivityProjection activity(Long playerId, Instant latestBookingTime) {
        return new RosterPlayerActivityProjection() {
            @Override
            public Long getPlayerId() {
                return playerId;
            }

            @Override
            public Instant getLatestBookingTime() {
                return latestBookingTime;
            }
        };
    }

    private static List<PlayerSluWeeklySnapshot> weeklySeries(String skillCode, int... valuesChronological) {
        short[][] weeks = switch (valuesChronological.length) {
            case 2 -> new short[][] {{PREV_YEAR, PREV_WEEK}, {CUR_YEAR, CUR_WEEK}};
            case 3 -> new short[][] {{PREV_PREV_YEAR, PREV_PREV_WEEK}, {PREV_YEAR, PREV_WEEK}, {CUR_YEAR, CUR_WEEK}};
            default -> throw new IllegalArgumentException("add a weeks[] case for " + valuesChronological.length);
        };
        List<PlayerSluWeeklySnapshot> result = new ArrayList<>();
        for (int i = 0; i < valuesChronological.length; i++) {
            result.add(makeSnapshot(weeks[i][0], weeks[i][1], skillCode, BigDecimal.valueOf(valuesChronological[i])));
        }
        return result;
    }

    private static PlayerSluWeeklySnapshot makeSnapshot(short year, short week, String skillCode, BigDecimal totalSlu) {
        PlayerSluWeeklySnapshot.PlayerSluSnapshotId id = new PlayerSluWeeklySnapshot.PlayerSluSnapshotId();
        id.setPlayerId(PLAYER_ID);
        id.setSkillCode(skillCode);
        id.setIsoYear(year);
        id.setIsoWeek(week);
        PlayerSluWeeklySnapshot s = new PlayerSluWeeklySnapshot();
        s.setId(id);
        s.setTotalSlu(totalSlu);
        return s;
    }
}
