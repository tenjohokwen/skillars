package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;

/** One admin_action_log entry about a user account (suspend / reactivate / login unlock). */
public record AdminUserActionDto(
    String actionType,
    Long adminId,
    String reason,
    Instant createdAt) {}
