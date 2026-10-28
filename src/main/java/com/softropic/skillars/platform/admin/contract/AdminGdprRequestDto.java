package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/** {@code playerId} is non-null for a parent-requested export/erasure of a child profile (V172). */
public record AdminGdprRequestDto(
    UUID requestId,
    Long userId,
    Long playerId,
    String requestType,
    String status,
    Instant createdAt,
    Instant completedAt
) {}
