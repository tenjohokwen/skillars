package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * @param awaitingWrapUp fix/audit3-completion-dispute-integrity: the family confirmed before the coach's wrap-up —
 *     the nudge resumes the wrap-up (attendance, ratings, homework, recap), not the recap-only dialog
 */
public record MissingRecapItem(UUID bookingId, Long playerId, String playerName, Instant sessionDate,
                               boolean awaitingWrapUp) {}
