package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.UUID;

public record HomeworkAssignmentResponse(
    UUID assignmentId,
    UUID drillId,
    DrillResponse drill,
    UUID coachId,
    String coachDisplayName,
    Instant assignedAt,
    boolean completed
) {}
