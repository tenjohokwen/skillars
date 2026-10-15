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
 */
public record RosterEntryResponse(
    Long playerId,
    String playerName,
    Instant lastSessionDate,
    int openNeglectedSkillCount,
    boolean dormant,
    double attentionScore
) {}
