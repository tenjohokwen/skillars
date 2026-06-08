package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

public record UploadSessionDto(
        UUID id,
        UUID videoId,
        UploadSessionStatus status,
        long reservedBytes,
        Instant expiresAt,
        Instant createdAt) {}
