package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

public record VideoApprovalResponse(
        UUID id,
        UUID videoId,
        Long playerId,
        String playerName,
        String videoType,
        String status,
        Instant createdAt) {}
