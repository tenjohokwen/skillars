package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SessionPackTierResponse(
    UUID packTierId,
    UUID coachId,
    String label,
    int sessionCount,
    BigDecimal totalPrice,
    BigDecimal pricePerSession,
    boolean isActive,
    Instant createdAt
) {}
