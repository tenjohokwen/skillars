package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.UUID;

/** Admin view of a safeguarding check on record. The inspector id stays on this admin-only DTO. */
public record AdminSafeguardingRecordDto(
    UUID coachId,
    String coachDisplayName,
    String checkType,
    Instant inspectedAt,
    Instant expiresAt,
    Long inspectedBy
) {
}
