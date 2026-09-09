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

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    /**
     * skillars-deferred-91 AC5 Part A: when {@code reserveCapture} wrote this CAPTURE_PENDING row.
     * Nullable — a row from before V124 has no stamp and stays on the manual CAPTURE_UNCONFIRMED
     * path (PaymentPendingSweeper only ages a row it can age).
     */
    @Column(name = "reserved_at")
    private Instant reservedAt;

    /**
     * skillars-deferred-106 AC3.5 / AC4.2: the {@code platform.commission.rate} in force when the
     * parent was charged. The coach payout net is computed from THIS rate (locked at capture), never
     * from {@code platform.commission.rate} re-read at completion — the rate may have changed in
     * between. Nullable — a row from before V133 has no stamp; the payout / cutover code falls back
     * to the live rate for those legacy already-CAPTURED rows only.
     */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;
}
