package com.softropic.skillars.platform.messaging.contract;

public record MessageHeldForReviewEvent(Long messageId, Long conversationId, String reason) {}
