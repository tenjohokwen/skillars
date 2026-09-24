package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * skillars-deferred-133 AC3: published by {@code StripeWebhookService.handleSubscriptionUpdated} when
 * an incoming {@code customer.subscription.updated} event carries a live/non-terminal Stripe status
 * ({@code active}/{@code trialing}/{@code past_due}) for a subscription that resolves to a known coach
 * but matches no local {@code payment.coach_subscriptions} row (and is not within the
 * {@code subscribeCoach} provisioning-race grace window) — a coach genuinely billed by Stripe with no
 * local record linking them to it. See {@code StripeWebhookService}'s own Javadoc for the full
 * detection logic and why this is alert-only, not auto-heal.
 */
public class CoachSubscriptionOrphanedEvent extends ApplicationEvent {

    private final UUID coachProfileId;
    private final String stripeSubscriptionId;

    public CoachSubscriptionOrphanedEvent(Object source, UUID coachProfileId, String stripeSubscriptionId) {
        super(source);
        this.coachProfileId = coachProfileId;
        this.stripeSubscriptionId = stripeSubscriptionId;
    }

    public UUID getCoachProfileId() { return coachProfileId; }
    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
}
