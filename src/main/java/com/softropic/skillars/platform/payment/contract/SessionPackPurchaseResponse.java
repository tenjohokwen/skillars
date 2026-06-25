package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SessionPackPurchaseResponse(
    UUID purchaseId,
    UUID packTierId,
    String label,
    int sessionCount,
    int remainingSessions,
    BigDecimal pricePerSession,
    Instant expiresAt,
    String stripePaymentIntentId
) {}
