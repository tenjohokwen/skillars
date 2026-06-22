package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

// videoId and ownerId are nullable — some alert paths (service unavailability) do not have a specific video context
public record VideoModerationAdminAlertEvent(UUID videoId, String ownerId, String subject, String body, boolean urgent) {}
