package com.softropic.skillars.platform.payment.contract.event;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;

// subscriberId is Long (player BIGINT TSID) — matches video.owner_id format.
// subscriptionTier must be "YEARLY", "MONTHLY", or "QUARTERLY" — NOT "ANNUAL".
// VideoSubscriptionLifecycleListener routes on "YEARLY" vs non-YEARLY (FR-PAY-008).
public class SubscriptionExpiredEvent extends ApplicationEvent {

    private final Long subscriberId;
    private final String subscriptionTier;
    private final Instant expiredAt;

    public SubscriptionExpiredEvent(Object source, Long subscriberId, String subscriptionTier, Instant expiredAt) {
        super(source);
        this.subscriberId = subscriberId;
        this.subscriptionTier = subscriptionTier;
        this.expiredAt = expiredAt;
    }

    public Long getSubscriberId() { return subscriberId; }
    public String getSubscriptionTier() { return subscriptionTier; }
    public Instant getExpiredAt() { return expiredAt; }
}
