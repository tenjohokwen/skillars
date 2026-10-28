package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record DisputeResponse(
    UUID disputeId,
    UUID bookingId,
    String reason,
    String details,
    String status,
    String resolution,
    String resolutionNote,
    Instant createdAt,
    // The appeal window (FR-ADM-004, 14 days) runs from the resolution.
    Instant resolvedAt) {}
