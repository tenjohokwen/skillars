package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * One homework assignment as its assigning coach sees it: what was assigned, when, and whether / when the
 * player (or their parent, on the player's behalf) marked it done. Homework has no due date in the model,
 * so none is exposed.
 *
 * @param completedAt null while the assignment is open
 */
public record CoachHomeworkAssignmentResponse(
    UUID assignmentId,
    UUID drillId,
    String drillName,
    UUID bookingId,
    Instant assignedAt,
    boolean completed,
    Instant completedAt
) {}
