package com.softropic.skillars.platform.admin.contract;

import java.time.Instant;
import java.util.List;

public record AdminMessageDetailDto(
    Long messageId,
    Long conversationId,
    Long senderId,
    String senderRole,
    String content,
    String moderationStatus,
    Instant deliveredAt,
    Instant createdAt,
    List<AdminMessageContextDto> contextBefore,
    List<AdminMessageContextDto> contextAfter,
    List<AdminMessageReportDto> reports) {}
