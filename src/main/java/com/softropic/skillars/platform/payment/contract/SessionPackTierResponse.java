package com.softropic.skillars.platform.payment.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @param validityDays fix/pack-expiry-size-tiers: how many days a pack bought from this tier now would
 *                     stay valid (its session-count tier's {@code pack.expiry.days.tierN}) — shown on the
 *                     purchase page; the purchased pack's own {@code expiresAt} is what then binds.
 */
public record SessionPackTierResponse(
    UUID packTierId,
    UUID coachId,
    String label,
    int sessionCount,
    BigDecimal totalPrice,
    BigDecimal pricePerSession,
    boolean isActive,
    Instant createdAt,
    long validityDays
) {}
