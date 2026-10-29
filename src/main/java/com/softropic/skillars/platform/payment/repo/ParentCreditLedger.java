package com.softropic.skillars.platform.payment.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "payment", name = "parent_credit_ledger")
@Getter
@NoArgsConstructor
public class ParentCreditLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tx_id", updatable = false, nullable = false)
    private UUID txId;

    @Setter
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Setter
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Setter
    @Column(nullable = false, length = 32)
    private String type;

    @Setter
    @Column(name = "reference_id")
    private UUID referenceId;

    @Setter
    @Column(length = 500)
    private String description;

    /**
     * fix/payment-dispute-credit-trace-and-lost-dispute (V199): the card charge (PaymentIntent) a refund
     * credit came from, when the booking's own {@code booking_payments} row does not say it. NULL = trace
     * through {@code booking_payments} / the booking's pack.
     */
    @Setter
    @Column(name = "source_payment_intent_id")
    private String sourcePaymentIntentId;

    /** V199: how much of {@link #amount} was card money from {@link #sourcePaymentIntentId}. */
    @Setter
    @Column(name = "source_card_amount", precision = 10, scale = 2)
    private BigDecimal sourceCardAmount;

    /**
     * fix/payment-wallet-dispute-integrity (V208): the part of {@link #amount} in the SPEND-ONLY pool — same
     * sign as {@code amount}, never larger in magnitude; zero = all cashable. The wallet's spend-only balance
     * is the sum of this column, its cashable balance the rest ({@code CreditWalletService.getWalletBalance}).
     */
    @Setter
    @Column(name = "spend_only_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal spendOnlyAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
