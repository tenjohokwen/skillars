package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

public record VideoModerationOwnerNotificationEvent(UUID videoId, String ownerId, String message) {}
