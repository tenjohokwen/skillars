package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

public record AdminGdprRequestDto(
    UUID requestId,
    Long userId,
    String requestType,
    String status,
    Instant createdAt,
    Instant completedAt
) {}
