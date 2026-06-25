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
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "booking_payments")
@Getter
@Setter
@NoArgsConstructor
public class BookingPayment {

    @Id
    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "batch_payment_intent_id")
    private UUID batchPaymentIntentId;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "credit_debited", nullable = false, precision = 10, scale = 2)
    private BigDecimal creditDebited = BigDecimal.ZERO;

    @Column(name = "stripe_charged", nullable = false, precision = 10, scale = 2)
    private BigDecimal stripeCharged = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "frozen_at")
    private Instant frozenAt;
}
