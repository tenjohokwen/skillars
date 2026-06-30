package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;

public record AdminMessageContextDto(
    Long messageId,
    String senderRole,
    String content,
    String moderationStatus,
    Instant createdAt) {}
