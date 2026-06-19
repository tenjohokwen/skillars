package com.softropic.skillars.platform.development.contract;

import java.time.Instant;
import java.util.UUID;

public record PerformanceReportResponse(
    UUID id,
    String coachName,
    Instant generatedAt,
    String signedDownloadUrl  // generated on demand; NOT stored
) {}
