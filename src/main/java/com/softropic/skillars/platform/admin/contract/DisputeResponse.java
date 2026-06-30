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
    Instant createdAt) {}
