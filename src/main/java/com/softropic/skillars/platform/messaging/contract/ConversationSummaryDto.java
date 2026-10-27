package com.softropic.skillars.platform.messaging.contract;

import java.time.Instant;

/**
 * @param status         the conversation's {@link ConversationStatus} name. BLOCKED rows reach a caller
 *                       through the parental-oversight list and through {@code POST /conversations}
 *                       (which returns an existing thread whatever its status), so the client must be
 *                       able to render them read-only.
 * @param canSend        whether the CALLER, in the role the request resolved to, may post into this
 *                       conversation right now: not BLOCKED and allowed by the player's current age
 *                       tier (story 8.2 table). Advisory for the UI only — sendMessage re-checks every
 *                       rule itself.
 * @param parentObserver true when the caller is a PARENT whose player is in the 13–17 tier, i.e. the
 *                       parent has oversight visibility rather than being the primary participant.
 */
public record ConversationSummaryDto(
    Long conversationId,
    String otherPartyName,
    String otherPartyAvatarUrl,
    String lastMessagePreview,
    Instant lastMessageAt,
    long unreadCount,
    String status,
    boolean canSend,
    boolean parentObserver
) {}
