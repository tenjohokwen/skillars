package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.TestClockProvider;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.RosterPlayerActivityProjection;
import com.softropic.skillars.platform.config.service.ConfigBounds;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

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
            coachPlayerLastSessionResolver, new CoachAccessWindow(configService));
        TestClockProvider.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        // Default: no merged signal at all — individual tests override via resolve(PLAYER_ID, COACH_ID)
        // stubs below. lenient() because the no-qualifying-activity test never reaches buildEntry.
        lenient().when(coachPlayerLastSessionResolver.resolve(anyLong(), any()))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(null, null));
        // skillars-deferred-153 AC3: getRoster() now reads maxPlayers via the same 4-arg
        // getBoundedLong call shape as dormantAfterDays, UNCONDITIONALLY and before even querying
        // activities — lenient() because the no-qualifying-activity/missing-profile tests never
        // reach buildEntry's own dormantAfterDays read but still need a sane (non-zero) default here,
        // since PageRequest.of(0, 0) throws.
        //
        // skillars-deferred-153 code review (2026-10-17): stubbed PER KEY, not with a blanket
        // anyString(). The previous single anyString() stub matched EVERY bounded-long key, so it
        // silently retuned development.roster.dormantAfterDays to the maxPlayers value (150) for
        // every test that did not override it — and stubDormantAfterDays() had the mirror-image
        // problem, setting the effective maxPlayers to the dormancy threshold. Each key now gets its
        // own value and the coupling is gone.
        // skillars-12-10: the shared coach-access window (default 90 days).
        lenient().when(configService.getBoundedLong(
                eq(ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.key()), anyLong(), anyLong(), anyLong()))
            .thenReturn(ConfigBounds.TIMELINE_COACH_ACCESS_EXPIRY_DAYS.defaultValue());
        lenient().when(configService.getBoundedLong(
                eq(ConfigBounds.DEVELOPMENT_ROSTER_MAX_PLAYERS.key()), anyLong(), anyLong(), anyLong()))
            .thenReturn(ConfigBounds.DEVELOPMENT_ROSTER_MAX_PLAYERS.defaultValue());
        lenient().when(configService.getBoundedLong(
                eq(ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.key()), anyLong(), anyLong(), anyLong()))
            .thenReturn(ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.defaultValue());
    }

    @AfterEach
    void tearDown() {
        TestClockProvider.unsetClock();
    }

    @Test
    void getRoster_withNoQualifyingActivity_returnsEmptyRosterWithoutFurtherLookups() {
        // skillars-deferred-153 code review (2026-10-17): 5-arg form — `now` was inserted SECOND so
        // the in-@Query ORDER BY can clamp a future-dated booking down to `now` before ranking.
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(
                eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), any(Pageable.class)))
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

    // skillars-12-6 AC8/AC9: the three caption inputs. Rule 1 — an attended session exists.
    @Test
    void getRoster_attendedSession_exposesDaysSinceLastSession() {
        Instant sessionDate = NOW.minus(Duration.ofHours(53)); // 2 whole days
        stubActivity(NOW.minus(Duration.ofDays(10)));
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(sessionDate, null));
        stubPlayerName("Trained");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.daysSinceLastSession()).isEqualTo(2L);
        assertThat(entry.hasUpcomingBooking()).isFalse();
    }

    // Rule 2 — no attended session and the latest booking is in the past: "booked, but no attended
    // session yet" (the case where the dormant badge could fire next to a "never trained" caption).
    @Test
    void getRoster_noAttendedSessionAndPastBooking_hasNullDaysAndNoUpcomingBooking() {
        stubActivity(NOW.minus(Duration.ofDays(60)));
        stubPlayerName("Booked Never Attended");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.daysSinceLastSession()).isNull();
        assertThat(entry.hasUpcomingBooking()).isFalse();
        assertThat(entry.dormant()).isTrue();
    }

    // Rule 3 — no attended session, upcoming booking: genuinely "haven't trained together yet".
    @Test
    void getRoster_noAttendedSessionAndUpcomingBooking_hasNullDaysAndUpcomingBooking() {
        stubActivity(NOW.plus(Duration.ofDays(3)));
        stubPlayerName("First Session Ahead");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.daysSinceLastSession()).isNull();
        assertThat(entry.hasUpcomingBooking()).isTrue();
        assertThat(entry.dormant()).isFalse();
    }

    @Test
    void getRoster_lastSessionDateMarginallyAfterNow_clampsDaysToZero() {
        stubActivity(NOW.minus(Duration.ofDays(2)));
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID))
            .thenReturn(new CoachPlayerLastSessionResolver.LastCoachSessionSignal(NOW.plusSeconds(30), null));
        stubPlayerName("Clock Skew");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        assertThat(singleEntry(service.getRoster(COACH_ID)).daysSinceLastSession()).isZero();
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

    // skillars-deferred-152 AC6: findAllById silently returns fewer rows than requested for a
    // missing player_profiles row (latent today, see Dev Notes) — the entry is filtered out of the
    // roster entirely rather than surfacing a bare "playerName": null, and buildEntry's own
    // downstream lookups (neglected flags, trend data, the last-session resolver) are never reached
    // for it.
    @Test
    void getRoster_playerProfileRowMissing_filtersEntryOutOfRoster() {
        stubActivity(NOW.minusSeconds(3600));
        when(playerProfileRepository.findAllById(List.of(PLAYER_ID))).thenReturn(List.of());

        RosterResponse response = service.getRoster(COACH_ID);

        assertThat(response.players()).isEmpty();
        verifyNoInteractions(coachPlayerLastSessionResolver, neglectedSkillFlagRepository, snapshotRepository);
    }

    // skillars-deferred-153 AC3: the configured maxPlayers bound is passed through as the
    // PageRequest's size, unsorted (the query's own ORDER BY already orders most-recently-active
    // first — a sorted Pageable would be appended after it and silently do nothing).
    @Test
    void getRoster_candidateSetExceedsMaxPlayers_passesConfiguredCapAsUnsortedPageRequestSize() {
        when(configService.getBoundedLong(
                eq(ConfigBounds.DEVELOPMENT_ROSTER_MAX_PLAYERS.key()),
                anyLong(), anyLong(), anyLong()))
            .thenReturn(200L);
        stubActivity(NOW.minusSeconds(3600));
        stubPlayerName("Capped Candidate");
        stubNoNeglectedFlags();
        stubNoTrendData();

        service.getRoster(COACH_ID);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(bookingRepository).findRosterPlayerActivityByCoachIdAndStatusIn(
            eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), pageableCaptor.capture());
        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isZero();
        assertThat(captured.getPageSize()).isEqualTo(200);
        assertThat(captured.getSort().isUnsorted()).isTrue();
    }

    // skillars-deferred-153 code review (2026-10-17): NEW — the inserted `now` parameter must be the
    // service's own ClockProvider-pinned instant, and recencyFloor must be 730 days before it. The
    // query's ORDER BY clamps MAX(requestedStartTime) against this `now`, so passing the wrong value
    // (or a second, independently-taken Instant.now()) would silently change which players survive
    // the cap for a coach whose candidate set exceeds maxPlayers.
    @Test
    void getRoster_passesClockPinnedNowAndSevenThirtyDayRecencyFloorToTheQuery() {
        stubActivity(NOW.minusSeconds(3600));
        stubPlayerName("Clock Pinned");
        stubNoNeglectedFlags();
        stubNoTrendData();
        stubDormantAfterDays(30);

        service.getRoster(COACH_ID);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> floorCaptor = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(bookingRepository).findRosterPlayerActivityByCoachIdAndStatusIn(
            eq(COACH_ID), nowCaptor.capture(), anyList(), floorCaptor.capture(), any(Pageable.class));

        assertThat(nowCaptor.getValue()).isEqualTo(NOW);
        assertThat(floorCaptor.getValue()).isEqualTo(NOW.minus(Duration.ofDays(730)));
    }

    // skillars-deferred-153 code review (2026-10-17): NEW — the two roster config keys must read
    // independently. Guards the exact defect the old blanket anyString() stub hid: with maxPlayers
    // configured high and dormantAfterDays configured low, the 60-day-stale player must still read
    // as dormant (i.e. dormantAfterDays was NOT overwritten by the maxPlayers value).
    @Test
    void getRoster_maxPlayersAndDormantAfterDaysAreReadIndependently() {
        when(configService.getBoundedLong(
                eq(ConfigBounds.DEVELOPMENT_ROSTER_MAX_PLAYERS.key()),
                anyLong(), anyLong(), anyLong()))
            .thenReturn(500L);
        stubDormantAfterDays(5);
        stubActivity(NOW.minus(Duration.ofDays(60)));
        stubPlayerName("Long Dormant");
        stubNoNeglectedFlags();
        stubNoTrendData();

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.dormant()).isTrue();

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(bookingRepository).findRosterPlayerActivityByCoachIdAndStatusIn(
            eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
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

    // ── skillars-12-10 AC7: lapsed group ─────────────────────────────────────────────────────────

    @Test
    void getRoster_bookingEndOutsideWindow_isListedAsExpiredWithDataMinimised() {
        Instant start = NOW.minus(java.time.Duration.ofDays(100));
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(
                eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(activity(PLAYER_ID, start, start.plusSeconds(3600))));
        stubPlayerName("Lapsed Lou");
        Instant lastSession = start.plusSeconds(3600);
        when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(lastSession, null));

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));

        assertThat(entry.accessExpired()).isTrue();
        assertThat(entry.playerName()).isEqualTo("Lapsed Lou");
        assertThat(entry.lastSessionDate()).isEqualTo(lastSession);
        assertThat(entry.openNeglectedSkillCount()).isZero();
        assertThat(entry.attentionScore()).isZero();
        assertThat(entry.dormant()).isFalse();
        // The data the coach can no longer read is neither queried nor returned.
        verifyNoInteractions(neglectedSkillFlagRepository, snapshotRepository);
    }

    @Test
    void getRoster_bookingEndJustInsideWindow_isNotExpired() {
        Instant end = NOW.minus(java.time.Duration.ofDays(90)).plusSeconds(120);
        stubPlayerName("Edge Eve");
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(
                eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(activity(PLAYER_ID, end.minusSeconds(3600), end)));
        stubNoNeglectedFlags();
        stubNoTrendData();

        assertThat(singleEntry(service.getRoster(COACH_ID)).accessExpired()).isFalse();
    }

    @Test
    void getRoster_futureBookingEnd_isNotExpired_evenWithNoAttendedSession() {
        stubActivity(NOW.plusSeconds(86_400));
        stubPlayerName("New Nia");
        stubNoNeglectedFlags();
        stubNoTrendData();

        RosterEntryResponse entry = singleEntry(service.getRoster(COACH_ID));
        assertThat(entry.accessExpired()).isFalse();
        assertThat(entry.hasUpcomingBooking()).isTrue();
    }

    @Test
    void getRoster_expiredEntriesSortAfterOpenOnes_byLastSessionDesc() {
        Instant old1 = NOW.minus(java.time.Duration.ofDays(120));
        Instant old2 = NOW.minus(java.time.Duration.ofDays(200));
        Instant recent = NOW.minus(java.time.Duration.ofDays(5));
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(
                eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(activity(1L, old2), activity(2L, old1), activity(3L, recent)));
        PlayerProfile p1 = profile(1L, "Old2"), p2 = profile(2L, "Old1"), p3 = profile(3L, "Recent");
        when(playerProfileRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(p1, p2, p3));
        when(coachPlayerLastSessionResolver.resolve(1L, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(old2, null));
        when(coachPlayerLastSessionResolver.resolve(2L, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(old1, null));
        when(coachPlayerLastSessionResolver.resolve(3L, COACH_ID)).thenReturn(
            new CoachPlayerLastSessionResolver.LastCoachSessionSignal(recent, null));
        when(neglectedSkillFlagRepository.countByPlayerIdAndResolvedAtIsNull(3L)).thenReturn(0);
        when(snapshotRepository.findByPlayerIdFromWeek(eq(3L), anyShort(), anyShort(), anyShort(), anyShort()))
            .thenReturn(List.of());

        List<String> names = service.getRoster(COACH_ID).players().stream().map(RosterEntryResponse::playerName).toList();

        assertThat(names).containsExactly("Recent", "Old1", "Old2");
    }

    private static PlayerProfile profile(Long id, String name) {
        PlayerProfile profile = new PlayerProfile();
        profile.setId(id);
        profile.setName(name);
        return profile;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubActivity(Instant latestBookingTime) {
        when(bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(
                eq(COACH_ID), any(Instant.class), anyList(), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(activity(PLAYER_ID, latestBookingTime)));
    }

    private void stubPlayerName(String name) {
        PlayerProfile profile = new PlayerProfile();
        profile.setId(PLAYER_ID);
        profile.setName(name);
        when(playerProfileRepository.findAllById(List.of(PLAYER_ID))).thenReturn(List.of(profile));
    }

    private void stubNoNeglectedFlags() {
        // skillars-deferred-152 AC4: production code now calls the count query, not the
        // entity-returning one -- stubbing the old method here would be an unconsulted stub
        // and throw UnnecessaryStubbingException under this class's strict (MockitoExtension) stubs.
        when(neglectedSkillFlagRepository.countByPlayerIdAndResolvedAtIsNull(PLAYER_ID)).thenReturn(0);
    }

    private void stubNoTrendData() {
        when(snapshotRepository.findByPlayerIdFromWeek(eq(PLAYER_ID), anyShort(), anyShort(), anyShort(), anyShort()))
            .thenReturn(List.of());
    }

    // skillars-deferred-153 code review (2026-10-17): keyed on DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS
    // specifically. The previous anyString() form also overwrote maxPlayers with `days` — so
    // stubDormantAfterDays(30) silently capped the candidate set at 30, and stubDormantAfterDays(0)
    // would have thrown from PageRequest.of(0, 0).
    private void stubDormantAfterDays(long days) {
        when(configService.getBoundedLong(
                eq(ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.key()), anyLong(), anyLong(), anyLong()))
            .thenReturn(days);
    }

    private RosterEntryResponse singleEntry(RosterResponse response) {
        assertThat(response.players()).hasSize(1);
        return response.players().get(0);
    }

    private static RosterPlayerActivityProjection activity(Long playerId, Instant latestBookingTime) {
        return activity(playerId, latestBookingTime, latestBookingTime.plusSeconds(3600));
    }

    private static RosterPlayerActivityProjection activity(
        Long playerId, Instant latestBookingTime, Instant latestBookingEndTime) {
        return new RosterPlayerActivityProjection() {
            @Override
            public Instant getLatestBookingEndTime() {
                return latestBookingEndTime;
            }

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
