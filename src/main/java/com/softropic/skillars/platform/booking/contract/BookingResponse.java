package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
    UUID id,
    Long playerId,
    String playerName,
    UUID coachId,
    String coachDisplayName,
    Instant requestedStartTime,
    Instant requestedEndTime,
    String status,
    String canonicalTimezone,
    String notes,
    Instant createdAt,
    String parentName,
    RescheduleRequestResponse pendingReschedule,
    UUID batchId,
    Integer batchSize,
    String playerRequest,
    // feat/coach-workflow-gaps A23: true when the booking has a CAPTURED payment, i.e. the payer's
    // receipt endpoint will render it. Only computed for the payer's own list (getParentBookings);
    // false everywhere else.
    boolean receiptAvailable
) {}
