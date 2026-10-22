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
 * @param coachPlanNote the calling coach's own private plan note for this booking
 *                        (skillars-12-6 AC3); null if never set. Never leaves the coach
 * @param coachPlanNoteEditable whether the note may still be written right now
 *                        ({@code Booking.isPrepNoteEditable}, the same predicate the write path uses)
 * @param daysSinceLastSession whole days since {@link #lastSessionDate} on the server clock, clamped
 *                        at 0; null when {@code lastSessionDate} is null (AC8)
 * @param openNeglectedSkillCodes the open neglected-flag skill codes, de-duplicated and sorted
 *                        alphabetically; {@link #openNeglectedSkillCount} is its size (AC11)
 * @param suggestedWhatsNext the most recent non-blank "what's next" from an older attended session
 *                        with this coach, sanitised and capped at 500 chars; deliberately looser
 *                        than {@link #lastWhatsNext} (AC12). Null when none exists
 * @param suggestedWhatsNextSessionDate when the session that produced {@link #suggestedWhatsNext}
 *                        took place; null exactly when that is null
 * @param lastWhatWentWell the optional "what went well" recap field (skillars-12-7 AC11); same
 *                        recap-source rules as {@link #lastWhatWeDid}
 */
public record SessionPrepResponse(
    Long playerId,
    Instant lastSessionDate,
    String lastWhatWeDid,
    String lastWhatsNext,
    List<String> focusThreadSkillCodes,
    int openNeglectedSkillCount,
    String playerRequest,
    String coachPlanNote,
    boolean coachPlanNoteEditable,
    Long daysSinceLastSession,
    List<String> openNeglectedSkillCodes,
    String suggestedWhatsNext,
    Instant suggestedWhatsNextSessionDate,
    String lastWhatWentWell
) {}
