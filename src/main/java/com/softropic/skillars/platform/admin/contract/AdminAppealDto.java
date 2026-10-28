package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admin queue row / detail view of an appeal. {@code subject} is a small, subject-type-specific summary
 * of the enforced record (e.g. strike reason + booking id, review rating + body, dispute resolution +
 * note) — empty in queue rows, populated by the detail endpoint. Null when the record no longer exists.
 */
public record AdminAppealDto(
    UUID appealId,
    AppealSubjectType subjectType,
    UUID subjectId,
    Long appellantUserId,
    String appellantName,
    Instant enforcedAt,
    String reason,
    AppealStatus status,
    String decisionNote,
    Long decidedBy,
    Instant decidedAt,
    Boolean reversalApplied,
    Instant createdAt,
    Map<String, Object> subject
) {}
