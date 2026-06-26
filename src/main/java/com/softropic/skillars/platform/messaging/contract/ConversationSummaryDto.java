package com.softropic.skillars.platform.messaging.contract;

import java.time.Instant;

public record ConversationSummaryDto(
    Long conversationId,
    String otherPartyName,
    String otherPartyAvatarUrl,
    String lastMessagePreview,
    Instant lastMessageAt,
    long unreadCount
) {}
