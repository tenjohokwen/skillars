package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

public record VideoPublishedEvent(UUID videoId, String ownerId) {}
