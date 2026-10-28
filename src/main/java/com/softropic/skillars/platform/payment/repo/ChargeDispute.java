package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * fix/payment-payouts-disputes-webhooks (V193): a Stripe card dispute (chargeback) against a platform
 * PaymentIntent, recorded from {@code charge.dispute.created} and settled by {@code charge.dispute.closed}.
 * While {@link #STATUS_OPEN}, every not-yet-released coach payout the intent funded is frozen — including
 * one created later, when a session the charge paid for completes ({@code CoachPayoutOutboxSupport}).
 */
@Entity
@Table(schema = "payment", name = "charge_disputes")
@Getter
@Setter
@NoArgsConstructor
public class ChargeDispute {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    @Id
    @Column(name = "stripe_dispute_id", nullable = false, updatable = false)
    private String stripeDisputeId;

    @Column(name = "payment_intent_id", nullable = false)
    private String paymentIntentId;

    @Column(name = "charge_id")
    private String chargeId;

    /** The disputed amount, in major units. */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Stripe's own dispute status at the last event (needs_response, won, lost, warning_closed, ...). */
    @Column(name = "stripe_status", length = 40)
    private String stripeStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
