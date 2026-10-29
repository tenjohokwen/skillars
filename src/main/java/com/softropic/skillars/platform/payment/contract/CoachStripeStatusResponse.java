package com.softropic.skillars.platform.payment.contract;

import java.util.UUID;

public record CoachStripeStatusResponse(
    UUID coachId,
    String onboardingStatus,
    boolean chargesEnabled,
    boolean payoutsEnabled,
    /**
     * fix/payment-stripe-connect-webhook-endpoint: the coach disconnected the platform from their
     * Stripe account ({@code account.application.deauthorized}); the UI offers a reconnect.
     */
    boolean disconnected
) {
    public CoachStripeStatusResponse(UUID coachId, String onboardingStatus, boolean chargesEnabled,
                                     boolean payoutsEnabled) {
        this(coachId, onboardingStatus, chargesEnabled, payoutsEnabled, false);
    }
}
