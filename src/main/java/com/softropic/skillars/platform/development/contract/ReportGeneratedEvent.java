package com.softropic.skillars.platform.development.contract;

import java.time.Instant;
import java.util.UUID;

public record ReportGeneratedEvent(
    UUID reportId,
    Long playerId,
    String coachName,
    String playerName,
    Instant generatedAt,
    byte[] pdfBytes
) {}
