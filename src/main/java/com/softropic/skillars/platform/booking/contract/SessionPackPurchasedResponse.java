package com.softropic.skillars.platform.booking.contract;

import java.time.Instant;
import java.util.UUID;

public record SessionPackPurchasedResponse(
    UUID id,
    UUID coachId,
    String coachDisplayName,
    int sessionCount,
    int creditsRemaining,
    Instant purchasedAt,
    String status
) {}
