package com.softropic.skillars.platform.messaging.contract;

public record ModerationFailureEvent(Long messageId, Long conversationId, String failureReason) {}
