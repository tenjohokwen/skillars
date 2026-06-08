package com.softropic.skillars.platform.video.contract;

import java.util.UUID;

public record RetryUploadRequest(UUID videoId, String ownerId, long fileSizeBytes) {}
