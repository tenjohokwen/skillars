package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record CoachStrikeHistoryDto(
    UUID strikeId,
    String reason,
    UUID bookingId,
    Instant createdAt,
    boolean acknowledged) {}
