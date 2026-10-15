package com.softropic.skillars.platform.development.service;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.booking.repo.BookingRepository;
import com.softropic.skillars.platform.booking.repo.RosterPlayerActivityProjection;
import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.development.contract.RosterEntryResponse;
import com.softropic.skillars.platform.development.contract.RosterResponse;
import com.softropic.skillars.platform.development.contract.SkillTrendDirection;
import com.softropic.skillars.platform.development.repo.NeglectedSkillFlagRepository;
import com.softropic.skillars.platform.development.repo.PlayerSluWeeklySnapshot;
import com.softropic.skillars.platform.development.repo.SluWeeklySnapshotRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Derives the calling coach's "my players" roster, ranked by {@code attentionScore}, at read time —
 * no new entity/table (AC2). skillars-12-1.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CoachRosterService {

    // AC1: deliberately wider than, and independent of,
    // CoachPlayerAuthorizationService.ACTIVE_RELATIONSHIP_STATUSES (an authorization-scoped list,
    // not a roster-membership one — see Task 1.2). Never widen that constant to share this one.
    //
    // skillars-12-1 code review (2026-10-15) Patch, owner decision: DISPUTED and REFUND_PENDING
    // added — both are reachable from COMPLETED (BookingStateMachine) and represent a session that
    // genuinely happened; dropping the player from the roster mid-dispute (before it's known whether
    // it settles back to COMPLETED or proceeds to a refund) is wrong. REFUNDED stays excluded — a
    // processed refund is the engagement being formally unwound, a principled cut-off that avoids an
    // indefinite 730-day tail for a refunded one-off.
    private static final List<String> ROSTER_MEMBERSHIP_STATUSES = List.of(
        "ACCEPTED", "PAYMENT_PENDING", "CONFIRMED", "UPCOMING", "IN_PROGRESS", "PAUSED",
        "COMPLETED_PENDING_CONFIRMATION", "COMPLETED", "NO_SHOW_PLAYER", "NO_SHOW_COACH",
        "DISPUTED", "REFUND_PENDING");

    private static final long RECENCY_FLOOR_DAYS = 730;
    private static final int TREND_WEEKS_BACK = 12;

    private final BookingRepository bookingRepository;
    private final PlayerProfileRepository playerProfileRepository;
    private final NeglectedSkillFlagRepository neglectedSkillFlagRepository;
    private final SluWeeklySnapshotRepository snapshotRepository;
    private final ConfigService configService;
    private final CoachPlayerLastSessionResolver coachPlayerLastSessionResolver;

    public RosterResponse getRoster(UUID coachId) {
        Instant now = Instant.now(ClockProvider.getClock());
        Instant recencyFloor = now.minus(Duration.ofDays(RECENCY_FLOOR_DAYS));

        List<RosterPlayerActivityProjection> activities =
            bookingRepository.findRosterPlayerActivityByCoachIdAndStatusIn(
                coachId, ROSTER_MEMBERSHIP_STATUSES, recencyFloor);

        if (activities.isEmpty()) {
            return new RosterResponse(List.of());
        }

        List<Long> playerIds = activities.stream().map(RosterPlayerActivityProjection::getPlayerId).toList();
        Map<Long, String> namesByPlayerId = new HashMap<>();
        for (PlayerProfile profile : playerProfileRepository.findAllById(playerIds)) {
            namesByPlayerId.put(profile.getId(), profile.getName());
        }

        long dormantAfterDays = configService.getBoundedLong(
            ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.key(),
            ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.defaultValue(),
            ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.min(),
            ConfigBounds.DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS.max());

        List<RosterEntryResponse> entries = activities.stream()
            .map(activity -> buildEntry(activity, coachId, namesByPlayerId, dormantAfterDays, now))
            .sorted(Comparator.comparingDouble(RosterEntryResponse::attentionScore).reversed())
            .toList();

        return new RosterResponse(entries);
    }

    private RosterEntryResponse buildEntry(
        RosterPlayerActivityProjection activity,
        UUID coachId,
        Map<Long, String> namesByPlayerId,
        long dormantAfterDays,
        Instant now
    ) {
        Long playerId = activity.getPlayerId();
        Instant latestBookingTime = activity.getLatestBookingTime();

        // skillars-12-3 code review (2026-10-15) Patch, owner decision: was the narrow, SLU-only
        // sluRepository.findLastSessionDate read (AC3 of skillars-12-1) — widened to the merged
        // CoachPlayerLastSessionResolver signal so a Quick-Complete-only coach's roster entry agrees
        // with the same coach's Session Prep Card and timeline-access clock, instead of three
        // surfaces silently disagreeing about whether this coach and player have trained together.
        Instant lastSessionDate = coachPlayerLastSessionResolver.resolve(playerId, coachId).lastSessionDate();

        // skillars-12-1 code review (2026-10-15) Patch, owner decision: the recency signal is
        // past-only — a roster-qualifying booking can be future-dated (ACCEPTED/PAYMENT_PENDING/
        // CONFIRMED/UPCOMING), and treating it as "last activity" let an unpaid booking three weeks
        // out mask a player who hasn't actually trained in months. `hasUpcomingBooking` carries that
        // future-dated signal separately, suppressing only the dormant badge (see
        // RosterAttentionCalculator.Signals Javadoc) — never for a player returned by the roster
        // query, since latestBookingTime is always present.
        boolean hasUpcomingBooking = latestBookingTime.isAfter(now);
        Instant latestPastBookingTime = hasUpcomingBooking ? now : latestBookingTime;
        Instant lastActivityDate = lastSessionDate == null || lastSessionDate.isBefore(latestPastBookingTime)
            ? latestPastBookingTime
            : lastSessionDate;
        long daysSinceLastActivity = Duration.between(lastActivityDate, now).toDays();

        int neglectedCount = neglectedSkillFlagRepository.findByPlayerIdAndResolvedAtIsNull(playerId).size();

        int decliningCount = 0;
        for (List<BigDecimal> series : weeklySeriesBySkill(playerId).values()) {
            if (SluTrendClassifier.classify(series).direction() == SkillTrendDirection.DECLINING) {
                decliningCount++;
            }
        }

        RosterAttentionCalculator.Result result = RosterAttentionCalculator.compute(
            new RosterAttentionCalculator.Signals(
                daysSinceLastActivity, neglectedCount, decliningCount, dormantAfterDays, hasUpcomingBooking));

        return new RosterEntryResponse(
            playerId,
            namesByPlayerId.get(playerId),
            lastSessionDate,
            neglectedCount,
            result.dormant(),
            result.attentionScore());
    }

    // Task 2.3: calls the same two public pieces SluDashboardService.getSkillTrends does, one layer
    // lower — avoids that method's per-player relationship re-check (redundant given this service's
    // own coach-scoped filtering in getRoster, and 3 extra DB round-trips per roster player).
    private Map<String, List<BigDecimal>> weeklySeriesBySkill(Long playerId) {
        ZonedDateTime now = ZonedDateTime.now(ClockProvider.getClock()).withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime from = now.minusWeeks(TREND_WEEKS_BACK - 1);
        short fromYear = (short) from.get(IsoFields.WEEK_BASED_YEAR);
        short fromWeek = (short) from.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        short currentYear = (short) now.get(IsoFields.WEEK_BASED_YEAR);
        short currentWeek = (short) now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        List<PlayerSluWeeklySnapshot> snapshots =
            snapshotRepository.findByPlayerIdFromWeek(playerId, fromYear, fromWeek, currentYear, currentWeek);

        // Query is ordered (isoYear, isoWeek) ASC and (player, skill, year, week) is unique, so
        // appending per skill in iteration order yields each skill's chronological weekly series —
        // same invariant SluDashboardService.getSkillTrends relies on.
        Map<String, List<BigDecimal>> seriesBySkill = new TreeMap<>();
        for (PlayerSluWeeklySnapshot s : snapshots) {
            seriesBySkill.computeIfAbsent(s.getId().getSkillCode(), k -> new ArrayList<>())
                .add(s.getTotalSlu());
        }
        return seriesBySkill;
    }
}
