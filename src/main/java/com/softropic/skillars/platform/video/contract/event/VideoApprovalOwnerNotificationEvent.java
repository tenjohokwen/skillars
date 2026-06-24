package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

// Consumed by platform.notification.infrastructure.listener.VideoApprovalEmailListener (Story 7.x)
// decision: "approved" or "rejected"
public record VideoApprovalOwnerNotificationEvent(UUID videoId, Long playerId, String decision) {}
