package com.softropic.skillars.platform.session.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SessionPlanResponse(
    UUID id,
    UUID bookingId,
    UUID coachId,
    Long playerId,
    List<SessionBlockResponse> blocks,
    SessionDnaScore sessionDna,
    List<String> equipmentList,
    List<String> developmentFocus,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
