package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

public record VideoModerationRetryEvent(UUID videoId, String ownerId) {}
