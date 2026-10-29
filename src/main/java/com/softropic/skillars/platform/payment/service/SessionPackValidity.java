package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigBounds;
import com.softropic.skillars.platform.config.service.ConfigService;

/**
 * fix/pack-expiry-size-tiers: how long a session pack is valid from purchase, by its session count —
 * 1 → {@code pack.expiry.days.tier1}, 2-5 → tier2, 6-10 → tier3, 11+ → tier4 (seeded 90 / 180 / 365 /
 * 548 by V139). Replaces the flat 60 days {@code createPurchase} used to stamp. The value is fixed into
 * {@code expires_at} at purchase; a later config change never moves an existing pack's expiry.
 */
final class SessionPackValidity {

    private SessionPackValidity() {
    }

    static ConfigBounds.BoundedKey tierKey(int sessionCount) {
        if (sessionCount <= 1) {
            return ConfigBounds.PACK_EXPIRY_DAYS_TIER1;
        }
        if (sessionCount <= 5) {
            return ConfigBounds.PACK_EXPIRY_DAYS_TIER2;
        }
        if (sessionCount <= 10) {
            return ConfigBounds.PACK_EXPIRY_DAYS_TIER3;
        }
        return ConfigBounds.PACK_EXPIRY_DAYS_TIER4;
    }

    /**
     * Validity in days for a pack of {@code sessionCount} sessions. A bad stored value falls back to the
     * registry default (the V139 seed) inside {@code getBoundedLong}; this never throws for a bad value,
     * so it cannot fail a purchase the payer has already been charged for.
     */
    static long days(ConfigService configService, int sessionCount) {
        return configService.getBoundedLong(tierKey(sessionCount));
    }
}
