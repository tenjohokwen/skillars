package com.softropic.skillars.platform.messaging.contract;

import java.time.Instant;

public record MessageDto(
    Long messageId,
    Long senderId,
    String senderRole,
    String content,
    String moderationStatus,
    Instant deliveredAt,
    Instant createdAt
) {}
