package com.softropic.skillars.platform.development.service;

/**
 * Pure, stateless attention-score + dormancy computation for {@link CoachRosterService}.
 * skillars-12-1 AC4/AC5/AC6, Task 2.5/2.6.
 *
 * <p>Weights (0.4 recency / 0.35 neglected / 0.25 declining) are a starting point, not a pinned
 * contract — only the relative ordering in AC5 is tested.
 */
final class RosterAttentionCalculator {

    private static final int NEGLECTED_CAP = 5;
    // skillars-12-1 code review (2026-10-15) Patch, owner decision: a bare ratio
    // (decliningCount / countWithTrendData) let a player with few tracked skills outrank one with
    // many more declining skills, and read as 0 — identical to "no declining skills" — for an
    // abandoned player whose every skill is INSUFFICIENT_DATA. A capped absolute count, mirroring
    // NEGLECTED_CAP directly above, satisfies AC5's count-based contract without that inversion.
    private static final int DECLINING_CAP = 5;
    private static final double RECENCY_WEIGHT = 0.4;
    private static final double NEGLECTED_WEIGHT = 0.35;
    private static final double DECLINING_WEIGHT = 0.25;

    private RosterAttentionCalculator() {
    }

    /**
     * @param daysSinceLastActivity days since the player's last **past** activity (the later of
     *                              {@code lastSessionDate} and {@code min(latestBookingTime, now)})
     *                              — never negative for any real roster player (see AC6: a
     *                              just-created booking yields {@code ~0}, not a null-propagated
     *                              maximum-urgency value). A future-dated {@code latestBookingTime}
     *                              is excluded from this signal entirely (see
     *                              {@code hasUpcomingBooking}) rather than clamped to {@code now},
     *                              so a long-silent player with a newly-booked future session still
     *                              scores on their real last activity.
     * @param dormantAfterDays     the configured threshold (AC4) — the raw value used for the
     *                              dormancy comparison, and the clamp ceiling for the recency score
     *                              component
     * @param hasUpcomingBooking   true when the player has a roster-qualifying booking whose
     *                              {@code requestedStartTime} is in the future — code review
     *                              (2026-10-15) Patch, owner decision: suppresses only the
     *                              {@code dormant} badge ("do I need to chase them?"), independent
     *                              of the recency score ("how long has it been?") — a coach who
     *                              already booked a silent player's next session doesn't need a
     *                              dormant flag, but the player's real idle time still counts.
     */
    record Signals(
        long daysSinceLastActivity,
        int neglectedCount,
        int decliningCount,
        long dormantAfterDays,
        boolean hasUpcomingBooking
    ) {}

    record Result(double attentionScore, boolean dormant) {}

    static Result compute(Signals signals) {
        boolean dormant = signals.daysSinceLastActivity() > signals.dormantAfterDays()
            && !signals.hasUpcomingBooking();

        // Defense-in-depth only: CoachRosterService now derives daysSinceLastActivity from
        // past-only activity (see the Signals Javadoc), so a negative value should be unreachable —
        // this guard just keeps the score well-defined if that invariant is ever violated upstream.
        long clampedDays = Math.min(Math.max(signals.daysSinceLastActivity(), 0), signals.dormantAfterDays());
        double recencyNorm = (double) clampedDays / signals.dormantAfterDays();

        int clampedNeglected = Math.min(Math.max(signals.neglectedCount(), 0), NEGLECTED_CAP);
        double neglectedNorm = (double) clampedNeglected / NEGLECTED_CAP;

        int clampedDeclining = Math.min(Math.max(signals.decliningCount(), 0), DECLINING_CAP);
        double decliningNorm = (double) clampedDeclining / DECLINING_CAP;

        double score = RECENCY_WEIGHT * recencyNorm
            + NEGLECTED_WEIGHT * neglectedNorm
            + DECLINING_WEIGHT * decliningNorm;

        return new Result(score, dormant);
    }
}
