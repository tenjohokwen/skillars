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

    // skillars-deferred-153 AC8: the recency NORMALIZER is now decoupled from the operator-
    // configurable dormantAfterDays threshold — retuning that key previously silently reshaped the
    // whole attentionScore ranking (and degenerated at its own legal bounds: min=1 saturated every
    // non-today player to 1.0, max=3650 effectively switched recency off). dormantAfterDays stays
    // in Signals and continues to control ONLY the `dormant` boolean below — this constant is a
    // fixed 90-day horizon, reusing 90 to match this module's existing coachAccessExpiryDays
    // default (TimelineQueryService), not a second operator-configurable key.
    private static final long RECENCY_NORMALIZATION_DAYS = 90;

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
     * @param dormantAfterDays     the configured threshold (AC4) — used for the dormancy comparison
     *                              ONLY. skillars-deferred-153 AC8 decoupled the recency score from
     *                              this value; its clamp ceiling is now the fixed
     *                              {@link #RECENCY_NORMALIZATION_DAYS}, so retuning this operator key
     *                              no longer reshapes the whole ranking
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
        // skillars-deferred-152 AC5: was strictly `>`, which disagreed with recencyNorm's own
        // clamp for exactly one day (d == dormantAfterDays). `>=` closed that one-day gap. Keep the
        // `>=`, but note its original rationale no longer holds: skillars-deferred-153 AC8 moved the
        // recency clamp onto the fixed RECENCY_NORMALIZATION_DAYS, so the two lines below are now
        // deliberately independent and recencyNorm is NOT saturated at d == dormantAfterDays (at the
        // default 30 it is 30/90 ≈ 0.33). `>=` is retained as the correct dormancy boundary in its own
        // right — "silent for at least the configured window" — not to align with the recency term.
        // Do not touch daysSinceLastActivity's own computation (CoachRosterService), which also feeds
        // the roster's sort order and would silently re-score every player away from this boundary.
        boolean dormant = signals.daysSinceLastActivity() >= signals.dormantAfterDays()
            && !signals.hasUpcomingBooking();

        // Defense-in-depth only: CoachRosterService now derives daysSinceLastActivity from
        // past-only activity (see the Signals Javadoc), so a negative value should be unreachable —
        // this guard just keeps the score well-defined if that invariant is ever violated upstream.
        //
        // skillars-deferred-153 AC8: clamp/normalize against the FIXED RECENCY_NORMALIZATION_DAYS,
        // not the operator-configurable dormantAfterDays — this line and the `dormant` boolean
        // above are now deliberately doing different things with the same daysSinceLastActivity
        // input. dormantAfterDays itself is floored at 1 by its own config bound, so it cannot be 0
        // here either way — not a new risk, but worth noting since this divides by a different
        // constant now.
        long clampedDays = Math.min(Math.max(signals.daysSinceLastActivity(), 0), RECENCY_NORMALIZATION_DAYS);
        double recencyNorm = (double) clampedDays / RECENCY_NORMALIZATION_DAYS;

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
