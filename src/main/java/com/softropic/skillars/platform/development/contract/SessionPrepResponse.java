package com.softropic.skillars.platform.development.contract;

import java.time.Instant;
import java.util.List;

/**
 * The calling coach's Session Prep Card for one upcoming booking. skillars-12-3 AC3. Carries no
 * player name or any other player-identifying field beyond the already-known {@link #playerId} —
 * the card's own {@code playerName} comes from the frontend's existing booking data, not this
 * response (AC5).
 *
 * @param lastSessionDate    the merged "last completed, attended session with this coach" signal
 *                            ({@link CoachPlayerLastSessionResolver}); null if this coach and this
 *                            player have never completed an attended session together
 * @param lastWhatWeDid      populated only when the timeline event that produced
 *                            {@code lastSessionDate} is itself the recap source — null otherwise,
 *                            even when {@code lastSessionDate} is non-null (AC3's consistency rule)
 * @param lastWhatsNext       see {@link #lastWhatWeDid}
 * @param focusThreadSkillCodes capped at {@code SessionFocusThreadCalculator.MAX_FOCUS_CODES},
 *                              ranked by frequency across the last
 *                              {@code SessionRepository.SESSION_WINDOW} session-builder sessions
 * @param openNeglectedSkillCount player-level, reused read-only exactly as story 12-1 does
 * @param playerRequest the player/parent-initiated "work on X next time" session-prep request
 *                        (skillars-deferred-155 AC6), read directly off the booking; null if never
 *                        set or cleared
 */
public record SessionPrepResponse(
    Long playerId,
    Instant lastSessionDate,
    String lastWhatWeDid,
    String lastWhatsNext,
    List<String> focusThreadSkillCodes,
    int openNeglectedSkillCount,
    String playerRequest
) {}
