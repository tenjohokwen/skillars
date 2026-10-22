package com.softropic.skillars.platform.session.contract;

import java.util.List;
import java.util.UUID;

/**
 * skillars-12-8: published by {@code HomeworkAssignmentService.handleBookingCompleted} with ONLY the drill ids
 * newly persisted in that invocation (a replayed BookingCompletedEvent publishes nothing). Ids only; the
 * timeline consumer resolves drill names itself. Consumed by {@code development.TimelineEventListener}.
 */
public record HomeworkAssignedEvent(UUID bookingId, Long playerId, UUID coachId, List<UUID> drillIds) {}
