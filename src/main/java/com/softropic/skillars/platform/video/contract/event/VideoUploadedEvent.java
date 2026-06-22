package com.softropic.skillars.platform.video.contract.event;

import java.util.UUID;

public record VideoUploadedEvent(UUID videoId, String ownerId) {}
