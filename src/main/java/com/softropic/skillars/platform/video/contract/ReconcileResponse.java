package com.softropic.skillars.platform.video.contract;

import java.util.UUID;

public record ReconcileResponse(
        UUID videoId,
        OperationalState operationalState,
        AccessState accessState,
        ReconcileIncidentDto incident) {}
