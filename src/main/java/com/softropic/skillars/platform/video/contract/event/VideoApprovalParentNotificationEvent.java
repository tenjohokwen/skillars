package com.softropic.skillars.platform.video.contract.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A minor's video is waiting for the parent's approval. Published inside the transaction that creates
 * the approval request; consumed BEFORE_COMMIT by
 * {@code platform.notification.infrastructure.listener.VideoOwnerEmailListener}, which emails the
 * parent and writes an inbox row deep-linking to {@code /parent/approvals}.
 *
 * @param playerId       PlayerProfile id
 * @param parentId       the primary parent's user id (PlayerProfile.parentId)
 * @param parentEmail    that parent's email, resolved by the publisher; null = no reachable address
 * @param parentLangKey  that parent's language, may be null
 */
public record VideoApprovalParentNotificationEvent(
        UUID videoId,
        Long playerId,
        Long parentId,
        String playerName,
        String videoType,
        Instant videoCreatedAt,
        String parentEmail,
        String parentLangKey) {}
