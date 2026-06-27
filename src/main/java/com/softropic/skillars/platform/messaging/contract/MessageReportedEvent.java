package com.softropic.skillars.platform.messaging.contract;

public record MessageReportedEvent(
    Long reportId, Long messageId, Long conversationId,
    Long reportedBy, String reason) {}
