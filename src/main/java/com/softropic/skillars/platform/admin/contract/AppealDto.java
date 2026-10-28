package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/** The appellant's own view of one of their appeals. */
public record AppealDto(
    UUID appealId,
    AppealSubjectType subjectType,
    UUID subjectId,
    Instant enforcedAt,
    String reason,
    AppealStatus status,
    String decisionNote,
    Instant decidedAt,
    Instant createdAt
) {}
