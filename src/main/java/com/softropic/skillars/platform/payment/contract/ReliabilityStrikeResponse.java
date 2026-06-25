package com.softropic.skillars.platform.payment.contract;

import java.time.Instant;
import java.util.UUID;

public record ReliabilityStrikeResponse(
    UUID strikeId,
    UUID bookingId,
    String reason,
    Instant issuedAt,
    boolean acknowledged
) {}
