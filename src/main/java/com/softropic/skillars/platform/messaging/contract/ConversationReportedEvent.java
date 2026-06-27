package com.softropic.skillars.platform.messaging.contract;

public record ConversationReportedEvent(
    Long reportId, Long conversationId, Long reportedBy, String reason) {}
