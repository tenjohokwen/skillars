package com.softropic.skillars.platform.video.contract.event;

import java.time.Instant;
import java.util.UUID;

// Consumed by platform.notification.infrastructure.listener.VideoApprovalEmailListener (Story 7.x)
// Deep-link target for notification: /parent/approvals — see Story 7.x notification consumer
public record VideoApprovalParentNotificationEvent(
        UUID videoId,
        Long playerId,
        Long parentId,
        String playerName,
        String videoType,
        Instant videoCreatedAt) {}
