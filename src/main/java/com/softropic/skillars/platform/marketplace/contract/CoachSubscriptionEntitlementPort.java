package com.softropic.skillars.platform.marketplace.contract;

import java.util.UUID;

/**
 * fix/payment-subscription-entitlements: read-side gate behind CoachProfileService.getCoachSubscriptionTier.
 * marketplace.coach_subscriptions.tier is a projection the payment module syncs; this port lets the
 * marketplace refuse a paid projected tier when the payment-side subscription is no longer entitled
 * (implemented in the payment module, so marketplace takes no compile dependency on payment).
 */
public interface CoachSubscriptionEntitlementPort {

    /**
     * False only when a payment-side subscription row exists for the coach and its status does not
     * entitle a paid tier. A coach with no payment-side row keeps the projected tier (admin-provisioned
     * and pre-payment rows have none).
     */
    boolean isPaidTierEntitled(UUID coachId);
}
