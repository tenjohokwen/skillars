package com.softropic.skillars.platform.development.contract;

import java.time.Instant;

/**
 * One player on the calling coach's roster, ranked by {@link #attentionScore}. skillars-12-1 AC3.
 *
 * @param lastSessionDate the most recent SLU-recorded session date for this coach-player pair; null
 *                        if this coach has no SLU rows for the player yet (a Quick-Complete-only or
 *                        session-builder-less history, or a brand-new relationship) — see Dev Notes
 *                        "lastSessionDate vs. last activity" for why {@link #dormant} should be
 *                        preferred over inferring urgency from a null value here.
 * @param daysSinceLastSession whole days from {@link #lastSessionDate} to now (server clock, clamped
 *                        at 0); null exactly when {@code lastSessionDate} is null (skillars-12-6 AC8)
 * @param hasUpcomingBooking whether the player's latest roster-qualifying booking is in the future,
 *                        so the client can tell "booked but no attended session yet" from "first
 *                        session still ahead" without contradicting the dormant badge (AC9)
 */
public record RosterEntryResponse(
    Long playerId,
    String playerName,
    Instant lastSessionDate,
    int openNeglectedSkillCount,
    boolean dormant,
    double attentionScore,
    Long daysSinceLastSession,
    boolean hasUpcomingBooking
) {}
