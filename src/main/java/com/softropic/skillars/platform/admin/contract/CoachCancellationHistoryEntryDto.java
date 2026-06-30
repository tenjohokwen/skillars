package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record CoachCancellationHistoryEntryDto(
    UUID id,
    String cancelReason,
    UUID bookingId,
    Instant createdAt) {}
