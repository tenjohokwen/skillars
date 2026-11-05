package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * skillars-deferred-169 AC4: a {@code customer.subscription.updated|deleted} that found no local subscription row (it raced the
 * signup) and was parked to be re-applied once the row exists. Holds the already-extracted fields, not raw JSON. No FK: the
 * webhook's own marker row is uncommitted when the park runs. Rows are inserted only by
 * {@link StripePendingSubscriptionEventRepository#insertIfAbsent} and removed by claim/purge.
 */
@Entity
@Table(schema = "payment", name = "stripe_pending_subscription_events")
@Getter
@NoArgsConstructor
public class StripePendingSubscriptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "stripe_subscription_id", nullable = false, updatable = false)
    private String stripeSubscriptionId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "event_created", updatable = false)
    private Long eventCreated;

    @Column(name = "status", updatable = false)
    private String status;

    @Column(name = "current_period_end", updatable = false)
    private Long currentPeriodEnd;

    @Column(name = "cancel_at_period_end", updatable = false)
    private Boolean cancelAtPeriodEnd;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
