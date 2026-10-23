package com.softropic.skillars.platform.development.contract;

import java.time.Instant;

/**
 * One player on the calling coach's roster, ranked by {@link #attentionScore}. skillars-12-1 AC3.
 *
 * @param lastSessionDate the end of the most recent attended session with this coach (session time, skillars-12-10); null
 *                        if this coach has neither an SLU row nor a coach-attributed SESSION_COMPLETED
 *                        timeline row for the player yet (a brand-new relationship, or only non-attended
 *                        wrap-ups) — see Dev Notes
 *                        "lastSessionDate vs. last activity" for why {@link #dormant} should be
 *                        preferred over inferring urgency from a null value here.
 * @param daysSinceLastSession whole days from {@link #lastSessionDate} to now (server clock, clamped
 *                        at 0); null exactly when {@code lastSessionDate} is null (skillars-12-6 AC8)
 * @param hasUpcomingBooking whether the player's latest roster-qualifying booking is in the future,
 *                        so the client can tell "booked but no attended session yet" from "first
 *                        session still ahead" without contradicting the dormant badge (AC9)
 * @param accessExpired skillars-12-10 (D6): the coach-access window has closed. The entry is listed (name and
 *                        last-session facts only) but the counts/scores are zeroed and the detail page is not
 *                        reachable; computed with the same rule the read endpoints enforce
 */
public record RosterEntryResponse(
    Long playerId,
    String playerName,
    Instant lastSessionDate,
    int openNeglectedSkillCount,
    boolean dormant,
    double attentionScore,
    Long daysSinceLastSession,
    boolean hasUpcomingBooking,
    boolean accessExpired
) {}
