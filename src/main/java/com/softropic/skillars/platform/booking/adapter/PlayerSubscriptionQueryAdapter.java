package com.softropic.skillars.platform.booking.adapter;

import com.softropic.skillars.platform.video.contract.PlayerSubscriptionQueryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Temporary stub implementation of PlayerSubscriptionQueryPort in platform.booking.
 * Epic 7 (Payments & Subscriptions) will replace this with a real adapter in platform.payment
 * backed by the subscription_tiers table. Until then all queries return false (no subscription).
 *
 * IMPORTANT: returning false from hasActiveYearlySubscription means no yearly exemptions are applied
 * (conservative). Returning false from hasAnyActiveSubscription means subscription expiry events will
 * always trigger the BLOCKED transition (correct for a world with no subscriptions yet).
 */
@Slf4j
@Component
public class PlayerSubscriptionQueryAdapter implements PlayerSubscriptionQueryPort {

    @Override
    public boolean hasActiveYearlySubscription(UUID playerId) {
        log.debug("hasActiveYearlySubscription: platform.payment not yet available (Epic 7); returning false for playerId={}", playerId);
        return false;
    }

    @Override
    public boolean hasAnyActiveSubscription(UUID playerId) {
        log.debug("hasAnyActiveSubscription: platform.payment not yet available (Epic 7); returning false for playerId={}", playerId);
        return false;
    }
}
