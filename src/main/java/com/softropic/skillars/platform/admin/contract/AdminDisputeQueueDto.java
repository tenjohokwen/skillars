package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record AdminDisputeQueueDto(
    UUID disputeId,
    UUID bookingId,
    String raisedByRole,
    String reason,
    String status,
    Instant createdAt) {}
