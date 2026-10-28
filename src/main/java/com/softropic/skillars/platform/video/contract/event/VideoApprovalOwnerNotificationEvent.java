package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

/**
 * The parent decided on a minor's video. Published inside the decision's transaction; consumed
 * BEFORE_COMMIT by {@code platform.notification.infrastructure.listener.VideoOwnerEmailListener}.
 *
 * @param playerId         PlayerProfile id (the video's owner), not a user id
 * @param decision         "approved" or "rejected"
 * @param recipientEmail   the player's own account email, resolved by the publisher; null = no reachable address
 * @param recipientLangKey that account's language, may be null
 */
public record VideoApprovalOwnerNotificationEvent(UUID videoId, Long playerId, String decision,
                                                  String recipientEmail, String recipientLangKey) {}
