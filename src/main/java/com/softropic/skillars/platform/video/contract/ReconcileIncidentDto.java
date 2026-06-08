package com.softropic.skillars.platform.video.contract;

import java.time.Instant;
import java.util.UUID;

public record ReconcileIncidentDto(
        UUID id,
        ReconciliationIncidentType incidentType,
        String providerAssetId,
        String description,
        Instant createdAt) {}
