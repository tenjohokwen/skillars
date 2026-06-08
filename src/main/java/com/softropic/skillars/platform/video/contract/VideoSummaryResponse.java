package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

public record VideoSummaryResponse(
        UUID id,
        String title,
        OperationalState operationalState,
        AccessState accessState,
        String ownerId,
        Visibility visibility,
        Instant updatedAt) {}
